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

- [ ] **Tooltips as text**: what hovering a slot or a widget shows, with the
      lines mods add.
- [ ] **A frame as data**: every string, item and sprite drawn in one frame,
      with where. A screen draws most of what it shows without widgets — a
      trading screen's prices, its "Trades" heading, the villager's level —
      and until now only a screenshot could see it. With positions, overlap,
      text off the screen and a label too wide for its button are arithmetic.
      Recorded for one frame on request, never otherwise.
- [ ] **Widget state by kind**: a checkbox's tick, a slider's value, a
      cycling button's value; `set_text` and focus.
- [ ] **The HUD**: action bar, title and subtitle, boss bars, sidebar, status
      effects, experience, air, armour.
- [ ] **An event log**: chat and system messages as before, and the action
      bar, titles, toasts and **sounds**, each numbered. A sound is often a
      mod's only feedback, and no screenshot shows one.
- [ ] **The world as the client believes it**: a block, a box of blocks, what
      the crosshair is on, a ray from the eyes, light, biome, time, weather.
      Only the server could be asked, so a client out of step with it could
      not be caught.
- [ ] **Richer entities**: health, equipment, velocity, vehicle.
- [ ] **The character**: hold movement keys for so many ticks, look, walk to
      a place, attack, break a block, use and hold use, drop, swap hands.
      Through the key bindings and the interaction manager, as a player's
      input arrives, not by teleport.
- [ ] **Performance**: frame rate, frame time, memory.

## Phase 3 — writing tests faster, and in more places

- [ ] **Recording**: play through by hand once, get the scenario.
- [ ] **An API for mods**: a mod registers operations of its own, so a test
      reads its state as data instead of parsing what a command printed.
- [ ] **More than one game**: two clients in one scenario, by name.
- [ ] **JUnit XML** from the runner, for CI.
- [ ] **Golden screenshots**: compare with a kept image within a tolerance,
      so layout regressions are caught without anyone looking.
- [ ] **`launch` and `stop`** from the CLI.
- [ ] **An audit trail**: every operation logged at debug, so what an agent
      did can be read back.

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
