# Backlog
 **Done** 2026-10-07: `ServerEvents` (Architectury lifecycle + player events), `CrashEvents` + `CrashReportMixin` (`require = 0`), both builds green.
Numbered tasks, each small enough for one fresh session. A task keeps its
number for life; other documents cite it. State lives in `handover.md`
(`## Now`); this file is the plan.
 **Done** 2026-10-07: `ClientEvents`, `ClientCompat` twins, `DisconnectedScreenAccessor`, both builds green.
## Task 1 — Lifecycle events: a pushed, filterable record of what the game and server did

Status: **open**, written 2026-10-07. Owner: nobody. Ordering: 1 first;
2, 3 and 4 are independent after it (disjoint write areas); 5 last.

### Why

A test run today learns that a server died or a client could not join by
waiting for a timeout and then reading a log. Two real cases from this
month: a dedicated server that crashed on start (`AccessDeniedException`
on a native library, then an NPE in `stopServer`), and a client left on
"connection lost" that nothing reported. The caller cannot sleep on the
game; it should be woken by an event. `EventLog` (chat and toasts, read
with a `since` sequence) already exists in the client; this extends the
idea to lifecycle, to the server side, and to a file a process can watch.

### What to build

**One event shape**, one JSON object per line:

```json
{"seq":12,"ts":"2026-10-07T17:08:12.431Z","side":"server","name":"server.crash","level":"error","data":{"report":"crash-reports/crash-2026-10-07_17.08.12-server.txt","cause":"AccessDeniedException: ...sable_rapier_x86_64_windows.dll"}}
```

`side` is `client`, `server` or `launcher` (task 4). `level` is `info`,
`warn` or `error`. `data` is small: ids, reasons, paths. Never a log.

**Event names (first set):**

| Side | Names |
|---|---|
| server | `server.starting`, `server.ready`, `server.stopping`, `server.stopped`, `server.crash` (report path + first cause line), `mod.load_failed` (mod id + message, where the game gets that far), `player.joined`, `player.left` |
| client | `client.ready` (main menu or world loaded), `client.connecting` (address), `client.connected`, `client.connect_failed` (reason, as the disconnect screen would say it: incompatible mods, timeout, refused, `connection lost`), `client.disconnected` (reason), `client.crash` |
| launcher | `process.started`, `process.exited` (exit code, last crash report if newer than the start) |

`client.connect_failed` is a disconnect that happens before the play
state is reached; `client.disconnected` is one after it. A failed connect
**must** produce its event: that is the case the owner asked for.

**Configuration** in `config/mc_puppet.json` (same file, same loader as
the bridge settings; no second consent key, see CLAUDE.md):

```json
"events": { "enabled": ["server.crash","client.connect_failed","client.disconnected"],
            "sinks": ["file"], "min_level": "info" }
```

- Default when the bridge is on: every name with level `warn` or `error`
  plus `server.ready`, `client.ready`, `client.connected`,
  `client.connect_failed`, `client.disconnected`. `enabled: ["*"]` means all.
- Sinks: `file` (default: `<run dir>/mc_puppet/events.jsonl`, appended,
  truncated at start of a run, so a watcher reads only this run) and
  `stdout`. **No webhook and no network sink**: loopback-only is the
  product's licence to exist; a caller that wants a push reads the file or
  the bridge.
- **Events exist only where the bridge is on**; nothing is written in a
  shipped config. This is not a new way past consent.
- The bridge also serves them: an `events` operation (`since`, `names`,
  `limit`) beside the existing event log, so a connected tool can read the
  same sequence. Adding an operation does not raise `Protocol.VERSION`.

**CLI:** `puppet events [--dir D] [--since N] [--follow] [--name a,b]`
prints the file (follows with `--follow`; one line per event, built for
`tail -f` and Claude Code's `Monitor`). `puppet wait --event NAME[,NAME]
[--fail-on NAME[,NAME]] --timeout S` exits 0 when a wanted event arrives,
1 on a `--fail-on` event (default: any `*.crash`, `client.connect_failed`,
`process.exited` with a non-zero code), 2 on timeout, and prints that
event's line either way. The scenario language gets a step with the same
meaning (`wait_event`).

### Slices (disjoint write areas)

| Slice | Write area | Notes |
|---|---|---|
| 1A event bus | `core/Events.java` (new), `PuppetConfig`, `Bridge`/`Ops` (`events` op), unit tests in `CoreTest` | plain Java, no game; the file sink and the config parse are tested here. Build 1A first and fix the line format. |
| 1B server hooks | `server/` + a mixin or loader event where the game writes a crash report; `compat/` where versions differ | `server.crash` must still be written when `stopServer` itself throws. Hooks are `require = 0` mixins like the rest. |
| 1C client hooks | `client/` + `compat/ClientCompat` (both versions, twin files) | connect start, play state reached, disconnect with reason (the disconnect screen's text), client crash. A dedicated server must not load these classes. |
| 1D CLI watcher (**done 2026-10-07**: events.js, supervise.js, `puppet wait`/`events`, scenario `wait_event`, launcher records process.started/exited; npm test 99 pass) | `tools/puppet/` only (`puppet.js`, `lib.js`, `launch.js`, `scenario.js`) | the **launcher** side: `launch` spawns the JVM, so it sees what no in-game hook can (a native crash, a kill, a failure before the mod loads). It writes `process.started` / `process.exited` to the same file, and on exit copies the newest crash report path newer than the start. Needs only the line format from 1A. |
| 1E docs and proof | README, `docs/handover.md`, `docs/MULTIVERSION.md`, CHANGELOG, a scenario | last. |

### Acceptance

- Unit tests: config defaults and `enabled`/`min_level` filtering; the
  file sink truncates per run; sequence is monotonic; a bad `events` block
  keeps the defaults and logs one warning.
- On all four targets (1.21.1 Fabric and NeoForge, 1.20.1 Fabric and
  Forge), by running, not by reading:
  1. a dedicated server started and stopped normally gives `server.starting`,
     `server.ready`, `server.stopping`, `server.stopped`, in order;
  2. a client joining it gives `client.connecting`, `client.connected`,
     `player.joined`; leaving gives `client.disconnected` and `player.left`;
  3. a client pointed at a stopped port, and a client refused for a mod
     mismatch, each give `client.connect_failed` with a reason;
  4. a server killed while a client is on it gives `client.disconnected`
     (reason `connection lost` or the screen's text) on the client and, from
     the launcher, `process.exited` with a non-zero code;
  5. a server made to crash on start (a jar in `mods/` that throws) gives
     `process.exited` from the launcher with the crash report path, even
     though no in-game hook ran;
  6. `puppet wait --event client.connected --timeout 60` returns 0 in case 2
     and 1 in case 3, without sleeping to the timeout.
- Security read twice: nothing here widens the bridge, no new bind, no
  network sink, no read of files outside the run directory; the reason text
  is cut and carries no token or password.
- `tools/build-all.sh` green; both scenarios still pass.

### Notes for whoever implements it

- The existing `EventLog` is a ring buffer of chat and toasts with a
  sequence. Reuse its idea and its `since` semantics; do not fold chat into
  lifecycle events (different volume, different consumers). Keep the answer
  small, as every operation is (CLAUDE.md, "paid for by the token").
- What the **launcher** adds is the point. An in-game hook cannot report a
  crash that kills the JVM before or while it runs; without 1D the original
  case (a failure on start) is not caught.
- A mod-load failure usually happens before `McPuppet.init`; report it from
  the launcher by reading the crash report, not from a hook.
- Version seams (disconnect screen class, connection state names) go in
  `compat/`, in both files; shared code never asks which version it is on.

## Task 2 — Node tests in the build and CI, and tests for `mcp.js` and `junit.js`

Status: **done** 2026-10-07 (not committed when written). Write area:
`tools/puppet/`, `tools/build-all.sh`.

What was wrong: `tools/build-all.sh` ran only `scenario.test.js`, while
`tools/puppet/package.json` named scenario, launch and connection tests; and
`mcp.js` and `junit.js` had no test. CI (`.github/workflows/build.yml`) calls
`tools/build-all.sh`, so fixing the script fixes CI; the workflow itself
needed no change. The Java `:common:test` already runs in `build-all.sh` (it
runs the unit tests of each build), so it was not added.

- A. `build-all.sh` runs the whole `npm test` in `tools/puppet`, output to a
  file, only the summary printed. Done.
- B. `tools/puppet/mcp.test.js` spawns `mcp.js` over stdio against a fake
  bridge: initialize, `tools/list`, a refusal passing through verbatim, a
  malformed request, a screenshot returned as an image block. Done.
- C. `docs/ROADMAP.md` no longer says the GitHub repository does not exist and
  marks publishing done, as far as the repository's own files prove. Done.
- D. `tools/puppet/junit.test.js` covers escaping and failure counts. Done.

Result: `cd tools/puppet && npm test` passes, 75 tests, 0 failures.

## Task 3 — Core unit tests and a drift check between the two builds

Status: **done** 2026-10-07 (uncommitted at the time of writing). Write area:
`common/src/test/`, `tools/`.

Result: `ArgsTest` (8), `EventLogTest` (9), `ProtocolToolsTest` (1) and
`tools/check-twins.js`, run first by `tools/build-all.sh`. `:common:test`
passes in both builds, 121 tests each. A deliberate `compat` drift made the
script exit 1; a mixin-list mismatch was not tried. No bug found in `Args` or
`EventLog`.

Unit tests for `core/Args.java` and `core/EventLog.java`, and a test that
`Protocol.VERSION` is listed in `PROTOCOLS` in `tools/puppet/lib.js`. Then a
script that compares the public signatures of each `compat` class with its
twin in `mc1.20.1/`, and the two `mc_puppet.mixins.json` lists, and fails
when they differ where they should not. Run by `tools/build-all.sh`.

## Task 4 — A client started with `pretend_production` has no exit of its own

Status: **done 2026-10-07**: owner decision, a hint only. `McPuppet.init` logs
one WARN saying the bridge is intentionally off and the window must be closed
by hand; no auto-exit, no window title, no change to the bridge or consent code.

Such a client has no bridge, so nothing can ask it to quit; it must be closed
by hand (see `McPuppet.java`, around lines 44 to 49, and CLAUDE.md). Whether it
should end itself after a timeout, or print how to close it, or stay as it is,
is a design decision for the owner. Any answer touches the consent and bridge
code, which CLAUDE.md says is read twice, so the change gets a security
double read before it is merged.

## Task 5 — Scenarios for what only a real game shows

Status: **open, owner-gated**, written 2026-10-07. Needs a real game on all
four targets (1.21.1 Fabric and NeoForge, 1.20.1 Fabric and Forge), so it
cannot be done by an agent alone.

Scenarios for: stopping and releasing held input; `break_block`; repairing a
hotbar hand. And `tools/prod-check.js` beyond the one case it was seen on
(NeoForge 1.21.1): the Forge 1.20.1 jar, the two Fabric jars, and a client
started from a launcher.
