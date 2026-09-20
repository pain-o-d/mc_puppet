# Two Minecraft versions from one set of sources

Decided 2026-09-20 by the owner: **1.21.1** (Fabric, NeoForge) and **1.20.1**
(Fabric, Forge), the two versions modding gathers around. MC Puppet goes
first, as the pilot: it is small, it does not touch the game's networking, and
without it there is nothing to test another mod on 1.20.1 with.

Tick a box when the thing is done **and seen to work in a running game**.

## The shape

The 1.21.1 build stays exactly where it is, at the root, and is not touched by
any of this. A second, complete Gradle build lives in `mc1.20.1/` and compiles
**the same sources**, reaching them by path:

```
mc_puppet/
  common/ fabric/ neoforge/        the 1.21.1 build, as it always was
  common/src/main/java/…           the sources, shared by both builds
  common/src/main/java/…/compat/   what 1.21.1 does its own way    ← not seen by 1.20.1
  mc1.20.1/
    settings.gradle, gradle.properties, gradlew → its own build, Java 17
    common/src/main/java/…/compat/ what 1.20.1 does its own way    ← same class names
    fabric/ forge/                 entry points
```

Two builds rather than one with a preprocessor, for now, because:

- nothing can break the build that works: the root does not know `mc1.20.1/`
  exists;
- each is an ordinary Architectury project, which is what Loom, the
  Architectury plugin and an IDE are made for. Both versions in *one* Gradle
  build is the part with sharp edges (one root `architectury.minecraft`, one
  Java toolchain, one Loom);
- MC Puppet's differences between versions are few and sit in a few places.
  A preprocessor earns its keep when they are many and scattered, which is
  get_rich, later. Stonecutter can be brought in then, over the same seams.

**The rule: a difference between versions lives in `compat/`, behind a class
both versions have, and nowhere else.** Shared code never asks which version
it is on. If the same concern needs a third fork somewhere, it wanted a seam.

Mixins are the exception that proves it: a mixin names a method of the game's,
and where that method differs the mixin is the difference. Those go in
`compat/` too, and each build lists its own in its own mixins json.

## Steps

- [ ] Artifacts for 1.20.1 on this machine: Architectury API 9.2.14 mirrored
      through `tools/fetch-architectury.sh` (the big-file problem with
      `maven.architectury.dev`), the rest from repositories that work.
- [ ] `mc1.20.1/` builds an empty mod on Fabric and Forge: the environment
      proven before our code is in it.
- [ ] The shared sources compile against 1.20.1. Every error is either a seam
      to cut or a mixin to fork; list them here as they are found.
- [ ] The core's unit tests pass in both builds.
- [ ] Fabric 1.20.1 runs; `scenarios/trade-with-a-villager.json` and
      `scenarios/eyes-and-hands.json` pass **unchanged**.
- [ ] Forge 1.20.1 runs; the same two pass.
- [ ] `tools/puppet` knows nothing of versions, and `status` says which one a
      game is.
- [ ] One command builds all four jars; names carry the version:
      `mc_puppet-forge-0.1.0+mc1.20.1.jar`.
- [ ] README, CLAUDE.md and the release notes say how.

## Seams found

*(filled in as the compiler finds them)*
