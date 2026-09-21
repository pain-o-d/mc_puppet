#!/usr/bin/env node
/*
 * The built jar in a real server, outside any development environment, and the
 * one promise that only means something there: a config that switches the
 * bridge on opens nothing until the person at this machine has said this game
 * directory may be driven.
 *
 *   node tools/prod-check.js --eula            install if need be, then the three runs below
 *   SERVER_FROM=<an installed NeoForge server> node tools/prod-check.js --eula
 *                                              copies its libraries rather than downloading the game again
 *
 *   1. the config says on, nobody has allowed it   -> stays OFF, says why and what to run, no endpoint file
 *   2. after "allow <dir>"                         -> the bridge opens; info says this is not development;
 *                                                     a command runs; the token file is there
 *   3. after "disallow <dir>"                      -> OFF again
 *
 * and each time the process ends by itself once the server is told to stop.
 *
 * Everything lands in runs/neoforge-prod/, which is not committed. Consent is
 * written to the real ~/.mc_puppet/allowed.json, since that is the thing being
 * tried, and is taken back at the end whatever happened.
 *
 * --eula says you accept Minecraft's EULA (https://aka.ms/MinecraftEULA) for
 * this server; without it, and without an eula.txt already there, nothing starts.
 */
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn, spawnSync } = require("child_process");
const lib = require("./puppet/lib.js");

const root = path.join(__dirname, "..");
const dir = path.join(root, "runs", "neoforge-prod");
const props = Object.fromEntries(fs.readFileSync(path.join(root, "gradle.properties"), "utf8")
  .split(/\r?\n/).filter((line) => /^[a-z_]+\s*=/.test(line)).map((line) => line.split(/\s*=\s*/)));
const NEOFORGE = props.neoforge_version;
const java = process.env.JAVA21 || (process.platform === "win32"
  ? "C:/Program Files/Java/jdk-21/bin/java.exe" : "java");
const PORT = 25581;

function cached(group, artifact, version) {
  const base = path.join(os.homedir(), ".gradle", "caches", "modules-2", "files-2.1", group, artifact, version);
  if (!fs.existsSync(base)) return null;
  for (const hash of fs.readdirSync(base)) {
    const jar = path.join(base, hash, `${artifact}-${version}.jar`);
    if (fs.existsSync(jar)) return jar;
  }
  return null;
}

function install() {
  const libraries = path.join(dir, "libraries", "net", "neoforged", "neoforge", NEOFORGE);
  if (fs.existsSync(libraries)) return;
  fs.mkdirSync(dir, { recursive: true });
  if (process.env.SERVER_FROM) {
    console.log(`== copying an installed server's libraries from ${process.env.SERVER_FROM}`);
    fs.cpSync(path.join(process.env.SERVER_FROM, "libraries"), path.join(dir, "libraries"), { recursive: true });
    if (fs.existsSync(libraries)) return;
  }
  console.log(`== installing NeoForge ${NEOFORGE} (downloads the game: a few minutes)`);
  const installer = path.join(dir, `neoforge-${NEOFORGE}-installer.jar`);
  if (!fs.existsSync(installer)) {
    const got = spawnSync("curl", ["-fsSL", "-o", installer,
      `https://maven.neoforged.net/releases/net/neoforged/neoforge/${NEOFORGE}/neoforge-${NEOFORGE}-installer.jar`],
    { stdio: "inherit" });
    if (got.status !== 0) throw new Error("could not download the installer");
  }
  const done = spawnSync(java, ["-jar", installer, "--installServer"], { cwd: dir, encoding: "utf8" });
  fs.writeFileSync(path.join(dir, "installer.log"), (done.stdout || "") + (done.stderr || ""));
  if (done.status !== 0) throw new Error(`the installer failed; see ${path.join(dir, "installer.log")}`);
}

function furnish() {
  const mods = path.join(dir, "mods");
  fs.rmSync(mods, { recursive: true, force: true });
  fs.mkdirSync(mods, { recursive: true });
  const built = path.join(root, "neoforge", "build", "libs");
  const ours = fs.readdirSync(built).filter((name) => /^mc_puppet-neoforge-.*\.jar$/.test(name)
    && !/-(dev|sources|dev-shadow)\.jar$/.test(name));
  if (ours.length !== 1) throw new Error(`expected one built jar in ${built}, found: ${ours.join(", ") || "none"}`);
  fs.copyFileSync(path.join(built, ours[0]), path.join(mods, ours[0]));
  const architectury = cached("dev.architectury", "architectury-neoforge", props.architectury_api_version);
  if (!architectury) throw new Error("Architectury's jar is not in Gradle's cache; build the project once first");
  fs.copyFileSync(architectury, path.join(mods, path.basename(architectury)));
  console.log("== mods: " + fs.readdirSync(mods).join(", "));

  if (process.argv.includes("--eula")) fs.writeFileSync(path.join(dir, "eula.txt"), "eula=true\n");
  if (!fs.existsSync(path.join(dir, "eula.txt"))) {
    throw new Error("Minecraft's EULA has not been accepted for this server: read it and pass --eula");
  }
  // Nobody is meant to join: a port of its own, no authentication to wait for, a small world.
  fs.writeFileSync(path.join(dir, "server.properties"),
    "server-port=25599\nonline-mode=false\nlevel-type=minecraft\\:flat\nspawn-protection=0\nmax-tick-time=120000\n");
  fs.mkdirSync(path.join(dir, "config"), { recursive: true });
  // What a modpack would ship: the switch on, in the game directory.
  fs.writeFileSync(path.join(dir, "config", "mc_puppet.json"),
    JSON.stringify({ enabled: true, server_port: PORT }, null, 2));
}

/** Starts the server, waits for it to be up, asks `then` what it sees, stops it, waits for the process. */
function run(name, then) {
  return new Promise((resolve, reject) => {
    const endpoint = path.join(dir, "mc_puppet", "endpoint-server.json");
    fs.rmSync(endpoint, { force: true });
    const args = process.platform === "win32" ? "win_args.txt" : "unix_args.txt";
    const child = spawn(java, ["-Xmx2G", `@libraries/net/neoforged/neoforge/${NEOFORGE}/${args}`, "nogui"],
      { cwd: dir, stdio: ["pipe", "pipe", "pipe"] });
    running = child;
    child.once("exit", () => { running = null; });
    let log = "";
    let asked = false;
    let stoppedAt = 0;
    const timer = setTimeout(() => { child.kill(); reject(new Error(`${name}: no "Done" after five minutes`)); }, 300000);
    const onText = (chunk) => {
      log += chunk;
      if (asked || !/Done \([0-9.]+s\)!/.test(log)) return;
      asked = true;
      // The bridge opens on SERVER_STARTED, which is this line; a moment for its file.
      setTimeout(async () => {
        let seen;
        try {
          seen = await then({ log: () => log, endpoint });
        } catch (failure) {
          seen = { failed: String(failure && failure.message || failure) };
        }
        stoppedAt = Date.now();
        child.stdin.write("stop\n");
        child.once("exit", (code) => {
          clearTimeout(timer);
          resolve({ name, seen, code, exitSeconds: Math.round((Date.now() - stoppedAt) / 1000),
            said: log.split(/\r?\n/).filter((line) => /mc_puppet|MC Puppet/.test(line)) });
        });
      }, 3000);
    };
    child.stdout.setEncoding("utf8").on("data", onText);
    child.stderr.setEncoding("utf8").on("data", onText);
    child.on("error", reject);
  });
}

function puppet(...words) {
  const out = spawnSync(process.execPath, [path.join(__dirname, "puppet", "puppet.js"), "--dir", dir, ...words],
    { encoding: "utf8" });
  return { status: out.status, text: ((out.stdout || "") + (out.stderr || "")).trim() };
}

// Consent is written to the real home directory, which is the thing being tried. A finally
// does not run for Ctrl+C, and consent left behind is a game directory that stays drivable.
let running = null;
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"]) {
  process.on(signal, () => {
    try { lib.setAllowed(dir, false); } catch (unwritable) { /* said below */ }
    if (running) running.kill();
    console.error(`\n${signal}: consent for ${dir} taken back, the server told to go`);
    process.exit(130);
  });
}

const checks = [];
function check(what, ok, detail) {
  checks.push({ what, ok });
  console.log(`${ok ? "  ok  " : "  FAIL"} ${what}${detail ? "\n         " + String(detail).slice(0, 300) : ""}`);
}

(async () => {
  install();
  furnish();
  lib.setAllowed(dir, false);
  try {
    console.log("\n== 1. the config says on, and nobody has allowed it");
    const refused = await run("refused", async ({ endpoint }) => ({ endpoint: fs.existsSync(endpoint), status: puppet("status") }));
    check("the mod loaded", refused.said.length > 0, refused.said[0]);
    check("it stayed off, and said why and what to run", refused.said.some((line) => /stayed OFF/.test(line) && /allow/.test(line)),
      refused.said.find((line) => /OFF/.test(line)));
    check("no endpoint file was written", refused.seen.endpoint === false);
    check("the tools find no game to talk to", refused.seen.status.status !== 0 || /No game/.test(refused.seen.status.text),
      refused.seen.status.text);
    check("the process ended by itself", refused.code === 0, `exit ${refused.code} after ${refused.exitSeconds}s`);

    console.log("\n== 2. after: mc-puppet allow " + dir);
    const allowed = puppet("allow", dir);
    check("allow wrote the consent file", allowed.status === 0 && fs.existsSync(lib.consentFile()), allowed.text);
    const open = await run("allowed", async ({ endpoint }) => ({
      endpoint: fs.existsSync(endpoint),
      file: fs.existsSync(endpoint) ? JSON.parse(fs.readFileSync(endpoint, "utf8")) : null,
      info: puppet("server", "info"),
      command: puppet("server", "command", JSON.stringify({ command: "time set day" })),
    }));
    check("the bridge opened", open.seen.endpoint === true, open.said.find((line) => /ON for the server/.test(line)));
    check("on 127.0.0.1, with a token and the protocol's version in the file",
      !!open.seen.file && open.seen.file.host === "127.0.0.1" && !!open.seen.file.token && open.seen.file.protocol != null,
      open.seen.file && JSON.stringify({ ...open.seen.file, token: "…" }));
    let info = null;
    try { info = JSON.parse(open.seen.info.text); } catch (unparsed) { /* reported below */ }
    check("info answers, and says this is not a development environment",
      !!info && info.development === false, open.seen.info.text);
    check("it names the game and the loader", !!info && info.minecraft === "1.21.1" && info.loader === "neoforge",
      info && `${info.minecraft} ${info.loader}`);
    check("a command runs", open.seen.command.status === 0 && /success/.test(open.seen.command.text), open.seen.command.text);
    check("the process ended by itself", open.code === 0, `exit ${open.code} after ${open.exitSeconds}s`);
    check("the endpoint file went with the server", !fs.existsSync(path.join(dir, "mc_puppet", "endpoint-server.json")));

    console.log("\n== 3. after: mc-puppet disallow " + dir);
    puppet("disallow", dir);
    const again = await run("disallowed", async ({ endpoint }) => ({ endpoint: fs.existsSync(endpoint) }));
    check("off again", again.seen.endpoint === false && again.said.some((line) => /stayed OFF/.test(line)));
  } finally {
    lib.setAllowed(dir, false);
  }
  const failed = checks.filter((each) => !each.ok);
  console.log(`\n${checks.length - failed.length} of ${checks.length} passed`);
  process.exit(failed.length ? 1 : 0);
})().catch((failure) => { console.error(failure.message || failure); lib.setAllowed(dir, false); process.exit(2); });
