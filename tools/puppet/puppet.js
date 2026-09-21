#!/usr/bin/env node
/**
 * MC Puppet from a shell, for scripts and CI.
 *
 *   node puppet.js status                         which games are listening
 *   node puppet.js client help                    what the client answers to
 *   node puppet.js client screen                  the open screen as data
 *   node puppet.js client click_widget text=Done  key=value arguments
 *   node puppet.js server command '{"command":"time set day"}'   or JSON
 *   node puppet.js run scenarios/smoke.json       a scenario; exits 1 if it fails
 *   node puppet.js record out.json                play by hand, Enter to stop: a scenario of what was done
 *   node puppet.js launch client --loader fabric --world my_world
 *                                                 start a dev game, wait for its bridge (and the world)
 *   node puppet.js stop                           quit the client, stop the server
 *   node puppet.js allow <gameDir>                let this game be driven outside a development
 *                                                 environment (a real launcher's instance); "disallow"
 *                                                 takes it back, "allowed" lists them
 *   node puppet.js mcp                            be an MCP server on stdio, for an AI coding agent
 *   node puppet.js version                        these tools' version, and the protocols they speak
 *
 * Installed from npm the command is "mc-puppet": npx mc-puppet status.
 *
 *   --dir <gameDir>   where to look (repeatable); else MC_PUPPET_DIRS, else here.
 *                     name=<gameDir> names a game: "side": "client@name" in a scenario
 *   --junit <file>    also write the results as JUnit XML, for CI
 *   --update-golden   write golden screenshots from this run instead of comparing
 *   --project <dir>   launch: the mod project with the gradlew (default: here)
 *   --loader <name>   launch: fabric or neoforge (default: fabric)
 *   --world <name>    launch client: open this saved world and wait for it
 *   --timeout <s>     launch: how long a start may take (default: 600)
 *   --keep-going      run every step of a scenario even after one fails
 *   --json            print the raw answer
 *   --no-log          do not fail a scenario for errors the game logged while it ran
 */
const fs = require("fs");
const path = require("path");
const { Puppet } = require("./lib");
const scenario = require("./scenario");

function parseArgs(words) {
  if (words.length === 1 && words[0].trim().startsWith("{")) return JSON.parse(words[0]);
  const args = {};
  for (const word of words) {
    const at = word.indexOf("=");
    if (at < 0) throw new Error(`arguments are key=value or one JSON object; got "${word}"`);
    const raw = word.slice(at + 1);
    let value = raw;
    if (/^-?\d+(\.\d+)?$/.test(raw)) value = Number(raw);
    else if (raw === "true" || raw === "false") value = raw === "true";
    else if (raw.startsWith("{") || raw.startsWith("[")) value = JSON.parse(raw);
    args[word.slice(0, at)] = value;
  }
  return args;
}

async function main() {
  const argv = process.argv.slice(2);
  const dirs = [];
  let keepGoing = false;
  let raw = false;
  let watchLog = true;
  let junit = null;
  let updateGolden = false;
  const launchOptions = { project: ".", loader: "fabric", world: null, timeout: 600 };
  const words = [];
  for (let index = 0; index < argv.length; index++) {
    if (argv[index] === "--dir") dirs.push(argv[++index]);
    else if (argv[index] === "--keep-going") keepGoing = true;
    else if (argv[index] === "--json") raw = true;
    else if (argv[index] === "--no-log") watchLog = false;
    else if (argv[index] === "--junit") junit = argv[++index];
    else if (argv[index] === "--update-golden") updateGolden = true;
    else if (argv[index] === "--project") launchOptions.project = argv[++index];
    else if (argv[index] === "--loader") launchOptions.loader = argv[++index];
    else if (argv[index] === "--world") launchOptions.world = argv[++index];
    else if (argv[index] === "--timeout") launchOptions.timeout = Number(argv[++index]);
    else words.push(argv[index]);
  }
  const puppet = new Puppet(dirs);
  try {
    const [first, ...rest] = words;
    if (!first || first === "--help" || first === "-h") {
      console.log(fs.readFileSync(__filename, "utf8").split("*/")[0].replace(/^#!.*\n\/\*\*\n?/, "").replace(/^ \* ?/gm, ""));
      return 0;
    }
    if (first === "allow" || first === "disallow") {
      if (!rest[0]) throw new Error(`${first} which game directory? the folder with mods/ and config/ in it`);
      const lib = require("./lib");
      const target = path.resolve(rest[0]);
      if (first === "allow" && !fs.existsSync(path.join(target, "mods")) && !fs.existsSync(path.join(target, "config"))) {
        throw new Error(`${target} has no mods/ or config/ in it, so it does not look like a game directory`);
      }
      const kept = lib.setAllowed(target, first === "allow");
      console.log(first === "allow"
        ? `${target} may now be driven when MC Puppet is switched on there. Written to ${lib.consentFile()}`
        : `${target} may no longer be driven.`);
      console.log(kept.length ? "allowed: " + kept.join(", ") : "nothing is allowed outside development environments");
      return 0;
    }
    if (first === "allowed") {
      const lib = require("./lib");
      const kept = lib.readAllowed();
      console.log(kept.length ? kept.join("\n") : "nothing is allowed outside development environments");
      return 0;
    }
    if (first === "status") {
      const endpoints = puppet.endpoints();
      if (!endpoints.length) {
        console.log("No game is listening. Start one with -Dmc_puppet.enabled=true.");
        return 1;
      }
      for (const endpoint of endpoints) {
        const info = await puppet.call(endpoint.side, "info").catch((failure) => ({ error: failure.message }));
        console.log(`${endpoint.side} 127.0.0.1:${endpoint.port} pid ${endpoint.pid} in ${endpoint.dir}`);
        console.log("  " + JSON.stringify({ ...info, mods: info.mods ? info.mods.length + " mods" : undefined }));
      }
      return 0;
    }
    if (first === "run") {
      let failures = 0;
      const reports = [];
      for (const file of rest) {
        const loaded = JSON.parse(fs.readFileSync(file, "utf8"));
        const report = await scenario.run(puppet, loaded,
          { keepGoing, watchLog, updateGolden, baseDir: path.dirname(path.resolve(file)) });
        reports.push(report);
        if (raw) console.log(JSON.stringify(report, null, 1));
        else print(report);
        if (!report.ok) failures++;
      }
      if (junit) {
        fs.mkdirSync(path.dirname(path.resolve(junit)), { recursive: true });
        fs.writeFileSync(junit, require("./junit").junitXml(reports));
      }
      return failures ? 1 : 0;
    }
    if (first === "record") {
      const out = rest[0];
      if (!out) throw new Error("record to which file? puppet record my-scenario.json");
      await puppet.call("client", "record_start");
      console.log("Recording. Play it through in the game, then press Enter here.");
      await new Promise((done) => process.stdin.once("data", done));
      process.stdin.pause();
      const recorded = await puppet.call("client", "record_stop", { name: path.basename(out, ".json") });
      fs.writeFileSync(out, JSON.stringify(recorded, null, 2) + "\n");
      console.log(`${recorded.steps.length} steps written to ${out}. Add "expect" to the ones that matter.`);
      return 0;
    }
    if (first === "launch") {
      return await require("./launch").launch(puppet, rest[0] || "client", launchOptions);
    }
    if (first === "stop") {
      return await require("./launch").stop(puppet);
    }
    if (first === "client" || first === "server") {
      const [op, ...argWords] = rest;
      if (!op) throw new Error(`which operation? try: puppet ${first} help`);
      const result = await puppet.call(first, op, parseArgs(argWords));
      if (op === "help" && !raw) {
        for (const each of result.ops) console.log(`${each.op} ${each.args}\n    ${each.does}`);
      } else {
        console.log(JSON.stringify(result, null, raw ? 0 : 1));
      }
      return 0;
    }
    throw new Error(`unknown command "${first}"; try --help`);
  } finally {
    puppet.close();
  }
}

function print(report) {
  console.log(`${report.ok ? "PASS" : "FAIL"}  ${report.name}  (${report.passed}/${report.of} steps` +
    `${report.ran < report.of ? `, stopped after ${report.ran}` : ""})`);
  for (const step of report.steps) {
    if (step.ok && !step.shown && !step.skipped && !(step.golden && step.golden.written)) continue;
    console.log(`  ${step.ok ? (step.skipped ? "skip" : "ok  ") : "FAIL"} ${step.step}. ${step.side} ${step.op}` +
      `${step.phase ? "  (" + step.phase + ")" : ""}${step.note ? "  - " + step.note : ""}`);
    for (const problem of step.problems || []) console.log(`       ${problem}`);
    if (step.golden && step.golden.written) console.log(`       golden written: ${step.golden.written}`);
    if (step.shown !== undefined) console.log("       " + JSON.stringify(step.shown));
  }
  for (const problem of report.log_problems || []) console.log(`  LOG  ${problem.line}`);
  if ((report.log_problems || []).length) {
    console.log("       the game logged errors while this ran; \"allow_log\": [regex] in the scenario lets known ones by");
  }
}

if (process.argv[2] === "mcp") {
  // A server, not a command: it lives as long as its stdin does, and nothing here may exit for it.
  require("./mcp");
} else if (process.argv[2] === "version" || process.argv[2] === "--version") {
  console.log(`mc-puppet ${require("./package.json").version}, protocol ${require("./lib").PROTOCOLS.join(", ")}`);
} else {
  main().then((code) => process.exit(code), (failure) => {
    console.error("puppet: " + failure.message);
    process.exit(2);
  });
}
