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

- [x] Artifacts for 1.20.1 on this machine: Architectury API 9.2.14 mirrored
      through `tools/fetch-architectury.sh`, the rest from repositories that
      work.
- [x] `mc1.20.1/` builds an empty mod on Fabric and Forge: the owner's
      template, with the plugins pinned, the mirror consulted, a Java 17
      toolchain, and the new package.
- [x] The shared sources compile against 1.20.1. Twenty-five errors in eight
      files, every one of them now behind `compat/`; see below.
- [x] The core's unit tests pass in both builds.
- [x] **Fabric 1.20.1** runs; both scenarios pass, 24 of 24 and 47 of 47.
- [x] **Forge 1.20.1** runs; the same two pass.
- [x] 1.21.1 seen again afterwards on Fabric and NeoForge, since the sources it
      is built from changed; and a real mod's scenarios against the new jar.
- [x] `tools/puppet` knows nothing of versions, and `info` says which one a
      game is, and which loader.
- [x] `tools/build-all.sh` builds all four jars, and the 1.20.1 ones carry the
      version: `mc_puppet-forge-0.1.0+mc1.20.1.jar`.
- [ ] The 1.21.1 jars should carry theirs too (`+mc1.21.1`), before anything
      is published.
- [ ] A dedicated server on 1.20.1 has not been run; on 1.21.1 it has.

## Seams found

All of it, for a mod that reads screens and sends input. The game's GUI,
input and entities are nearly the same in the two versions; what moved is
around them.

| In `compat/` | 1.20.1 | 1.21.1 |
|---|---|---|
| `Compat.dataOf` | a stack carries NBT | components, from 1.20.5 |
| `Compat.firstBuy` / `secondBuy` | `getAdjustedFirstBuyItem`, `getSecondBuyItem` | `getDisplayed…` |
| `Compat.msPerTick` | `getTickTime`, a float of milliseconds | `getAverageNanosPerTick` |
| `Compat.reportingTo` | brigadier's `ResultConsumer` | `ReturnValueConsumer` |
| `Compat.nbtOf` | no registries needed | registries needed |
| `Compat.idOf` (an effect) | the effect, looked up | a registry entry with a key |
| `Compat.OTHER_LOADER` | forge | neoforge |
| `ClientCompat.updateCrosshair` | `updateTargetedEntity` | `updateCrosshairTarget` |
| `ClientCompat.openWorld` / `createWorld` | a parent screen first; no parent | a cancel callback; a parent |
| `ClientCompat.onSound` | two arguments | three |
| `ClientCompat.sidebarOf` / `linesOf` | slot 1, `ScoreboardPlayerScore` | an enum, `ScoreboardEntry` |

**Mixins turned out not to need forking**, which was the surprise. The two
that named something version-specific were made not to: `DrawContextMixin`
takes its tooltip's `Optional<?>` unnamed, since `TooltipData` moved packages
and erases to the same descriptor; `GameRendererMixin` asks for none of
`render`'s arguments, which are a tick counter in one version and a float and
a long in the other. The sprite hooks name a method 1.20.1 does not have; they
are optional, and the build says "Cannot remap drawGuiTexture" and goes on.

## What only running it found

- **Under Forge the bridge listened on `::1`.** `InetAddress.getLoopbackAddress()`
  is whichever loopback the JVM prefers, and Forge starts the game preferring
  IPv6; the endpoint file and the log both said 127.0.0.1, and nobody could
  connect. The bridge binds 127.0.0.1 in so many bytes now, and a test dials
  it that way. Not a 1.20.1 matter at all: any JVM told to prefer IPv6 had it.
- **A scenario speaks to the game as well as to the mod**, in commands, and
  their language changed: an item's data is NBT before 1.20.5 and components
  since. A summoned villager had no offers and a sword was never given. A
  value in a scenario may now depend on the version —
  `{"mc<1.20.5": "…", "else": "…"}` — and the two scenarios have one command
  each written both ways. Everything else in them is unchanged.
