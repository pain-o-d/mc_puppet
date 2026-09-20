# Changelog

Versions are `<mod version>+mc<Minecraft version>`: the same mod version is
the same features and the same protocol on every game it is built for.

## 0.1.0

The first release. Minecraft 1.21.1 (Fabric, NeoForge) and 1.20.1 (Fabric,
Forge), from one set of sources. Needs Architectury API.

**What it does**

- A bridge on each side of the game, for a program on the same machine: the
  client's sees and drives the screen, the server's runs commands and reads
  the world.
- The open screen as data: widgets with their text and position, slots, a
  merchant's offers, tooltips, the HUD. `frame` reports everything a screen
  draws, by layer, with layout problems it can see — text that does not fit
  its widget, a widget off the screen.
- Input through the game's own mouse and keyboard handlers, so what a mod
  listens for is what it gets. The character too: look, hold, tap, move to,
  attack, break a block.
- Worlds made, opened and left; screenshots; an event log to wait on.
- Scenarios: JSON steps with expectations, saved values and arithmetic,
  `eventually` and `wait_until`, setup and teardown, golden screenshots,
  values that depend on the game's version, JUnit output. Recorded by playing.
- An MCP server for AI coding agents, five tools wide.
- `PuppetApi`: a mod registers operations of its own, `modid:name`, and
  answers its tests in data.

**What keeps it safe**

- Off unless switched on. Loopback only, IPv4, with no setting for the
  address. A token made afresh each start. Operators' power on a server and
  no more.
- Outside a development environment the switch is not enough: the game
  directory has to have been allowed from the user's home, by
  `mc-puppet allow <gameDir>`, one directory at a time. A modpack ships its
  config folder; it cannot ship that.
- An audit log of what was asked, and a protocol version the tools check.

**Seen working**, not only compiled: the scenarios on all four targets in
development environments, and the built jar in a real NeoForge server
refusing, opening once allowed, and refusing again
(`node tools/prod-check.js --eula`).
