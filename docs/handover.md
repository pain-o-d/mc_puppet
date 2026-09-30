# Handover

What a session opened in this project needs to know that the code and the git
log do not say. Written 2026-09-22 from a session held in `../hivemind`, which
did the work below because it needed it; keep it current in the same commit
as the change it describes.

## Where things stand

`develop` is 0.1.2 plus one feature, merged and **unreleased**: clients that
join a server, and as many of them as a test needs (`feat: clients that join
a server`, `dc7f934`). The `## Unreleased` section of `CHANGELOG.md` says what
it is; the README's *Several clients on one server* says how it is used;
`docs/ROADMAP.md` ticks *More than one game* as run live at last.

The next release is **0.1.3**, by `docs/RELEASING.md`, and it is the first
one to carry the sources jars on Modrinth (that fix is in the same
`Unreleased` section). Nothing in it changes the protocol: `join_server` is
an operation added, `info.username` and a player's `name` in `entities` are
fields added, so `Protocol.VERSION` stays 1.

## What was seen, and what was not

Seen on 2026-09-22, in development environments:

- All four targets join a server by `join_server` and by `launch client
  --name … --server …`: 1.21.1 Fabric and NeoForge against a real Fabric
  server on another machine through `ssh -L` (three Fabric clients and a
  NeoForge one on it at once), 1.20.1 Fabric against this project's Fabric
  dev server, 1.20.1 Forge against its Forge dev server.
- `scenarios/two-clients-one-server.json` passes with two named clients; the
  server steps skip when the server's bridge is out of reach, as designed.
- A refused address (`192.168.1.10`, `play.example.org`), a port nobody
  listens on (`wait {for: world}` ends at once with "Connection refused"),
  and `join_server` with a world loaded, all refuse in words.
- Two of another project's dev clients (`../hivemind`, MC Puppet's jar in
  its `fabric/run/mods`) launched by `--project .` from there: `launch`
  works on a project that is not this one.

Not seen:

- `launch --name` on Linux or macOS. The Windows path runs the wrapper's
  jar by Java directly (see `windowsJava` in `launch.js` for why); the other
  path runs `gradlew` as before and is unchanged in that respect, but the
  init script and the run directory are new on every platform.
- A named client whose project has no `<loader>/run/` at all (`FROM_RUN`
  copies nothing, `options.txt` is written; it should simply be a first
  run, and was not tried).
- `tools/prod-check.js` and `tools/attack-check.js` since the change. The
  bridge, the token, consent and the audit are untouched, but the release
  checklist asks for `prod-check` regardless, and `attack-check` costs ten
  minutes.
Seen since: `../get_rich/tools/run-client-scenarios.sh`, the release
checklist's real suite, on 2026-09-27 in a fresh world on Fabric 1.21.1 with
the jar built from develop that day (`join_server`, `watch`, the baby flag and
`burning` in) — 11/11 passed, launch and stop as before (JUnit at
`get_rich/build/client-scenarios.xml`).

## `watch`, 2026-09-26 (from `../hivemind`, unreleased)

`watch` on both sides (`core/Watch`, `client/FrameClock`), an operation added,
so `Protocol.VERSION` stays 1. Built on all four; run live on Fabric 1.21.1
only, from `../hivemind` (`tools/scenarios/look.json` there). The client's
"drawn only" asks `EntityRenderDispatcher.shouldRender` with a frustum that
sees everything; the frame clock is one `nanoTime` a frame from the existing
render hook. Not seen: NeoForge and 1.20.1 live, and the server watch under a
real load of thousands.

2026-09-27: the twitch numbers (`reversals`, `wobbles`, `pace_cv`; on the
client `frame_reversals`, `frame_wobbles` from the frame hook, with the tick
delta through `ClientCompat.tickDelta` - the one line that differs between
versions), and `trace`. Run live on Fabric 1.21.1 from `../hivemind`, where
they found ghosts left in the world twitching on the spot. Built on all four.

## `mouse_drag` and a drag behind other windows, 2026-09-30 (unreleased)

Asked for by `../tycoon` (backlog 143) for `../stockyard-create` (its backlog
17): Simulated's physics assembler lever, throttle lever and steering wheel
are worked by dragging the mouse with use held, and `hold {keys:[use]}`
presses the binding and moves nothing. Branch `feature/mouse-drag`, merged.

- **`mouse_drag`** (`client/Body`): presses a mouse button through
  `Mouse.onMouseButton` (default: the button `use` is on), moves the cursor
  through `Mouse.onCursorPos` by yaw/pitch degrees (divided by
  `Gesture.degreesPerPixel` at the player's sensitivity, inverted with
  invert-Y) or by dx/dy pixels, over `ticks`, and lets go after `after`.
  Answers `turned`, `window_focused` (the real field), `moved`.
- **`client/VirtualFocus`** and two optional injections: while a gesture
  runs, `MinecraftClient.isWindowFocused` answers true and, with no screen,
  `Mouse.isCursorLocked` too; `Mouse.lockCursor` is cancelled unless the
  window really has focus, so the real cursor is never grabbed. Ended on
  success, failure, `stop` and `release_keys`.
- **`Input.gesture`**: the old `drag` and `mouse_drag` both. A step waits
  for a frame to have taken the last move (`cursorDeltaX/Y` back to 0), and
  the release waits for the last. `drag` adds a one-pixel move after the
  press and takes `steps`.
- **`window {focused}`** calls `onWindowFocusChanged`, what GLFW's callback
  calls; `window`/`info` report `focused`.
- `core/Gesture` (+ `GestureTest`, 3 tests): the pixel arithmetic and the
  legs. No mixin differs between versions; nothing new in `compat/`.

Seen: `scenarios/mouse-drag.json` **39/39 on all four** (1.21.1 Fabric and
NeoForge, 1.20.1 Fabric and Forge), with the game told its window lost
focus: a left drag of 16 cobblestone over four slots left 4 in each, a right
drag of 4 over three left 1 in each and 1 on the cursor, and a bow drawn by
`mouse_drag {button: use, yaw: 90, pitch: -30}` turned the head 90/-30 and
loosed an arrow. `eyes-and-hands` passed on all four afterwards (on NeoForge
at the second run: the first failed at step 25, the pig's `entity`, which
nothing here touches); `trade-with-a-villager` on Fabric 1.21.1.

Not seen: the old `drag` failing without focus on 1.21.1 (read from the
bytecode: `Mouse.tick` hands a screen its drag only when focused); Simulated
itself (no other repository was touched).

**For Simulated's physics assembler** (read from
`PhysicsAssemblerGUIHandler` 1.3.2, not run): the lever's value goes
`value -= turn / 80 × (0.55 − |0.5 − value|)` for each frame's pitch turn,
so from 0 to 1 is about 384 turn units, **about 58° of pitch at any
sensitivity**; a release with the value over 0.985 on two readings sends
`AssemblePacket`. The handler also checks the player within 2 blocks of the
assembler's centre each tick. So, standing next to it:

```json
{"op":"look","args":{"at":{"x":X.5,"y":Y.5,"z":Z.5}}},
{"op":"mouse_drag","args":{"button":"use","pitch":-90,"ticks":20,"after":4},
 "expect":[{"path":"turned.pitch","equals":0}]}
```

Mouse up (negative pitch) assembles; on a sub-level the lever starts at 1
and `pitch: 90` (down, below 0.015) disassembles. `turned.pitch` 0 says the
handler took the movement; the throttle lever and wheel should read the same
path (`MouseHandlerMixin.turnPlayer`), their scale unread.

## Traps met

- **Two ticks can run with no frame between them** (the game catches up
  ticks before it renders), and a frame is what hands mouse movement on:
  two moves became one and a right drag skipped its first slot. A gesture's
  step waits for the last to be taken.
- **The game believes its window has focus when it starts**
  (`onWindowFocusChanged(true)` after creating it) and learns otherwise only
  from a focus event: a window made behind a full-screen program says
  focused. `window {focused: false}` is how a scenario tries the other case.

- **Gradle's output vanished behind a detached `cmd`** on Windows: the log
  file `launch` points at had been empty since the command existed, and
  nobody noticed because no launch had failed. The wrapper's jar is now run
  by Java directly, with `JAVA_HOME` or the `java.home` the PATH's Java
  reports (the one on the PATH is usually Oracle's stub). Do not put `cmd`
  back in front of it.
- **Loom's `programArgs` is read-only** (a `ListProperty` under a list
  view): `run.programArguments.set(...)` in the init script, not `remove`.
- **A Forge 1.20.1 client cannot log in to a server running Fabric API** —
  it never answers a login query and times out after thirty seconds; a
  NeoForge 1.21.1 client can. And a Forge client resolves `localhost` to
  `::1`, where a server bound to `127.0.0.1` is not: say `127.0.0.1`. Both
  are in the README's *Limits*.
- **Two clients at once make "the newest client" the wrong question.**
  `launch` and `stop` address a game by its directory (`bridgeOf`, `ask`),
  never through `Puppet.call("client", …)`.
- **A shell heredoc eats backslashes** in a script that edits files. Write
  the script to a file; it cost an hour and one regex.

## The owner's

- Release 0.1.3: `RELEASING.md` from step 1. The version bump is in three
  places; the changelog heading; npm before Modrinth.
- Whether `launch --name` should be in the MCP server's tools. It is not:
  the five tools are deliberately few, and an agent can call the CLI.
- The later-perhaps item in the roadmap about a server telling a client it
  has MC Puppet on: a forwarded port made it unnecessary for the case that
  came up, and the roadmap says so; it stays a note.
