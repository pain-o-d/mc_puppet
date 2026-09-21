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
- `../get_rich/tools/run-client-scenarios.sh`, which is the release
  checklist's real suite. `launch client` changed underneath it — the
  wrapper is started differently on Windows, and `stop` asks each game by
  its own connection — so run it in a fresh world before tagging.

## Traps met

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
