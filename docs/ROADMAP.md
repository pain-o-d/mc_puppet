# Roadmap

Written 2026-09-19 after a critique of version 0.1.0. The critique in one
line: **a green test meant less than it looked**, and the client could click
but not walk, and see widgets but not the screen.

Boxes are ticked as work lands. Each phase ends with a run against a live
client, because that is where 0.1.0's own bugs were found.

## Phase 1 — what a green test is worth

The existing operations, made honest. Nothing new to show for it; everything
after it rests on it.

- [x] **Input goes through the game's input handlers.** `click_widget`,
      `click_at`, `key` and `type` called the screen's methods directly. The
      loaders' screen events — Fabric API's, NeoForge's, Architectury's — are
      raised by `Mouse` and `Keyboard`, one level up, so a mod that listens
      for an event rather than overriding a method was **not exercised at
      all**, and its test passed. Input now enters where a real mouse and
      keyboard do. `direct: true` keeps the old path for the odd case.
- [x] **Hover, drag, scroll, modifiers, key press and release.** What a
      tooltip, a split stack, a trade list and a shift-click need.
- [x] **The game does not pause when its window loses focus** while the
      bridge is on. A test runs behind other windows; vanilla opens the pause
      menu there, and every in-world step then meets a screen nobody opened.
- [x] **Names that survive a release build.** A scenario waited for
      `MerchantScreen`, which in a shipped jar is `class_492`. A container
      screen reports its registered handler type (`minecraft:merchant`) and a
      titled screen its translation key; waits match on those.
- [x] **`wait_until`: wait for data, not for ticks.** Any operation, a path
      into its answer and an expectation, evaluated in the game each tick.
      The worked scenarios wait "10 ticks" five times; each is a guess.
      Paths and expectations move into the mod's core, tested without a game.
- [x] **Scenarios: `setup` / `steps` / `teardown`**, with teardown always
      run. Clean-up sat at the end of `steps` and did not happen after a
      failure.
- [x] **Arithmetic in expectations:** `"${= before.coins - price.count * 2}"`.
      "The purse is lighter by exactly the price" could not be said.
- [x] **`eventually`** on a step: retry until its expectations hold.
- [x] **The log is part of the result.** A scenario fails if the game logged
      an error or an exception while it ran, and says which; and says so if
      the game died.
- [x] **NeoForge is launched**, not just built, and the smoke scenario passes
      on it. The first run found what building could not: `select_trade`
      sent its packet through a method NeoForge replaces, a
      `NoSuchMethodError` there and nowhere else. **Run both loaders before
      believing a change to `ClientOps`.**

## Phase 2 — eyes and hands

- [x] **Tooltips as text**: what hovering a slot or a widget shows, with the
      lines mods add.
- [x] **A frame as data**: every string, item and sprite drawn in one frame,
      with where. A screen draws most of what it shows without widgets — a
      trading screen's prices, its "Trades" heading, the villager's level —
      and until now only a screenshot could see it. With positions, overlap,
      text off the screen and a label too wide for its button are arithmetic.
      Recorded for one frame on request, never otherwise.
- [x] **Widget state by kind**: a checkbox's tick, a slider's value, a
      cycling button's value; `set_text` and focus.
- [x] **The HUD**: action bar, title and subtitle, boss bars, sidebar, status
      effects, experience, air, armour.
- [x] **An event log**: chat and system messages as before, and the action
      bar, titles, toasts and **sounds**, each numbered. A sound is often a
      mod's only feedback, and no screenshot shows one.
- [x] **The world as the client believes it**: a block, a box of blocks, what
      the crosshair is on, a ray from the eyes, light, biome, time, weather.
      Only the server could be asked, so a client out of step with it could
      not be caught.
- [x] **Richer entities**: health, equipment, velocity, vehicle.
- [x] **The character**: hold movement keys for so many ticks, look, walk to
      a place, attack, break a block, use and hold use, drop, swap hands.
      Through the key bindings and the interaction manager, as a player's
      input arrives, not by teleport.
- [x] **Performance**: frame rate, frame time, memory.

## Phase 3 — writing tests faster, and in more places

- [x] **Recording**: play through by hand once, get the scenario. Verified in
      a game on both loaders with input sent by a program; not yet with a
      person's hands on the mouse, which goes through the same events.
- [x] **An API for mods**: a mod registers operations of its own, so a test
      reads its state as data instead of parsing what a command printed.
      Unit-tested; **no mod has used it in a running game yet.**
- [x] **More than one game**: two clients in one scenario, by name.
      Unit-tested; **two games have not been run side by side yet.**
- [x] **JUnit XML** from the runner, for CI.
- [x] **Golden screenshots**: compare with a kept image within a tolerance,
      so layout regressions are caught without anyone looking. Unit-tested
      against real PNG data; no golden is kept in this repository, since one
      holds for one machine's window and language.
- [x] **`launch` and `stop`** from the CLI. The first `launch` hung the
      game: it asked for a world the second the bridge opened, while the
      client was still loading its resources. The bridge now opens when the
      game can be used. **A tool that is faster than a person finds what a
      person's pauses were hiding.**
- [x] **An audit trail**: every operation logged at debug, so what an agent
      did can be read back.

## Before it is published

- [x] **A shipped config opens nothing.** Outside a development environment
      the bridge needs consent from the user's home directory, per game
      directory (`Consent`, `mc-puppet allow`). Seen in a running game:
      refused with the reason and the command in the log; opened once
      allowed; the player told in chat on joining; refused again once
      disallowed.
- [x] **The protocol has a version**, in the endpoint file and in `info`, and
      the tools name which side to update when they differ.
- [x] The token's file is its owner's alone where that can be said.
- [x] **A review of the safety story by somebody other than its author**, on
      2026-09-21: an agent with no part in writing it, read-only, told to be
      hostile. The network side held - loopback only, nothing runs before a
      256-bit token is accepted, a remote attacker has no way in and a page
      in a browser gets no further than the token. Four things did not hold
      as written, and are fixed, each with a test that fails on the old code:
  - *Consent could be answered from inside the game directory*: an entry of
    `"."`, a home directory that is the game directory (a container), a
    shipped `-Duser.home`. Absolute entries only now, and a consent file
    inside the game directory counts for nothing.
  - *On Fabric a JVM argument made a player's game a development
    environment*, which needs no consent. The game is looked at as well as
    asked: a real one runs in intermediary names.
  - *The audit log could be lied to*: a step hidden in a `batch` behind
    padding, a line break in an operation's name writing lines of its own, a
    file that only turned over at startup.
  - *A scenario's golden could be written anywhere*, and with
    `--update-golden` over anything. A `.png` under the scenario's folder now.

      Smaller: a connection that proves nothing is dropped after ten seconds
      and at its first line that is not the protocol; a wrong token is a line
      in the log; the token's file is closed to others from the moment it
      exists; world names cannot walk out of `saves/`; `prod-check` takes its
      consent back on Ctrl+C. `tools/attack-check.js` tries nineteen of these
      against a running game. **What the review could not settle, and nobody
      has tried**: whether a real Fabric game starts at all with
      `-Dfabric.development=true`; which launchers let a pack set JVM
      arguments; the actions in the workflow are pinned by tag, not by hash.
- [x] **The built jar in a real server**, outside any development environment
      (`node tools/prod-check.js --eula`): with a config that says on and no
      consent it stays off and says what to run; allowed, it opens on
      127.0.0.1 with a token; disallowed, off again; the process ending by
      itself each time. Fourteen checks. NeoForge 1.21.1 only so far: the
      other three jars, and a real *client*, are not tried.
- [x] The tools are an npm package, `mc-puppet`, with `mc-puppet mcp` for the
      MCP server: packed, installed into an empty directory, run through npx.
      Not published: that is the owner's to do.
- [x] The README says four targets, and how a Forge or NeoForge dev run takes
      the mod (`modLocalRuntime`, not `run/mods`). The licence is in every
      jar; there is an icon and a changelog; the 1.21.1 jars say `+mc1.21.1`.
- [ ] A repository the metadata's links can point at. They name
      `github.com/pain-o-d/mc_puppet`, which does not exist yet.
- [ ] Published: the package on npm, the mod on Modrinth, which is also its
      Maven.
- [ ] An exit of its own for a client started with `pretend_production`.
- [x] A developer's dedicated server that has stopped does not leave its
      process behind (`core/Leaving`; see MULTIVERSION.md for why it did).

## What 1.0.0 waits for

Every release before it is a beta, marked so on Modrinth and on GitHub; the
version number says as much by starting with a nought. It stops being one when:

- [ ] all four jars have run in real installs, outside any development
      environment, a client from an ordinary launcher among them, with the
      consent story seen on each (`tools/prod-check.js` does NeoForge 1.21.1's
      server and nothing else yet);
- [ ] a project other than its author's uses it;
- [ ] the protocol has gone several releases without a change that breaks a
      scenario.

## What the phases taught

- Run both loaders. Each phase had something only NeoForge showed.
- A flaky step is a finding, not a nuisance. `frame` reported issues one run
  in ten; the cause was chat and a tutorial toast, drawn under and over the
  screen. Guessing fixed nothing; making a failed step show what it saw did.
  Frames now have layers, and a screen is judged by what it drew itself.
- A check on `entities[0]` passes until the world has an entity of its own.
  Name the thing that was acted on.

## Not planned, and why

- **Pathfinding.** `move_to` walks straight and jumps when stuck. A test
  that needs a maze solved should teleport.
- **Listing every list row and tab generically.** World lists and creative
  tabs are not widgets. The narrator's text could name them; clicking them
  needs positions each list keeps private in its own way. Operations cover
  the common cases (`open_world`); the rest waits for a need.
- **Packets as data.** Useful for a mod with networking, invasive, and a
  mod can expose what it sent through the API above.
- **Headless.** A client renders to a window. CI on Linux can give it a
  virtual display; on Windows the window opens.
- **Other game versions.** One version done well first.
