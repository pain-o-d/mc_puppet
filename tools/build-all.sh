#!/bin/sh
# Builds every jar: 1.21.1 for Fabric and NeoForge at the root, 1.20.1 for Fabric and
# Forge in mc1.20.1/, each build running the unit tests against its own game.
# See docs/MULTIVERSION.md. Pass --offline to skip the network.
set -e
cd "$(dirname "$0")/.."
node tools/check-twins.js
echo "== Minecraft 1.21.1 (Fabric, NeoForge)"
./gradlew build "$@"
echo "== Minecraft 1.20.1 (Fabric, Forge)"
(cd mc1.20.1 && ./gradlew build "$@")
echo "== tools"
# The whole node suite (npm test: scenario, launch, connection, mcp, junit). It goes to a file so
# that a failure fails the script (a pipe into grep would hide it) and only the summary is shown.
report=$(mktemp)
if ! (cd tools/puppet && npm test) > "$report" 2>&1; then
  tail -n 60 "$report"
  rm -f "$report"
  exit 1
fi
grep -E "(pass|fail) [0-9]+$" "$report"
rm -f "$report"
echo
ls fabric/build/libs neoforge/build/libs mc1.20.1/fabric/build/libs mc1.20.1/forge/build/libs | grep -E "\.jar$" | grep -v -E "dev|sources"
