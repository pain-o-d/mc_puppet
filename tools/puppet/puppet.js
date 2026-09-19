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
 *
 *   --dir <gameDir>   where to look (repeatable); else MC_PUPPET_DIRS, else here
 *   --keep-going      run every step of a scenario even after one fails
 *   --json            print the raw answer
 */
const fs = require("fs");
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
  const words = [];
  for (let index = 0; index < argv.length; index++) {
    if (argv[index] === "--dir") dirs.push(argv[++index]);
    else if (argv[index] === "--keep-going") keepGoing = true;
    else if (argv[index] === "--json") raw = true;
    else words.push(argv[index]);
  }
  const puppet = new Puppet(dirs);
  try {
    const [first, ...rest] = words;
    if (!first || first === "--help" || first === "-h") {
      console.log(fs.readFileSync(__filename, "utf8").split("*/")[0].replace(/^#!.*\n\/\*\*\n?/, "").replace(/^ \* ?/gm, ""));
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
      for (const file of rest) {
        const loaded = JSON.parse(fs.readFileSync(file, "utf8"));
        const report = await scenario.run(puppet, loaded, { keepGoing });
        if (raw) console.log(JSON.stringify(report, null, 1));
        else print(report);
        if (!report.ok) failures++;
      }
      return failures ? 1 : 0;
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
    if (step.ok && !step.shown && !step.skipped) continue;
    console.log(`  ${step.ok ? (step.skipped ? "skip" : "ok  ") : "FAIL"} ${step.step}. ${step.side} ${step.op}` +
      `${step.note ? "  - " + step.note : ""}`);
    for (const problem of step.problems || []) console.log(`       ${problem}`);
    if (step.shown !== undefined) console.log("       " + JSON.stringify(step.shown));
  }
}

main().then((code) => process.exit(code), (failure) => {
  console.error("puppet: " + failure.message);
  process.exit(2);
});
