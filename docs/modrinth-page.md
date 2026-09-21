<!--
  The text of the project's page on Modrinth, kept here so that it changes with the mod.
  Everything under the line goes into "Description". The fields beside it:

    Name      MC Puppet
    Slug      mc-puppet            (the README and the release notes link to modrinth.com/mod/mc-puppet)
    Summary   See and drive a running Minecraft from a script, CI or an AI coding agent, to test mods
              without a person at the keyboard. Off by default, localhost only.
    Categories        Utility, Library, Management   (it is a developer's tool: not Adventure, not Cursed)
    Client / server   Client: optional. Server: optional.   (either side works alone)
    License           MIT
    Source / Issues   https://github.com/pain-o-d/mc_puppet  and  …/issues
    Each version      channel Beta; depends on Architectury API (required)
-->

---

**A tool for people who make mods.** If you are here because a modpack has it: it does nothing on your machine unless you switch it on yourself, and you can remove it. [Why it is safe to have installed](#is-it-safe) is below.

# See and drive a running Minecraft, from a program

A server can be tested from its console. A screen cannot. Whether a button is where it should be, whether a trading screen restates its offers without closing, whether a slot holds what the player was shown — until now that took somebody looking at it. MC Puppet makes the client answer questions.

```bash
npx mc-puppet client screen                  # the open screen as data: widgets, slots, a merchant's offers
npx mc-puppet client click_widget text=Done  # through the game's own mouse handler
npx mc-puppet server command '{"command":"time set day"}'   # with its output
npx mc-puppet run scenarios/trade.json --junit results.xml  # a whole test, for CI
```

- **See:** every widget with its text, position and state; a container's slots; a merchant's offers; tooltips; the HUD; chat; nearby entities. `frame` reports everything a screen draws and the layout problems it can find — a label that does not fit its button, a widget off the screen. And a screenshot, for what data cannot say.
- **Do:** click, type, press keys, select a trade, use a block or an entity, look, walk, attack — through the code paths a player's input takes, so your mod's listeners hear what they would hear.
- **Get there:** create, open and leave worlds, resize the window, and wait *on the game's own tick* for a screen, a world, a chat line. No sleeps.
- **Both sides:** the server's bridge runs commands with their output captured and reads players, inventories, entities and blocks. A test clicks in the client and then asks the server what the villager now holds.
- **Scenarios:** JSON steps with expectations, saved values and arithmetic, setup and teardown, golden screenshots, JUnit output. Record one by playing.
- **For AI coding agents:** an MCP server, `npx mc-puppet mcp`, five tools wide. The agent that wrote the screen can look at it.
- **Your mod can answer too:** register operations of your own, `yourmod:something`, and let tests ask for your mod's state as data instead of reading it off a screen.

Minecraft **1.21.1** (Fabric, NeoForge) and **1.20.1** (Fabric, Forge), one mod version for all four. Needs [Architectury API](https://modrinth.com/mod/architectury-api). The command line tools are a separate, dependency-free npm package, [`mc-puppet`](https://www.npmjs.com/package/mc-puppet).

## In a development environment

**Fabric:** put the jar in `run/mods/`. **Forge and NeoForge:** make it a dependency, so that Loom remaps it — a jar in `run/mods/` is not in the names a dev run uses:

```groovy
modLocalRuntime "maven.modrinth:mc-puppet:<version>"
```

Then switch it on with `-Dmc_puppet.enabled=true` or `{"enabled": true}` in `run/config/mc_puppet.json`, and ask it what it can do: `npx mc-puppet client help`. The [manual](https://github.com/pain-o-d/mc_puppet#readme) has the operations, the scenario language and worked examples.

## Is it safe?

This is remote control of a game, and it is built to be refused.

- **Off by default.** Installed and not switched on, it logs one line and does nothing else.
- **Localhost only.** It listens on 127.0.0.1, and there is no setting that makes it listen to a network.
- **A token every start.** 256 bits, made afresh, in a file in the game's own folder. Reaching the port is not enough; a page in your browser gets no further than that.
- **A modpack cannot switch it on for you.** A pack ships its `config/` folder, so outside a development environment the switch is not enough: you have to allow that game directory yourself, from your home directory, with `npx mc-puppet allow <gameDir>`. One directory at a time, no wildcard. Until then the mod says in the log that it was switched on and has stayed off.
- **When it is on in a real game, you are told** in chat, every time you join a world.
- **Everything asked of it is written down** in an audit log beside the token.

It was reviewed before release by somebody other than its author, told to be hostile; what that found is fixed, and [listed](https://github.com/pain-o-d/mc_puppet/blob/main/docs/ROADMAP.md). What it does not defend against is a pack that can set JVM arguments or carries a mod of its own: that is code running as you already, and it needs no bridge.

## Beta

Used so far by one mod's test suite. Seen working: scenarios on all four targets in development environments, and the built jar in a real NeoForge 1.21.1 server. **Not yet tried:** the other three jars outside a development environment, and a real client from an ordinary launcher. The protocol and the scenario language may change before 1.0.0, so pin an exact version of the mod and of the tools. Reports are welcome on [GitHub](https://github.com/pain-o-d/mc_puppet/issues).
