#!/usr/bin/env bash
# Stops dev client/server JVMs belonging to this project.
#
# Why this exists: killing the Gradle wrapper leaves the Minecraft JVM it
# spawned running. That orphan keeps port 25565 and the world's session.lock,
# so the next launch fails with "Address already in use" or a locked level —
# errors that look like a code problem and are not. Orphans also accumulate
# silently across runs.
#
# Matches on the project path in the command line, so it never touches an
# unrelated java process.
set -uo pipefail

PROJECT="${1:-mc_puppet}"

count=$(powershell -NoProfile -Command "
  (Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" |
   Where-Object { \$_.CommandLine -like '*${PROJECT}*' }).Count" 2>/dev/null | tr -d '[:space:]')

if [ "${count:-0}" = "0" ]; then
    echo "No ${PROJECT} dev JVMs running."
else
    echo "Stopping ${count} ${PROJECT} dev JVM(s)..."
    powershell -NoProfile -Command "
      Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" |
      Where-Object { \$_.CommandLine -like '*${PROJECT}*' } |
      ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" 2>/dev/null
    sleep 3
fi

# A killed server never released its lock; a stale one blocks the next launch.
for lock in */run/*/session.lock; do
    [ -e "$lock" ] && rm -f "$lock" && echo "Cleared stale $lock"
done

exit 0
