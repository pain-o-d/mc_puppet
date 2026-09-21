/**
 * Starting and stopping a dev game from a script.
 *
 * A CI job, or an agent, needs the whole loop: start the game, wait until it
 * can be talked to, run, stop. Starting is Gradle's run task, detached so that
 * it outlives this process, with its output in a file. Stopping is asking
 * nicely - quit on the client, stop on the server - because killing Gradle
 * leaves the game it started alive and holding the world's lock.
 *
 * A test of multiplayer needs several clients of one project at once, and a
 * project has one run directory and one player's name. So a client may be
 * given a name: it then lives in runs/<name> beside the loader's run/, made on
 * first use from a template, plays under a name of its own, and is
 * "client@<name>" to a scenario. The project's build file is not touched:
 * launch.init.gradle says it all from outside, for the one run.
 */
const fs = require("fs");
const path = require("path");
const { spawn, spawnSync } = require("child_process");
const { Connection, sameDir } = require("./lib");

const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

/** What a client made from nothing starts with: quiet, cheap to draw, and no screens that wait for a person. */
const OPTIONS = [
  "onboardAccessibility:false", "skipMultiplayerWarning:true", "tutorialStep:none", "joinedFirstServer:true",
  "pauseOnLostFocus:false", "soundCategory_master:0.0", "maxFps:30", "renderDistance:6", "simulationDistance:6",
  "enableVsync:false", "fullscreen:false", "narrator:0",
];

/** What a new game directory takes from the loader's own run/: what makes it the same game, and nothing it played. */
const FROM_RUN = ["mods", "config", "options.txt"];

/** The Gradle task and the game directory of a side, for a Loom or Architectury project. */
function plan(side, options) {
  if (side !== "client" && side !== "server") throw new Error(`launch client or server, not "${side}"`);
  const project = path.resolve(options.project || ".");
  const wrapper = path.join(project, process.platform === "win32" ? "gradlew.bat" : "gradlew");
  // A single-loader project has no loader subproject; its run task is at the root.
  const multi = fs.existsSync(path.join(project, options.loader));
  const task = `${multi ? ":" + options.loader + ":" : ""}run${side === "client" ? "Client" : "Server"}`;
  const loaderDir = multi ? path.join(project, options.loader) : project;
  const name = options.name || null;
  if (name !== null && side !== "client") throw new Error("--name is for clients: a project has one dev server");
  if (name !== null && !/^[A-Za-z_][\w-]{0,31}$/.test(name)) {
    throw new Error(`"${name}" cannot name a game: a letter first, then letters, digits, _ and -`);
  }
  let username = options.username || null;
  if (username === null && name !== null) {
    if (!/^\w{3,16}$/.test(name)) {
      throw new Error(`"${name}" cannot be a player's name as well (3 to 16 letters, digits and _); give --username`);
    }
    username = name;
  }
  if (username !== null && !/^\w{3,16}$/.test(username)) {
    throw new Error(`"${username}" cannot be a player's name: 3 to 16 letters, digits and _`);
  }
  if (username !== null && side !== "client") throw new Error("--username is for clients");
  const runDir = name === null ? null : `runs/${name}`;
  return {
    side, project, wrapper, task, loaderDir, name, username, runDir,
    gameDir: path.join(loaderDir, runDir || "run"),
    log: path.join(project, "build", `mc_puppet-launch-${side}${name === null ? "" : "-" + name}.log`),
  };
}

/** Makes a named client's game directory, once. What is there already is somebody's, and is left alone. */
function prepare(planned, template) {
  if (planned.runDir === null || fs.existsSync(planned.gameDir)) return false;
  fs.mkdirSync(planned.gameDir, { recursive: true });
  if (template) {
    if (!fs.existsSync(template)) throw new Error(`there is no template at ${template}`);
    fs.cpSync(template, planned.gameDir, { recursive: true });
  } else {
    const run = path.join(planned.loaderDir, "run");
    for (const each of FROM_RUN) {
      if (fs.existsSync(path.join(run, each))) {
        fs.cpSync(path.join(run, each), path.join(planned.gameDir, each), { recursive: true });
      }
    }
  }
  const options = path.join(planned.gameDir, "options.txt");
  if (!fs.existsSync(options)) fs.writeFileSync(options, OPTIONS.join("\n") + "\n");
  return true;
}

const bridgeOf = (puppet, planned) => puppet.endpoints()
  .find((endpoint) => endpoint.side === planned.side && sameDir(endpoint.dir, planned.gameDir));

/** One operation asked of one game exactly: with several clients up, "the newest client" is the wrong question. */
async function ask(endpoint, op, args, timeoutMs) {
  const connection = await new Connection(endpoint).connect();
  try {
    return await connection.call(op, args || {}, timeoutMs);
  } finally {
    connection.close();
  }
}

/**
 * The Java a Windows machine would run Gradle with: JAVA_HOME, else the one on the PATH, asked
 * where it really lives. What is on the PATH is usually Oracle's stub, which starts the real one.
 */
function windowsJava() {
  const home = process.env.JAVA_HOME || (() => {
    try {
      const asked = spawnSync("java", ["-XshowSettings:properties", "-version"], { encoding: "utf8", windowsHide: true });
      const found = /^\s*java\.home = (.+)$/m.exec(String(asked.stderr) + String(asked.stdout));
      return found ? found[1].trim() : null;
    } catch (noJava) {
      return null;
    }
  })();
  const java = home && path.join(home, "bin", "java.exe");
  return java && fs.existsSync(java) ? java : null;
}

function start(planned) {
  fs.mkdirSync(path.dirname(planned.log), { recursive: true });
  const out = fs.openSync(planned.log, "w");
  const words = ["--init-script", path.join(__dirname, "launch.init.gradle"), planned.task, "--console=plain"];
  if (planned.runDir !== null) words.push(`-Pmc_puppet.run_dir=${planned.runDir}`);
  if (planned.username !== null) words.push(`-Pmc_puppet.username=${planned.username}`);
  const detached = { cwd: planned.project, detached: true, stdio: ["ignore", out, out], windowsHide: true };
  let child;
  if (process.platform !== "win32") {
    child = spawn(planned.wrapper, words, detached);
  } else {
    // A .bat is not a program, and the cmd that would run it, started detached, hands its own
    // output on and loses Java's: the file a failed launch pointed at was always empty. So what
    // gradlew.bat does is done here, with no cmd between: the wrapper's jar, by the real Java.
    const java = windowsJava();
    const jar = path.join(planned.project, "gradle", "wrapper", "gradle-wrapper.jar");
    child = java && fs.existsSync(jar)
      ? spawn(java, ["-Xmx64m", "-Xms64m", "-Dorg.gradle.appname=gradlew", "-jar", jar, ...words], detached)
      // Named, and not through "shell: true", which would paste the arguments into a command line unescaped.
      : spawn(process.env.ComSpec || "cmd.exe", ["/d", "/c", planned.wrapper, ...words], detached);
  }
  child.unref();
  const started = { exited: null };
  child.on("exit", (code) => { started.exited = code; });
  return started;
}

/** Whether Gradle has got as far as the game: from there on it only waits, and the next build may begin. */
function gameBegun(planned) {
  try {
    return fs.readFileSync(planned.log, "utf8").includes(`> Task ${planned.task}`);
  } catch (absent) {
    return false;
  }
}

async function launch(puppet, side, options) {
  const names = options.name ? String(options.name).split(",").map((each) => each.trim()).filter(Boolean) : [null];
  if (names.length > 1 && options.username) {
    throw new Error("several clients cannot share one --username; without it each plays under its own name");
  }
  if (options.world && options.server) throw new Error("--world or --server, not both");
  const plans = names.map((name) => plan(side, { ...options, name }));
  if (!fs.existsSync(plans[0].wrapper)) {
    throw new Error(`no Gradle wrapper at ${plans[0].wrapper}; --project <the mod's directory>`);
  }
  const deadline = Date.now() + options.timeout * 1000;
  const overdue = (planned) => new Error(`no ${side} bridge${planned.name ? " for " + planned.name : ""} after `
    + `${options.timeout}s; see ${planned.log}`);

  const running = [];
  for (const planned of plans) {
    if (bridgeOf(puppet, planned)) {
      console.log(`${planned.name || "a " + side} is already running with its bridge on; using it`);
      continue;
    }
    if (prepare(planned, options.template && path.resolve(options.template))) {
      console.log(`made ${planned.gameDir}`);
    }
    const started = start(planned);
    running.push({ planned, started });
    console.log(`started ${planned.task}${planned.name ? " as " + planned.name : ""} (output in ${planned.log})`);
    // One build at a time up to the game itself: two builds of one project that compile and
    // remap side by side wait on each other's locks at best.
    while (planned !== plans[plans.length - 1] && !gameBegun(planned) && !bridgeOf(puppet, planned)
      && started.exited === null) {
      if (Date.now() > deadline) throw overdue(planned);
      await sleep(1000);
    }
  }
  for (const { planned, started } of running) {
    while (!bridgeOf(puppet, planned)) {
      if (started.exited !== null) {
        throw new Error(`${planned.task} ended with code ${started.exited} before the bridge opened; see ${planned.log}`);
      }
      if (Date.now() > deadline) throw overdue(planned);
      await sleep(2000);
    }
  }

  for (const planned of plans) {
    const endpoint = bridgeOf(puppet, planned);
    const info = await ask(endpoint, "info");
    let where = "";
    if (side === "client" && !info.in_world && options.world) {
      const worlds = await ask(endpoint, "worlds");
      if (!worlds.includes(options.world)) {
        throw new Error(`there is no saved world "${options.world}" (there are: ${worlds.join(", ") || "none"}). `
          + "create_world makes one");
      }
      await ask(endpoint, "open_world", { name: options.world });
      await ask(endpoint, "wait", { for: "world", timeout_ms: 300000 }, 310000);
      where = " in " + options.world;
    } else if (side === "client" && !info.in_world && options.server) {
      await ask(endpoint, "join_server", { address: options.server });
      await ask(endpoint, "wait", { for: "world", timeout_ms: 120000 }, 130000);
      where = " on " + options.server;
    }
    console.log(`${planned.name || side} ready${where}${side === "client" ? " as " + (info.username || "?") : ""}`);
  }
  return 0;
}

/** Asks every game found to stop, or with names only the games called so. */
async function stop(puppet, names = []) {
  const all = puppet.endpoints();
  const unknown = names.filter((name) => !all.some((endpoint) => endpoint.game === name));
  if (unknown.length) {
    console.log(`not running, or not called that: ${unknown.join(", ")}`);
  }
  const endpoints = names.length ? all.filter((endpoint) => names.includes(endpoint.game)) : all;
  if (!endpoints.length) {
    console.log("nothing is running with its bridge on");
    return unknown.length ? 1 : 0;
  }
  for (const endpoint of endpoints) {
    // An integrated server goes with its client; asking it to stop first would only race the quit.
    if (endpoint.side === "server"
      && endpoints.some((other) => other.side === "client" && sameDir(other.dir, endpoint.dir))) continue;
    const called = `${endpoint.game ? endpoint.game + "'s " : ""}${endpoint.side} (pid ${endpoint.pid})`;
    try {
      if (endpoint.side === "client") await ask(endpoint, "quit");
      else await ask(endpoint, "command", { command: "stop" });
      console.log(`asked the ${called} to stop`);
    } catch (gone) {
      // Closing the connection is how a game that is quitting answers.
      console.log(`the ${called} is stopping`);
    }
  }
  const pids = new Set(endpoints.map((endpoint) => endpoint.pid));
  const left = () => puppet.endpoints().filter((endpoint) => pids.has(endpoint.pid));
  const deadline = Date.now() + 60000;
  while (left().length && Date.now() < deadline) await sleep(1000);
  if (left().length) {
    console.log(`still running after a minute: ${left().map((endpoint) => endpoint.side + " pid " + endpoint.pid).join(", ")}`);
    return 1;
  }
  return unknown.length ? 1 : 0;
}

module.exports = { launch, stop, plan, prepare };
