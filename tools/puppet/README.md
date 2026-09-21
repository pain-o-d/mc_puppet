# mc-puppet

The command line tools and MCP server for **MC Puppet**, a Minecraft mod that
lets a program on the same machine see and drive a running game — the open
screen as data, clicks and keys through the game's own input handlers,
commands with their output, screenshots — so a mod can be tested without a
person at the keyboard.

These tools talk to the mod; they do nothing without it. The mod, for
Minecraft 1.21.1 (Fabric, NeoForge) and 1.20.1 (Fabric, Forge), and the whole
manual, are at the project's page. No dependencies; Node 18 or later.

```bash
npx mc-puppet status                        # which games are listening
npx mc-puppet client screen                 # the open screen: widgets, slots, a merchant's offers
npx mc-puppet client click_widget text=Done
npx mc-puppet server command '{"command":"time set day"}'
npx mc-puppet run scenarios/trade.json --junit results.xml
npx mc-puppet launch client --loader fabric --world my_world   # a dev game through your Gradle wrapper
npx mc-puppet stop
```

`--dir <gameDir>` says where to look, or `MC_PUPPET_DIRS`; a mod project's
root will do, since `run`, `fabric/run`, `neoforge/run` and `forge/run` under
it are looked in.

## For AI coding agents

```json
"mc-puppet": {
  "type": "stdio",
  "command": "npx",
  "args": ["-y", "mc-puppet", "mcp"],
  "env": { "MC_PUPPET_DIRS": "/path/to/your_mod" }
}
```

Five tools: `puppet_status`, `puppet_help`, `puppet_call`, `puppet_run` and
`puppet_screenshot`. The game describes its own operations, so the list stays
short.

## Outside a development environment

The mod is off unless switched on, listens on 127.0.0.1 only, and wants a
token it makes afresh each start. In a real launcher's instance that is still
not enough, because a modpack ships its config folder: the game directory has
to be allowed from your home directory, one at a time.

```bash
npx mc-puppet allow "C:/games/my-instance"     # and: disallow, allowed
```

Nothing you download should ever write there. If a game you did not set up
says MC Puppet is switched on and has stayed off, that is this at work, and
nothing needs doing.

MIT.
