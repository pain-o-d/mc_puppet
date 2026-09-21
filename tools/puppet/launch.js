/**
 * Starting and stopping a dev game from a script.
 *
 * A CI job, or an agent, needs the whole loop: start the game, wait until it
 * can be talked to, run, stop. Starting is Gradle's run task, detached so that
 * it outlives this process, with its output in a file. Stopping is asking
 * nicely - quit on the client, stop on the server - because killing Gradle
 * leaves the game it started alive and holding the world's lock.
 */
const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");

const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

/** The Gradle task and the game directory of a side, for a Loom or Architectury project. */
function plan(side, options) {
  if (side !== "client" && side !== "server") throw new Error(`launch client or server, not "${side}"`);
  const project = path.resolve(options.project || ".");
  const wrapper = path.join(project, process.platform === "win32" ? "gradlew.bat" : "gradlew");
  // A single-loader project has no loader subproject; its run task is at the root.
  const multi = fs.existsSync(path.join(project, options.loader));
  const task = `${multi ? ":" + options.loader + ":" : ""}run${side === "client" ? "Client" : "Server"}`;
  return { project, wrapper, task, log: path.join(project, "build", `mc_puppet-launch-${side}.log`) };
}

async function launch(puppet, side, options) {
  const { project, wrapper, task, log } = plan(side, options);
  if (!fs.existsSync(wrapper)) throw new Error(`no Gradle wrapper at ${wrapper}; --project <the mod's directory>`);
  if (puppet.endpoints().some((endpoint) => endpoint.side === side)) {
    console.log(`a ${side} is already running with its bridge on; using it`);
  } else {
    fs.mkdirSync(path.dirname(log), { recursive: true });
    const out = fs.openSync(log, "w");
    // A .bat is not a program: on Windows it is run by cmd, named here rather than through
    // "shell: true", which would paste the arguments into a command line unescaped.
    const words = [task, "-Dmc_puppet.enabled=true"];
    const child = process.platform === "win32"
      ? spawn(process.env.ComSpec || "cmd.exe", ["/d", "/c", wrapper, ...words],
        { cwd: project, detached: true, stdio: ["ignore", out, out], windowsHide: true })
      : spawn(wrapper, words, { cwd: project, detached: true, stdio: ["ignore", out, out] });
    child.unref();
    console.log(`started ${task} (output in ${log}); waiting for the ${side} bridge`);
    const deadline = Date.now() + options.timeout * 1000;
    let exited = null;
    child.on("exit", (code) => { exited = code; });
    while (!puppet.endpoints().some((endpoint) => endpoint.side === side)) {
      if (exited !== null) throw new Error(`${task} ended with code ${exited} before the bridge opened; see ${log}`);
      if (Date.now() > deadline) throw new Error(`no ${side} bridge after ${options.timeout}s; see ${log}`);
      await sleep(2000);
    }
  }
  const info = await puppet.call(side, "info");
  if (side === "client" && options.world && !info.in_world) {
    const worlds = await puppet.call("client", "worlds");
    if (!worlds.includes(options.world)) {
      throw new Error(`there is no saved world "${options.world}" (there are: ${worlds.join(", ") || "none"}). `
        + "create_world makes one");
    }
    await puppet.call("client", "open_world", { name: options.world });
    await puppet.call("client", "wait", { for: "world", timeout_ms: 300000 });
  }
  console.log(`${side} ready${side === "client" && options.world ? " in " + options.world : ""}`);
  return 0;
}

async function stop(puppet) {
  const endpoints = puppet.endpoints();
  if (!endpoints.length) {
    console.log("nothing is running with its bridge on");
    return 0;
  }
  const hasClient = endpoints.some((endpoint) => endpoint.side === "client");
  for (const endpoint of endpoints) {
    // An integrated server goes with its client; asking it to stop first would only race the quit.
    if (endpoint.side === "server" && hasClient) continue;
    try {
      if (endpoint.side === "client") await puppet.call("client", "quit");
      else await puppet.call("server", "command", { command: "stop" });
      console.log(`asked the ${endpoint.side} (pid ${endpoint.pid}) to stop`);
    } catch (gone) {
      // Closing the connection is how a game that is quitting answers.
      console.log(`the ${endpoint.side} (pid ${endpoint.pid}) is stopping`);
    }
  }
  const deadline = Date.now() + 60000;
  while (puppet.endpoints().length && Date.now() < deadline) await sleep(1000);
  const left = puppet.endpoints();
  if (left.length) {
    console.log(`still running after a minute: ${left.map((endpoint) => endpoint.side + " pid " + endpoint.pid).join(", ")}`);
    return 1;
  }
  return 0;
}

module.exports = { launch, stop, plan };
