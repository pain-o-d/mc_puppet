# Changelog

Versions are `<mod version>+mc<Minecraft version>`: the same mod version is
the same features and the same protocol on every game it is built for.

## Unreleased

- The `-sources.jar` of each build holds the sources of what is in the jar. Those of
  0.1.1 and 0.1.2 held one file of forty-four, the loader's entry point: the
  common module, which is where the bridge is, was left out. Read the code in the
  repository at the tag instead; it is what the jars were built from.

## 0.1.2

2026-09-21. A beta.

- **A client's bridge reaches this machine, and no further.** In a world of
  one's own, or on a server on `localhost`, nothing changes. On a server that
  is anywhere else the bridge neither drives nor reads the game: clicks, keys,
  walking, attacking, commands, the screen, nearby entities and screenshots
  are all refused, by name and through a `batch` or a `wait_until` alike, and
  whatever a test was holding is let go of on the way in. `info`, `help`,
  `stop`, `release_keys`, `leave_world` and `quit` still answer, and `info`
  says `"elsewhere": true`. A tool that presses a player's keys is a bot on
  somebody else's server, and nothing about testing a mod needs one. 0.1.1
  made no such distinction; nobody had asked it to yet.
- Every place the project is found says where the rest of it is: the README
  links to Modrinth and npm, the npm page to the mod and the manual, and the
  mod menu in the game to the mod's page, its source and its issues.

## 0.1.1

2026-09-21. **The first version published**, a beta: what has and has not been
tried is at the top of the README. It is 0.1.0, below, with one correction.

- Each build names the one game it was tried on. 0.1.0 said `~1.21.1` and
  `~1.20.1` on Fabric, which is any 1.21.x and any 1.20.x, and on NeoForge a
  range with no top at all. The mod lives in mixins into the mouse, the
  keyboard and the screens, which move between minor versions; on 1.21.4 a
  launcher would have called it compatible and the game would have crashed
  instead of saying the mod was for another version. Found before anything
  was published, by being asked what the mod depends on.
- The pages said "needs Architectury API", which is the whole of it on Forge
  and NeoForge. On Fabric it needs Fabric API as well: not for itself, but
  Architectury does.

## 0.1.0

2026-09-21. Tagged, built and never published: see 0.1.1. Minecraft 1.21.1 (Fabric, NeoForge) and 1.20.1 (Fabric,
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
- Reviewed before release by somebody other than its author, told to be hostile;
  what that found is fixed and listed in `docs/ROADMAP.md`.

**Seen working**, not only compiled: the scenarios on all four targets in
development environments, and the built jar in a real NeoForge server
refusing, opening once allowed, and refusing again
(`node tools/prod-check.js --eula`).
