#!/bin/sh
# Builds every jar: 1.21.1 for Fabric and NeoForge at the root, 1.20.1 for Fabric and
# Forge in mc1.20.1/, each build running the unit tests against its own game.
# See docs/MULTIVERSION.md. Pass --offline to skip the network.
set -e
cd "$(dirname "$0")/.."
echo "== Minecraft 1.21.1 (Fabric, NeoForge)"
./gradlew build "$@"
echo "== Minecraft 1.20.1 (Fabric, Forge)"
(cd mc1.20.1 && ./gradlew build "$@")
echo "== tools"
node --test tools/puppet/scenario.test.js | grep -E "^. (pass|fail)"
echo
ls fabric/build/libs neoforge/build/libs mc1.20.1/fabric/build/libs mc1.20.1/forge/build/libs | grep -E "\.jar$" | grep -v -E "dev|sources"
