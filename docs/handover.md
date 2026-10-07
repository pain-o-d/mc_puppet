# Handover

## Now (2026-10-07)

The short state; dated sections below are history. Rewrite this block, do
not append to it, in the same commit as the change it describes.

- **Works:** input, connections and build-lease handoff repaired on four targets (committed 2026-10-03/04).
- **Next:** `docs/backlog.md` task 1, lifecycle events (server/client/launcher, filterable, file sink, `puppet wait`). Written, not started; slices 1A-1E there.
- **Open:** release 0.1.3 is the owner's; develop is ahead of `v0.1.2` by more than one feature.


What a session opened in this project needs to know that the code and the git
log do not say. Written 2026-09-22 from a session held in `../hivemind`, which
did the work below because it needed it; keep it current in the same commit
as the change it describes.

## Where things stand

### Input repair, 2026-10-03 (four-target development validation complete)

Pack task 166 exposed two independent client-input defects: an unfocused held
attack did not satisfy vanilla's cursor-lock gate, and client slot 0/axe could
coexist with authoritative server slot 2/empty. A further source review found
that pending hold callbacks could re-press after `stop` or `release_keys`, and
that `setPressed(false)` alone retained vanilla's queued press count.

The current repair gives temporal input one context-checked lease and scoped
virtual focus, with game-thread cleanup of its held bindings and queued presses.
Vanilla's private `syncSelectedSlot` has the same descriptor on both supported
versions; an atomic alternate/desired round trip through that method repairs
the equal-cache case. No custom selection packet, server inventory mutation or
new protocol shape is introduced; protocol 1 and the null hotbar result remain.

Revision 1 built all four targets and passed 83 core tests on each version,
including 12 input-session regressions, plus 33 launcher/scenario-language tests.
Its first fresh Fabric 1.21.1 run passed eyes-and-hands 47/47, mouse-drag 39/39,
focused-input 59/59 and trading 24/24. Authoritative slots 2/0/0/8/0/2 also
passed, but leaving the world took 53 seconds against a 30-second transport
deadline. The original attempt remains FAIL; save and quit completed normally.

A separate fresh attempt retained that failure and allowed 120 seconds for
save transport. It failed hand mining: `break_block` reported 7 ticks against
the unchanged 10--25 criterion. The wrapper's 20-second exit observation also
expired during a 67-second normal save; supplemental logs, absent owned PID
and closed ports establish eventual normal closure. Later scenarios, reopen
and concurrent cancellation were not executed in that attempt.

Source review found two possible mining drivers: the operation called
`handleBlockBreaking(true)` on each waiter probe while vanilla could also drive
an already pressed attack binding under scoped focus. The precise failed-run
binding state was not recorded, so that historical trigger is unproven.
Revision 2 replaces manual progress with one owned ordinary attack-binding
path and observes native player ticks. All four revision-2 builds/remap checks
passed; both versions passed 83 core tests each, including 12 input-session
tests. The launcher/scenario-language checks passed 33/33.

Fresh development clients on Fabric/NeoForge 1.21.1 and Fabric/Forge 1.20.1
each passed eyes-and-hands 47/47, mouse-drag 39/39, focused-input 59/59 and
trading 24/24, followed by 83 native input checks: 676 scenario steps and 332
input checks in total. The unchanged hand-mining 10--25 native-tick predicate
passed everywhere; exact tick values were not serialized in those reports.
The matrix verified authoritative hotbar selections (different, repeated,
empty, boundary and reopen), inventory conservation, stop/release cancellation
of pending holds and gestures, six owner-preserving overlap refusals, and
screen-change cancellation followed by usable input. A natural reopen slot
mismatch was not reproduced; the forced alternate/desired path was exercised.
All four clients saved and quit normally, their owned PIDs disappeared, their
bridge ports closed and the canonical Loom lock was absent. The short-lived
launch CLI released its build lease while each same game PID remained alive.

The independent aggregate audit is retained in the local ignored pack evidence
directory as `puppet166-four-v3.independent-audit.json`, SHA-256
`68b2893e7825a3d0f1436c65222cc81f9db8e1fcdb0a32a426d0cab0899b1730`.
It binds tested source `1dd715291ec0ec4033cb3dfef99f8e8fbcce8aef28b2b6e0d12a832e449c857c`,
all four development runs and archived release-JAR build receipts. This proves
the development clients; the separate production-consumer checks are recorded
below. No independent game-process exit-code claim or socket cancellation is
implied. The original failed receipts and criteria remain unchanged.

Internal tracked-future cancellation revokes
the lease; the existing Ops/socket wrapper does not propagate disconnect as
cancellation. Call `stop` or `release_keys` explicitly.

The subsequent consumer pack reconnect reproduced client0/axe versus
server2/empty. One ordinary same-client-slot `hotbar(0)` repaired the
authoritative axe and `hotbar(2)` left both hands empty. Independent comparison
of full inventory arrays proves conservation, correcting a vacuous field-based
assertion in the preserved root helper receipt. The consumer short launch with
the additional init script returned0, recorded ready and released Loom while
the same client stayed alive with the full expected pack composition; an
erroneous wrapper mod-ID assertion and separate correct readback are retained.
The production NeoForge server loaded the verified revision2 JAR and normally
exited0. Both consumer game processes are now closed.

A later cold parallel Node scan found an independent connection defect in
`tools/puppet/lib.js`: pending connects were not shared, the side cache replaced
them while socket was null, and close could not destroy in-flight sockets. Orphaned
authenticated sockets exhausted Bridge's unchanged eight-connection limit.
Only the exact read-only orphan Node helper was terminated; game JVMs then
saved/quit normally. A bounded connection/close/reconnect repair now passes ten
real loopback TCP regressions, with48/48 launcher/scenario/connection checks.
It shares pending connects for the exact directory/port/token/PID/protocol,
destroys pending sockets on close, rejects work closed before request commit,
and isolates retired socket events from fresh requests. Real cold16/32-call,
connect refusal/reconnect, pending close, delayed retired close and token/port
restart cases pass.

The subsequent fresh production NeoForge consumer completed the actual cold
`Promise.all` batch of 16 server `block` calls. In the ignored pack
`runs/recipe-reload-161/operations.jsonl`, replies 59911--59926 complete at
2026-10-03 20:19:38.216--20:19:38.220 UTC with all declared coordinates/states.
That 4 ms is a response-completion span, not a socket count or performance
guarantee. The final Node source and ten real TCP regressions separately prove
pending-connection ownership; the old cold failure remains preserved. Bridge's
eight-connection limit, loopback/token/consent, protocol 1 and game-future
cancellation contract are unchanged.

The same consumer then performed two genuine non-sneaking survival USE actions
on separate native Simulated rope endpoints. Client/server selected-item and
reach/raycast admission passed; the first USE added the real winch-selection
component to one coupling, and the second consumed exactly that one item and
created the matching native endpoint strand. The empty 140-cell mobile body and
separate 2-cell holder retained their exact identities/cells/masses, with the
original timber fingerprint unchanged. This is bounded native interaction
evidence. No hoist, wheel travel or original-stock transfer passed: the later
hoist cycle failed its actor-stance preflight before declaration/motion, with
zero cycle samples. Its emergency stop also failed native ray admission; the
disclosed operator fallback returned `Could not set the block` on the already
powered stop lever. No cause for the actor-stance mismatch was established.

The ignored hoist-preset runner's final `JSON.stringify` rejected a parsed
native Network Long stored as BigInt. This was that runner's terminal receipt
writer, not a Puppet parser/transport failure. The failed receipt is retained;
a separate read-only finalizer preserves the exact raw native value and the
observed source/gear -16 RPM, winch 0 state without replaying setup or motion.

Normal final closure is recorded separately in
`fork166-mobile-coupled-closure.json`: server PID31560 exited 0, client PID23872 saved
and quit with final Gradle `BUILD SUCCESSFUL`, both owned PIDs were absent,
ports 25687/25697/25698/25699 refused connections and Loom was absent. The
client process exit-code field is null; do not invent an independent exit code.
The initial closer checked the Gradle log before its finishing writer and
remains FAIL; the supplemental read-only closure verifies the completed logs.

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


## Build-lock launcher handoff, 2026-10-03 (unreleased)

The standalone launcher optionally cooperates with an external build mutex.
Without both LOOM_LOCK_PARTICIPANTS and LOOM_LOCK_TOKEN, launching stays
self-contained. With them, every launched game gets a distinct wrapper JSON
lease with hostname, wrapper PID, project, game directory, name and task. The
final protocol gives Node sole ownership of `<id>.json` and declares
`gradleRecord: '<id>.json.gradle.json'`. Gradle alone writes that sidecar with
token, hostname, base lease filename, buildPid and building/ready/exited phase.
It announces its daemon PID before configuration/remapping and ready immediately
before the selected run task, after dependencies. Build completion records the
daemon's final phase independently of wrapper exit.

The external supervisor aggregates the two records and validates their
identity. Wrapper exit cannot overwrite daemon PID/phase. A missing, malformed
or foreign sidecar remains unknown even if the wrapper exited; only proven
spawn failure with `spawnFailed=true` and `pid=null` discharges a missing
daemon record. Ready/exited releases build ownership without waiting for the
game/wrapper to end. After ready, an absent lease during normal game shutdown
is an allowed no-op. Generic non-Gradle leases retain their existing handling.
These changes preserve standalone launch, game operations, token/consent and
protocol version.

The launcher also accepts repeatable explicit `--init-script <file.gradle>`
arguments. Paths resolve from the invocation directory and must be regular,
readable Gradle files before any run-directory preparation or spawn. The owned
launcher hook stays first, additional scripts keep caller order, and both
Windows Java and POSIX wrapper paths receive the same argument array. Five
additional launcher checks pass, including real CLI parsing without launching
Gradle: the earlier launcher/scenario suite passed 38/38. The later consumer
launch with its pack dependency init script recorded ready and released Loom
while the same client remained alive, as documented above. The four input runs
predate this CLI-only extension and the final lease-race repair.

Earlier evidence: Node lock/handoff tests use real short-lived detached Node
children to demonstrate retention after launcher failure and release at the
build-ready boundary while the runtime child survives. Sequential launcher
metadata and daemon-exit tests plus the scenario-language suite passed 42/42
across the shared lock, launcher and existing scenario tests. A harmless offline Gradle 9.5.1
fixture also passed with Java 21 and a temporary Gradle user home: its dependency
task completed before dummy runServer observed the ready phase and daemon PID;
the successful build and an earlier failed invocation both released the lock.
This exercised both init hooks without compiling a mod or launching Minecraft.
Subsequently, the four fresh input-validation clients above each recorded the
actual detached build lease reaching ready, successful CLI return and lock
release while the same game PID stayed alive. All four were then saved and
quit under the runtime owner's supervision. A missing/uninspectable lease fails closed in
the external supervisor, so recovery may need deliberate owner inspection.

Independent final review then found a genuine lost-update race in the earlier
shared JSON: Node could read a wrapper snapshot, Gradle could write its daemon
PID/phase, and Node's later wrapper-exit write could erase those new fields.
The successful game launches and sequential checks above did not cover that
interleaving. They remain historical proof of their stated paths.

The separate-record repair now passes 37/37 shared tests (lock 12, RCON 12,
stop 13) and 50/50 MC Node tests (scenario 30, launch 10, connection 10). The new
deterministic launcher tests interleave wrapper updates with daemon building
and ready records; supervisor tests retain missing/malformed/foreign records,
live or unknown daemons and validate the explicit spawn-failure exception.
The fresh harmless fixture then passed both actual Gradle 9.5.1 init hooks with
Java 21 and local-cache offline dependencies. The direct hook records building
during prerequisite/run and exited at buildFinished. The MC hook records
building during its prerequisite and ready before dummy runServer; the canonical
lock releases while that same dummy task continues for five seconds. The task
then verifies the wrapper lease is absent and finishes `BUILD SUCCESSFUL`,
exercising normal completion after ready handoff. Both supervisor invocations
exit 0 and Loom is absent. Exact result is retained as
`fork166-lock-writer-integration.json`, status
`ACTUAL_GRADLE_9_5_1_SEPARATE_WRITER_HOOKS_AND_READY_HANDOFF_PASS`, at
2026-10-03 20:58:04.235 UTC, with fixture receipts/logs under
`lock-writer-fixture/`. No Minecraft, Loom remapping or new four-target Java
input rerun is implied by this race repair.
