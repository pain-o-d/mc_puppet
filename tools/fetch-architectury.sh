#!/usr/bin/env bash
# Mirrors artifacts from maven.architectury.dev into the local Maven repo.
#
# Why this exists: on some networks (this one included) that host stalls on any
# response larger than ~20KB, while ranged requests below that succeed. Gradle
# reports the resulting timeout as "plugin not found", which is misleading.
# Fetching in 16KB ranges sidesteps the stall; every file is checksum-verified
# against the repo's own .sha1 so a silently truncated download cannot slip in.
#
# Usage: tools/fetch-architectury.sh
set -uo pipefail

REPO="https://maven.architectury.dev"
DEST="${HOME}/.m2/repository"
CHUNK=16384
ok=0; skip=0; fail=0

# group:artifact:version[:classifier,classifier] — the plugin markers plus the
# transitive artifacts Gradle cannot reach on its own. The transformer's
# -runtime and -agent jars are only pulled when a dev client or server actually
# launches, which is why they surfaced later than the rest.
ARTIFACTS="
architectury-plugin:architectury-plugin.gradle.plugin:3.5.169
dev.architectury:architectury-transformer:5.2.91:runtime,agent
dev.architectury:tiny-remapper:1.1.0
dev.architectury.loom:dev.architectury.loom.gradle.plugin:1.17.491
dev.architectury:architectury-loom:1.17.491
dev.architectury:mercury:0.4.3.18
dev.architectury:refmap-remapper:1.0.5
dev.architectury:at:1.0.1
dev.architectury:architectury:13.0.11
dev.architectury:architectury-fabric:13.0.11
dev.architectury:architectury-neoforge:13.0.11
dev.architectury:architectury:9.2.14
dev.architectury:architectury-fabric:9.2.14
dev.architectury:architectury-forge:9.2.14
"

fetch() {
    local path="$1" out="$2"
    local size
    size=$(curl -sI --max-time 30 "$REPO/$path" 2>/dev/null \
           | tr -d '\r' | awk '/[Cc]ontent-[Ll]ength:/{print $2}' | tail -1)
    [ -z "${size:-}" ] && return 1

    : > "$out.part"
    local off=0 end
    while [ "$off" -lt "$size" ]; do
        end=$((off + CHUNK - 1))
        [ "$end" -ge "$size" ] && end=$((size - 1))
        curl -s --max-time 45 -H "Range: bytes=$off-$end" "$REPO/$path" >> "$out.part" || return 1
        off=$((end + 1))
    done

    [ "$(wc -c < "$out.part")" -eq "$size" ] || return 1
    mv "$out.part" "$out"
}

verify() {
    local file="$1" path="$2" want got
    want=$(curl -s --max-time 30 "$REPO/$path.sha1" 2>/dev/null | tr -d '[:space:]' | cut -c1-40)
    [ -z "$want" ] && return 0                       # no checksum published
    got=$(sha1sum "$file" | cut -d' ' -f1)
    [ "$want" = "$got" ]
}

for coord in $ARTIFACTS; do
    [ -z "$coord" ] && continue
    group="${coord%%:*}"; rest="${coord#*:}"
    artifact="${rest%%:*}"; rest="${rest#*:}"
    version="${rest%%:*}"
    # Everything after the version, when present, is a comma-separated
    # classifier list; "" is the plain artifact and is always fetched.
    if [ "$rest" = "$version" ]; then
        classifiers=""
    else
        classifiers="${rest#*:}"
    fi
    gpath="${group//./\/}"

    variants=""
    for c in $(echo "$classifiers" | tr ',' ' '); do
        variants="$variants -$c"
    done

    for variant in "" $variants; do
    for ext in jar pom module; do
        # Only the plain artifact publishes a pom or module descriptor.
        [ -n "$variant" ] && [ "$ext" != jar ] && continue

        name="$artifact-$version$variant.$ext"
        path="$gpath/$artifact/$version/$name"
        out="$DEST/$gpath/$artifact/$version/$name"

        if [ -s "$out" ]; then skip=$((skip+1)); continue; fi
        mkdir -p "$(dirname "$out")"

        if fetch "$path" "$out" && verify "$out" "$path"; then
            echo "  ok       $name"
            ok=$((ok+1))
            continue
        fi

        rm -f "$out" "$out.part"

        # Not every artifact publishes every extension: plugin markers are
        # pom-only, and .module is absent on older publications. Only treat a
        # missing file as an error when the server says it is actually there.
        if curl -sI --max-time 30 "$REPO/$path" 2>/dev/null | head -1 | grep -q '200'; then
            echo "  FAILED   $name"
            fail=$((fail+1))
        fi
    done
    done
done

echo
echo "fetched $ok, already present $skip, failed $fail"
[ "$fail" -eq 0 ]
