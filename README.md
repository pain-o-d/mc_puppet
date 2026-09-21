# MC Puppet

**[The mod, on Modrinth](https://modrinth.com/mod/mc-puppet)** · **[The tools, on npm](https://www.npmjs.com/package/mc-puppet)** · [Releases](https://github.com/pain-o-d/mc_puppet/releases) · [Changelog](CHANGELOG.md) · [Report a problem](https://github.com/pain-o-d/mc_puppet/issues) · [Report a vulnerability, privately](SECURITY.md)

Lets a program on the same machine **see and drive a running Minecraft**, so a
mod can be tested without a person at the keyboard.

A server can be driven from its console, and its logic tested with a fake
player. A screen cannot. Whether a button is where it should be, whether a
trading screen restates its offers without closing, whether a slot holds what
the player was shown — that is only knowable in a client, and until now only
by somebody looking at it. MC Puppet makes the client answer questions.

- **See:** the open screen as data — every widget with its text, position and
  state; a container's slots and cursor; a merchant's offers — plus the player,
  chat, nearby entities, and a screenshot for what data cannot say.
- **Do:** click widgets and slots, select a trade, press keys, type, run
  commands, use an entity or a block — through the code paths a player uses.
- **Get there:** create, open and leave worlds, resize the window, change the
  GUI scale, quit, and wait *on the game's own tick* for a screen, a world, a
  chat line.
- **Server side too:** commands with their output captured, players and
  inventories, entities with a villager's offers, a block and its contents.
  More than RCON, and no password.
- **Scenarios:** a JSON list of steps with expectations, run from a shell, CI,
  or an AI coding agent through the bundled **MCP server**. A 24-step trading
  test runs in under two seconds.

Minecraft **1.21.1** (Fabric, NeoForge) and **1.20.1** (Fabric, Forge), those two versions exactly · needs [Architectury API](https://modrinth.com/mod/architectury-api), and on Fabric [Fabric API](https://modrinth.com/mod/fabric-api) · MIT

> **Beta.** Used so far by one mod's test suite, its author's. Seen working: the
> scenarios on all four targets in development environments, and the built jar
> in a real NeoForge 1.21.1 server. **Not yet tried**: the other three jars
> outside a development environment, and a real client from an ordinary
> launcher. The protocol and the scenario language may still change before
> 1.0.0, so pin an exact version of the mod and of the tools in your project.

## Safety first

This is remote control of a game, and it is built to be refused.

- **Off by default.** Dropping the jar into a mods folder does nothing but log
  one line and write a `config/mc_puppet.json` that says `"enabled": false`.
  It is switched on by `"enabled": true` in `config/mc_puppet.json`
  or by `-Dmc_puppet.enabled=true`.
- **Loopback only.** It binds `127.0.0.1`. There is no setting that makes it
  listen to a network.
- **A token every start.** Each request must carry a 256-bit token made afresh
  at startup and written to `<gameDir>/mc_puppet/endpoint-<side>.json`.
  Reaching the port is not enough; a caller has to be able to read the game's
  own files — and whoever can do that could already edit the world.
- **A wrong token ends the connection**, and is worth a line in the log. So
  does anything that is not this protocol, before a token has been shown: a
  page in a browser can reach a port on localhost, and gets no further than
  that. A connection that proves nothing in ten seconds is dropped.
- **The token's file is its owner's alone where the file system can say so**:
  on Linux and macOS the `mc_puppet/` folder and the file are closed to other
  accounts from the moment they exist. **Windows cannot be told that way**, and
  the file is as readable as the folder the game is in: yours alone under your
  user profile, anybody's with an account on the machine in a folder like
  `D:\Games`. On a machine other people use, keep the game under your profile
  or leave the bridge off.
- **Outside a development environment the switch is not enough.** A modpack
  ships its `config/` folder, so a developer who tests with MC Puppet and then
  exports the instance ships `"enabled": true` to every player of it — and a
  second key in the same file would ship right beside the first. In a game
  not started from Gradle or an IDE, the bridge also needs **consent kept
  where a pack cannot put it**: `~/.mc_puppet/allowed.json`, naming that game
  directory. `mc-puppet allow <gameDir>` writes it, one directory at a time,
  no wildcard; `disallow` takes it back. Without it the mod logs why it
  stayed off and what to run. Consent that a pack could have brought with it
  is refused by name: an entry that is not an absolute path (`"."` would be
  every game started from its own folder), a consent file that is itself
  inside the game directory (a server whose home *is* its root), a home that
  is not an absolute path. On Fabric, where the loader calls a game a
  development environment because a system property says so, the game is
  looked at too: a real one runs in intermediary names, and is not believed.
  **What this does not stand against** is a pack that can set JVM arguments or
  carries a mod of its own. That is code running as the player already, and it
  needs no bridge. What is kept out is *files* arriving with a download.
- **When it is on outside development, the player is told** in chat on joining
  a world, every time. A log is not somewhere a player looks.
- **This machine, and no further.** A client's bridge works in a world of
  your own and on a server on `localhost`. On any other server it neither
  drives nor reads the game — no clicks, no keys, no walking, no screen, no
  list of who is nearby — and lets go of anything a test was holding on the
  way in. A tool that presses a player's keys is a bot on somebody else's
  server, and nothing about testing a mod needs one. `info` says
  `"elsewhere": true` there, and leaving still works.
- **Everything asked is written down**, in `mc_puppet/audit-<side>.log`:
  each request, each step inside a `batch`, what a `wait_until` polls. The
  one written about supplies the words, so a name cannot break a line and
  padding cannot push an argument out of sight.
- **A scenario is a program for your game.** It can run any command at level 4
  in whatever game it is pointed at, so read one before you run it, as you
  would a shell script. What it cannot do is reach your machine: its sums are
  read by a parser and never evaluated, and the one thing it names to write, a
  golden screenshot, has to be a `.png` under the scenario's own folder.
- On a server it runs commands at operator level 4. Do not enable it on a
  server whose machine you share with people you would not give the console.

When it is on, it says so at `WARN` in the log, with the file that holds the
token. **Do not ship a modpack with it enabled** — and if one ever is, the
above is why it still does nothing on a player's machine.

**The mod and these tools are installed separately**, and say which version of
the protocol they speak: the mod in its endpoint file and in `info`, the tools
in `lib.js`. A mismatch is reported as one, naming which to update, and not as
an operation that mysteriously is not there. Pin an exact version of each in
a project; test infrastructure should not change by itself.

## Quick start

1. Get the jar for your loader and game version from
   [Modrinth](https://modrinth.com/mod/mc-puppet/versions) (the same files, with checksums, are on the
   [releases page](https://github.com/pain-o-d/mc_puppet/releases)). Put it, and Architectury API, in `mods/`, or
   depend on it in your dev environment (below).
2. Start the game with `-Dmc_puppet.enabled=true`.
3. Talk to it. The tools are on npm as `mc-puppet`, with no dependencies
   (Node 18 or later):

```bash
npx mc-puppet --dir <gameDir> status
npx mc-puppet --dir <gameDir> client help
npx mc-puppet --dir <gameDir> client screen
npx mc-puppet --dir <gameDir> client click_widget text=Singleplayer
npx mc-puppet --dir <gameDir> server command '{"command":"time set day"}'
npx mc-puppet --dir <gameDir> run scenarios/trade-with-a-villager.json
```

From a checkout of this repository the same command is
`node tools/puppet/puppet.js`, which is how the rest of this page writes it.

`--dir` is a game directory or a mod project root: `run`, `fabric/run` and
`neoforge/run` under it are looked in too. Without it, `MC_PUPPET_DIRS`
(`;`-separated), then the current directory.

### In a Loom / Architectury dev environment

**Fabric:** drop the jar into your project's `run/mods/`; Fabric Loader remaps
it as the game starts.

**Forge and NeoForge:** not `run/mods/`. Their jars are in SRG or Mojang names
and a dev run is in your mappings', and nothing remaps a file in a folder: the
game dies on the first Minecraft class the mod names. Make it a dependency
instead, and Loom remaps it:

```groovy
dependencies {
    // In a dev run only: never in your jar, never in your published dependencies.
    modLocalRuntime "maven.modrinth:mc-puppet:0.1.2+mc1.20.1-forge"   // or +mc1.21.1-neoforge
}
```

(with `https://api.modrinth.com/maven` among your repositories, limited to the
`maven.modrinth` group). ForgeGradle and NeoGradle have their own words for a
runtime-only mod dependency; the point is the same.

Then switch it on: add `vmArg '-Dmc_puppet.enabled=true'` to your run configs,
or put `{"enabled": true}` in `run/config/mc_puppet.json`. `run/` is normally
gitignored, which is what you want: the switch stays on your machine.

## Operations

Ask the game — `help` on either side lists every operation with its arguments.
In short:

| Client | |
|---|---|
| `info` `screen` `player` `count` `chat` `entities` `screenshot` | seeing |
| `frame` `tooltip` `hud` `events` | seeing what is not a widget |
| `block` `blocks` `target` `raycast` `world` `perf` `bindings` | the world as the client has it |
| `click_widget` `click_at` `hover` `drag` `scroll` `key` `release_keys` `type` | a mouse and a keyboard |
| `look` `hold` `tap` `move_to` `attack` `break_block` `stop` | the character |
| `set_text` `click_slot` `select_trade` `close_screen` `command` `say` `use_entity` `use_block` `use_item` `hotbar` | doing |
| `worlds` `create_world` `open_world` `leave_world` `window` `quit` `wait` | getting there |

| Server | |
|---|---|
| `info` `players` `inventory` `count` `entities` `entity` `block` | seeing |
| `command` (captured output, optional `as` a player) | doing |
| `wait` (ticks, or players online) | |

Both sides: `help`; `batch` — several steps in one round trip; and
`wait_until` — any operation, a path into its answer and an expectation,
looked at in the game once a tick:

```json
{ "op": "wait_until", "args": { "op": "screen", "path": "slots[slot=2].stack.id",
                                "equals": "minecraft:apple", "timeout_ms": 5000 } }
```

Wait for the thing, never for a number of ticks: "10 ticks" is a guess that
holds on the machine it was guessed on.

**Input is real input.** `click_widget`, `click_at`, `hover`, `drag`,
`scroll`, `key` and `type` enter the game where GLFW's callbacks do, so
the loaders' screen events fire, key bindings work with no screen open (`e`
opens the inventory), `"modifiers": ["shift"]` makes a shift-click, and a
drag over slots spreads a stack as it does for a player. A mod that listens
for a screen event instead of overriding a method is exercised like any other.
`"direct": true` calls the screen's own method instead, for the odd case.

**Names that survive a release build.** A class name is not one:
`MerchantScreen` is `class_492` in a shipped jar. `screen` reports a
container's registered `handler_type` (`minecraft:merchant`) and a title's
`title_key`, and `wait {for: screen}` matches on those first.

`screen` is the one to know. For a container it returns the panel's bounds,
every non-empty slot with its on-screen position, the cursor stack, and for a
merchant the offers *as displayed* (with discounts and demand applied) and
which one is selected. Coordinates are scaled GUI pixels, the same space
widgets live in, so "is this button outside the panel?" is arithmetic.

### Seeing without a screenshot

A screen draws most of what it shows without widgets: a trading screen's
prices, its heading, a HUD overlay. `frame` records the drawing calls of the
next frame — every string with where it landed and how wide, every item, every
tooltip, on request every sprite — with the transform in force applied, so a
scaled heading or a tooltip is where the player sees it:

```json
{ "op": "frame", "args": { "contains": "cents" },
  "expect": [ { "path": "texts[0].x", "gte": 0 }, { "path": "issues#", "equals": 0 } ] }
```

`issues` is the part of looking at a screenshot that is arithmetic: text that
runs **off the screen**, text drawn **over other text**, and a **label wider
than its widget**. With a screen open they are judged among what the screen itself drew — each
text carries its `layer`: `hud`, `screen` or `overlay` — because the HUD is
behind it and what overlaps there is nobody's defect. Nothing is recorded
unless asked, and then for one frame.

`tooltip {slot|widget|x,y}` hovers and returns the lines the game then
draws, with what mods add. `hud` is the action bar, title, boss bars, sidebar,
effects, health, food, air, experience. `events` is what was over before
anyone could look — messages, the action bar, titles, toasts and **sounds** —
numbered, so a test takes `sequence` before acting and asks `since` it after:
a sound is often all a mod does to say that something worked.

`block`, `blocks`, `target`, `raycast` and `world` read the world as the
*client* believes it. Ask the server the same and a client out of step with it
is caught.

### The character

`hold {keys: [forward, sneak], ticks: 20}` holds the game's own key bindings —
any binding by name, mods' included (`bindings` lists them) — and `tap`
presses one once, whatever key it is bound to. `look` turns the head and
answers with what the crosshair is then on. `move_to` faces a place and walks
there, jumping when blocked; it does not find paths. `attack` is the game's own
attack on what the crosshair is on, and `break_block` holds it on a block for
as long as that takes with what is in hand — dirt by hand is fifteen ticks, and
a test can say so. None of it teleports: what a mod does to movement, reach or
mining is between the key and its effect, and that is the part exercised.

## Scenarios

```json
{ "name": "the currency button restates the counter",
  "steps": [
    { "op": "use_entity", "args": { "type": "minecraft:villager" } },
    { "op": "wait", "args": { "for": "screen", "value": "Merchant" } },
    { "op": "screen", "save": "before",
      "expect": [ { "path": "widgets[text~=Currency]", "exists": true },
                  { "path": "offers#", "gte": 1 } ] },
    { "op": "click_widget", "args": { "text": "Currency" } },
    { "op": "screen",
      "expect": [ { "path": "offers[0].buy.id", "not": "${before.offers[0].buy.id}" } ] },
    { "side": "server", "op": "count", "args": { "player": "Puppet", "item": "minecraft:emerald" },
      "expect": [ { "equals": 5 } ] } ] }
```

- A scenario is `{name, setup?, steps, teardown?, allow_log?}`. **Teardown
  always runs**, every step of it, whatever failed before; a failed `setup`
  skips `steps`.
- A step is `{side?, op, args?, expect?, save?, show?, expect_error?, optional?, eventually?, note?}`.
  `side` defaults to `client`.
- **Paths:** `a.b`, `a[2]`, `a[-1]`, `a[key=value]`, `a[key~=part]` (first
  match, case-insensitive; the key may be a path, `slots[stack.id~=sword]`),
  `a#` (count). No path means the whole answer.
- **Expectations:** `equals`, `not`, `contains`, `matches` (regex), `gt` `gte`
  `lt` `lte`, `exists`. A failure says what was there instead.
- **`save`** keeps an answer; `"${name.path}"` anywhere later reuses it, and a
  string that is *only* a reference keeps the value's type.
- **Nesting and `let`:** references resolve innermost first,
  `${wallet.coins[item=${counter.offers[0].buy.id}].units}`, and a step that is
  `{"let": {"price": "${= offer.count * coin}"}}` calls nothing and names a
  value for the steps after it; it may `expect` of what it named.
- **Arithmetic:** `"${= before.coins - price.count * 2}"` — `+ - * / %`,
  brackets, `min max floor ceil round abs` over saved values. Read by a
  small parser, never `eval`: a scenario is a file someone downloaded.
- **`eventually`**: `true` (10s) or milliseconds — the step is asked again
  until its expectations hold. For one expectation `wait_until` is exact to
  the tick; `eventually` is for several at once, and with `"every_ms": 5000`
  for an operation that is work for the game to answer: `wait_until` asks
  every tick, and a costly question asked that often slows what it waits for.
- **The log is part of the result.** A scenario fails if the game logged an
  `ERROR`, a `FATAL` or a stack trace while it ran, and prints the lines.
  `"allow_log": ["regex"]` lets known ones by; `--no-log` turns it off.
- **`expect_error`** passes a step that is refused with that text: test that
  the game says no.
- **`optional`** tries a step and carries on either way — a confirmation
  dialog that may not appear.
- Stops at the first failure unless `--keep-going`. Exit code 1 on failure, so
  it drops into CI.

`scenarios/trade-with-a-villager.json` is a worked example that checks a trade
from both sides of the game and cleans up after itself;
`scenarios/eyes-and-hands.json` walks, breaks a block, hits a pig, and reads a
tooltip, the action bar, a sound and a frame. Both pass on Fabric and NeoForge.

## Writing tests faster

**Record one.** `node tools/puppet/puppet.js record my-test.json`, play it
through in the game, press Enter. What comes out is a scenario in the terms a
person would have written: a click on a button is `click_widget` by its text,
a click on a slot is `click_at {slot, modifiers}`, a screen that opens is a
`wait` for it by its handler type or title key, and the villager or block
used to get there is `use_entity` / `use_block`. It has no expectations in
it — those are the test, and yours to add. Walking about is not recorded.

**Golden screenshots.** On a step that takes a screenshot:

```json
{ "op": "screenshot", "args": { "name": "trade" },
  "golden": { "file": "golden/trade.png", "max_percent": 0.5, "region": { "x": 300, "y": 80, "w": 560, "h": 340 } } }
```

The first run writes the golden (beside the scenario); look at it once. From
then on a run compares, within a per-channel `tolerance` (16), and a failure
writes `<name>.diff.png` with the differing pixels in red.
`--update-golden` rewrites them. A golden holds for one window size, GUI
scale and language: set the window in `setup`, and compare a `region` when
the world shows behind the screen.

**CI.** `--junit results.xml` writes JUnit XML — a scenario is a suite, a step
a case, the game's logged errors a case of their own.
`puppet launch client --loader fabric --world my_world` starts the dev game
through the project's Gradle wrapper, waits for the bridge and the world, and
`puppet stop` asks it to quit, which unlike killing Gradle leaves nothing
holding the world's lock. A developer's dedicated server is seen out as well:
some development environments keep its process alive after it has stopped,
holding the remapped mod jars, and the next launch fails with *Failed to
remap mods*. With MC Puppet on, a process still there ten seconds after its
server stopped is ended. Only in a development environment, only a dedicated
server, and only after the worlds are saved.

**More than one game.** Name game directories — `--dir a=run1 --dir b=run2` or
`MC_PUPPET_DIRS=a=run1;b=run2` — and a step says `"side": "client@b"`. One
game directory per game.

## Operations of your own mod

A test of a mod wants the mod's state as data, not what a command printed:

```java
if (Platform.isModLoaded("mc_puppet")) {
    MyPuppetOps.register();          // its own class: nothing of MC Puppet loads without it
}

PuppetApi.register(PuppetApi.Side.SERVER, "my_mod:price", "{item}",
        "What an item is worth and by which route.",
        args -> priceJson(args.get("item").getAsString()));
```

The handler runs on its side's game thread; what it throws comes back as a
refusal in words; it is listed by `help`. Names are `modid:operation`.
Compile against MC Puppet without requiring it (`modCompileOnly`, an optional
dependency in the metadata, the check above). Register at any time.

## For AI coding agents (MCP)

`mc-puppet mcp` is a dependency-free [MCP](https://modelcontextprotocol.io)
server. Register it with your agent's host and point it at your project:

```json
"mc-puppet": {
  "type": "stdio",
  "command": "npx",
  "args": ["-y", "mc-puppet", "mcp"],
  "env": { "MC_PUPPET_DIRS": "/path/to/your_mod" }
}
```

(From a checkout: `"command": "node", "args": ["/path/to/mc_puppet/tools/puppet/mcp.js"]`.)

Five tools, deliberately: `puppet_status`, `puppet_help`, `puppet_call`,
`puppet_run` (a whole scenario in one call, reporting only what failed or was
asked to be shown) and `puppet_screenshot` (returns the image, so the model
can look at it). The game describes its own operations, so the tool list stays
short and every conversation pays less for it.

## The protocol

One JSON object a line, both ways, over TCP on `127.0.0.1`:

```
-> {"id": 7, "token": "…", "op": "screen", "args": {}}
<- {"id": 7, "ok": true, "result": {…}}
<- {"id": 7, "ok": false, "error": "no screen is open"}
```

The endpoint file says where: `{side, host, port, token, pid, started, protocol}`.
Default ports are 25580 (client) and 25581 (server); if one is taken the next
free one is used and the file says which. Requests may overlap; match answers
by `id`. Ten lines of any language are enough — `tools/puppet/lib.js` is the
reference.

## The audit log

Everything the bridge was asked to do is appended to
`<gameDir>/mc_puppet/audit-<side>.log`: when, which operation, its arguments
(cut at 300 characters, never the token), how it ended, how long it took.
What an unattended agent did to a game can be read back afterwards.

## Minecraft versions

| Minecraft | Loaders | Java | Built by |
|---|---|---|---|
| 1.21.1 | Fabric, NeoForge | 21 | the root build |
| 1.20.1 | Fabric, Forge | 17 | `mc1.20.1/`, from the same sources |

`tools/build-all.sh` builds all four jars. The tools, the protocol and the
scenarios are the same for every version; `info` says which game and loader
answered. Where the *game's own commands* differ between versions — an item's
data is NBT before 1.20.5 and components since — a scenario writes the value
both ways:

```json
{ "command": { "mc<1.20.5": "give @s diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:3s}]}",
               "else":      "give @s diamond_sword[enchantments={levels:{'minecraft:sharpness':3}}]" } }
```

`docs/MULTIVERSION.md` says how the two builds share their sources.

## Building

```bash
tools/build-all.sh            # all four jars, and every test
node tools/prod-check.js --eula   # the built jar in a real server: nothing opens without consent
./gradlew build               # 1.21.1, both loaders; jars in <loader>/build/libs/
./gradlew :common:test        # the protocol, token, batch and waiting, without a game
node --test tools/puppet/scenario.test.js   # the scenario language, without a game
./gradlew :fabric:runClient   # a dev client with the bridge on, player "Puppet"
```

## Limits

- The client bridge opens when the game can be used — the first tick with no
  loading splash — not when it has started. Work handed to a client that is
  still loading its resources runs from inside that loading, and a world
  opened from there never finishes opening. `open_world` and `create_world`
  refuse during a later reload too; `wait {for: loaded}` waits one out.
- A screenshot is the last rendered frame, and `frame` and `tooltip` need one
  drawn: the window must not be minimised. Behind other windows is fine.
- `frame` sees what goes through `DrawContext`. A mod that draws with its own
  vertex buffers is pixels only; an item's count is on the item, not a text.
- `move_to` does not find paths. Build the test world flat, or walk in legs.
- Input enters at the game's own mouse and keyboard handlers, not through the
  OS: a mod that registers its own GLFW callback does not hear it. The real
  mouse still works, and moving it over the window during a test moves the
  cursor.
- While the bridge is on, the game does not pause when its window loses focus
  (a test runs behind other windows). The setting is not saved.
- `use_entity` and `use_block` are checked by the server like any player's:
  stand within reach.
- The client bridge reads what the client knows. For the truth, ask the
  server — that both can be asked in one scenario is the point.
