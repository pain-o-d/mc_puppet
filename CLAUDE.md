# mc_puppet — Minecraft mod

Lets a program on the same machine see and drive a running game, for testing
mods. Read `README.md` first: it is the user-facing truth, including the
safety model, and must stay true.

Multi-loader mod built on **Architectury** for **Minecraft 1.21.1**, shipping
to **Fabric** and **NeoForge** from one shared codebase. Same toolchain, same
pinned versions and the same network workaround as its sibling `../get_rich`,
whose `CLAUDE.md` explains the workaround in full
(`tools/fetch-architectury.sh`; plugin versions pinned exactly).

## Layout

| Path | Purpose |
|------|---------|
| `common/…/core/` | Plain Java, no game: `Protocol` (wire + token), `Ops` (registry, `help`, `batch`), `Waiter` (tick-driven waits), `Bridge` (the socket), `PuppetConfig`, `Args`. `GameJson` is the one class here that touches the game. |
| `common/…/client/` | `ClientOps`, `ChatLog`, `PuppetClient`. **Nothing on a dedicated server may load these**; `McPuppet.init` reaches them only inside an environment check. |
| `common/…/server/` | `ServerOps`. |
| `common/…/mixin/` | Two client accessor mixins. No injections anywhere, on purpose. |
| `fabric/`, `neoforge/` | Entry points and metadata only. |
| `tools/puppet/` | `lib.js` (discovery + connection), `scenario.js` (the scenario language), `puppet.js` (CLI), `mcp.js` (MCP server). No dependencies. |
| `scenarios/` | Worked examples, runnable against a dev client. |

Base package `com.modrinth.pain_o_d.mc_puppet`, mod id `mc_puppet`.

## Commands

```bash
./gradlew build
./gradlew :common:test
node --test tools/puppet/scenario.test.js
./gradlew :fabric:runClient        # bridge on, player "Puppet", window opens
./gradlew :fabric:runServer        # dedicated; eula and offline mode live in fabric/run/
node tools/puppet/puppet.js --dir . launch client --name bot1,bot2 --server localhost:25565   # fabric/runs/<name>, client@bot1
tools/stop-dev-server.sh           # clear orphaned dev JVMs of this project

P="node tools/puppet/puppet.js --dir ."
$P status
$P client help
$P run scenarios/trade-with-a-villager.json
```

A dev run of *this* project has the bridge on by a Loom `vmArg`. Anywhere
else it is off until told otherwise. Never change that default.

## Rules that are not obvious

- **Consent lives in the user's home directory, never in the game directory.**
  `Consent` is what makes a shipped config harmless. Do not add a second key
  to `config/mc_puppet.json`, an environment variable a launcher profile can
  carry, or a wildcard: each would be shipped or set by exactly the people it
  is there to stop. `-Dmc_puppet.pretend_production=true` tries the rules for
  everybody else from a dev run, and can only make things stricter. A client
  started that way has no bridge and must be closed by hand.
- **Raise `Protocol.VERSION` when an existing operation changes what it takes
  or answers**, not when one is added, and add the number to `PROTOCOLS` in
  `tools/puppet/lib.js` for as long as the tools still speak it.
- **Two Minecraft versions, one set of sources** (`docs/MULTIVERSION.md`). The
  root builds 1.21.1; `mc1.20.1/` is a build of its own that compiles the same
  `common/src`, leaving out every package called `compat` and bringing its
  own. A difference between versions goes in `compat/`, in **both** files,
  with the same name and meaning, or the other build stops. Shared code never
  asks which version it is on. After touching shared code, run
  `tools/build-all.sh`, and before believing it, both scenarios on all four:
  `node tools/puppet/puppet.js --dir mc1.20.1 launch client --project mc1.20.1 --loader forge`.
  Each of the four has shown something the others did not.
- **Security is the product's licence to exist.** Loopback only, off by
  default, token per start, wrong token ends the connection. There is no
  setting for the bind address and there must never be one. Any change near
  `Bridge` or `PuppetConfig` gets read twice.
- **An operation runs on the game thread and returns a future.** Never block
  in one. Anything that waits goes through `Waiter`, which is polled from the
  tick. `Ops.run` moves work to the game thread and turns every failure,
  including a crash inside an operation, into an answer.
- **Answers are paid for by the token.** Empty slots are left out, an item
  with nothing unusual is an id and a count, long NBT is cut and marked. Keep
  new operations that way and give big ones a filter.
- **Refuse in words.** `Ops.Refused("no screen is open")` reaches a test
  report and a model verbatim. Say what was wrong and what to do.
- **The game describes itself.** A new operation is one `ops.now(name, args,
  does, …)`; `help` and the MCP server pick it up. Keep `args` and `does`
  accurate — they are the documentation.
- **`join_server` goes to a loopback address and nowhere else**, by the same
  rule as `Reach` and for the same reason. A remote test server is a forwarded
  port. Do not add a way round it; there is no need for one.
- **`launch` never edits the project it starts.** What a named client needs —
  its run directory, its player's name, the bridge switched on — goes through
  `tools/puppet/launch.init.gradle`. It is in the npm package's `files`.
- **Entities: only the living.** A mob killed this tick is in the world for
  another second. The first live scenario failed on exactly that.
- **Test against a game, not just compile.** The scenario in `scenarios/` is
  the smoke test; it has caught what unit tests could not.

## Verified, and not

Verified on Fabric 1.21.1: client bridge (screens, clicks, world creation,
trading, screenshot, quit), server bridge inside single-player and on a
dedicated server (no client class loaded). Since then, and by 2026-09-21: the
scenarios on all four targets (1.21.1 Fabric and NeoForge, 1.20.1 Fabric and
Forge) in development environments; the built jar in a real NeoForge 1.21.1
server (`tools/prod-check.js`); the live bridge attacked
(`tools/attack-check.js`). And on 2026-09-22 `join_server` and named clients on all four: 1.21.1
against a real Fabric server on another machine through `ssh -L` (four clients
at once), 1.20.1 against dev servers here. **Not tried:** the other three jars
outside a development environment, and a real client from a launcher.

**Published**: 0.1.2, a beta, on npm (`mc-puppet`), on GitHub
(`pain-o-d/mc_puppet`, public) and submitted to Modrinth (`mc-puppet`).
**`docs/RELEASING.md` is how**, with the reason beside every rule; read it
before touching a version number. `docs/ROADMAP.md` has what an independent
security review found and what 1.0.0 waits for.

## Git workflow

Gitflow, as in `../get_rich`: `main` is releases, work happens on `develop`,
`feature/*` and `bugfix/*` merge with `--no-ff`, Conventional Commits.

Never commit `run/`, `build/`, or `.claude/settings.local.json`.
