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
- **Entities: only the living.** A mob killed this tick is in the world for
  another second. The first live scenario failed on exactly that.
- **Test against a game, not just compile.** The scenario in `scenarios/` is
  the smoke test; it has caught what unit tests could not.

## Verified, and not

Verified on Fabric 1.21.1: client bridge (screens, clicks, world creation,
trading, screenshot, quit), server bridge inside single-player and on a
dedicated server (no client class loaded). **NeoForge builds and has not been
launched.**

## Git workflow

Gitflow, as in `../get_rich`: `main` is releases, work happens on `develop`,
`feature/*` and `bugfix/*` merge with `--no-ff`, Conventional Commits.

Never commit `run/`, `build/`, or `.claude/settings.local.json`.
