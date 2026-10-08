#!/usr/bin/env node
/**
 * Runs the Gradle command that starts a dev game, and records in the run directory's events file
 * what only the launcher can see: process.started when it begins, process.exited when it ends,
 * with the exit code and the newest crash report written since the start. A game that dies of a
 * native crash, a kill, or a failure before the mod loads leaves no in-game hook to say so.
 *
 * Started by launch.js, detached, so that it outlives the "puppet launch" that began it (which
 * returns as soon as the game's bridge is open). Not for people: one argument, a JSON object
 * {bridge, file, gameDir, cmd, args, cwd, task, name}. Its output is the Gradle log, as before.
 *
 * Only for a run whose bridge is on ("bridge": true): events exist where the bridge does, as the
 * game's own bus decides, so without it this runs the command and writes nothing.
 *
 * process.started goes in before the game's JVM exists and the game starts the file afresh; the
 * game, told by -Dmc_puppet.launched=true (launch.init.gradle), keeps that line (core Events). The
 * write at exit is for a game that never got that far, or a mod that emptied the file.
 */
const { spawn } = require("child_process");
const events = require("./events");

function main() {
  const given = JSON.parse(process.argv[2]);
  const startedAt = new Date();
  const identity = { task: given.task, ...(given.name ? { name: given.name } : {}) };
  let child;
  let finished = false;

  const finish = (code, signal, error) => {
    if (finished) return;
    finished = true;
    const exitCode = code === null || code === undefined ? 1 : code;
    if (given.bridge !== true) process.exit(exitCode);
    events.ensureStarted(given.file, startedAt, { ...identity, pid: child && child.pid });
    const report = events.newestCrashReport(given.gameDir, startedAt.getTime());
    events.appendEvent(given.file, "process.exited", exitCode === 0 ? "info" : "error", {
      ...identity,
      code: exitCode,
      ...(signal ? { signal } : {}),
      ...(error ? { error } : {}),
      ...(report ? { report } : {}),
    });
    process.exit(exitCode);
  };

  try {
    child = spawn(given.cmd, given.args, { cwd: given.cwd, stdio: "inherit", windowsHide: true });
  } catch (failure) {
    finish(1, null, failure.message);
    return;
  }
  child.on("error", (failure) => finish(1, null, failure.message));
  child.on("exit", (code, signal) => finish(code, signal, null));
  if (given.bridge === true) events.appendEvent(given.file, "process.started", "info", { ...identity, pid: child.pid });
}

main();
