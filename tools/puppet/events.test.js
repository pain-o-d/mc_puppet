// Lifecycle events from the command line (docs/backlog.md, task 1D): the file format, the
// watcher, `puppet wait` and `puppet events` as exit codes and lines, and the launcher's supervisor.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const events = require("./events");
const scenario = require("./scenario");

const PUPPET = path.join(__dirname, "puppet.js");
const SUPERVISE = path.join(__dirname, "supervise.js");

/** A game directory in a temporary place, with an events file holding the given lines. */
function game(lines = []) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "puppet-events-"));
  const file = events.eventsFile(dir);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, lines.map((each) => (typeof each === "string" ? each : JSON.stringify(each))).join("\n") + (lines.length ? "\n" : ""));
  return { dir, file };
}
const event = (seq, name, data = {}, side = "server") =>
  ({ seq, ts: `2026-10-07T17:08:12.${String(seq).padStart(3, "0")}Z`, side, name, level: "info", data });
const run = (args, options = {}) => spawnSync(process.execPath, [PUPPET, ...args], { encoding: "utf8", timeout: 20000, ...options });
const seqs = (output) => output.trim().split("\n").map((line) => JSON.parse(line).seq);

test("a pattern with * is a wildcard, anything else an exact name; lists are comma-separated", () => {
  assert.ok(events.matcher("client.*")("client.connected"));
  assert.ok(!events.matcher("client.*")("server.ready"));
  assert.ok(events.matcher("*.crash")("server.crash"));
  assert.ok(events.matcher("*")("anything"));
  assert.ok(!events.matcher("server.ready")("server.ready.not"));
  assert.ok(events.matcher("a.b")("a.b") && !events.matcher("a.b")("axb"), "the dot is a dot");
  assert.deepEqual(events.names(" a, b ,,c"), ["a", "b", "c"]);
  assert.deepEqual(events.names(null), []);
});

test("by default a crash, a failed connect and a non-zero exit are failures; a clean exit is not", () => {
  assert.ok(events.defaultFailure({ name: "client.crash", data: {} }));
  assert.ok(events.defaultFailure({ name: "client.connect_failed", data: { reason: "x" } }));
  assert.ok(events.defaultFailure({ name: "process.exited", data: { code: 1 } }));
  assert.ok(!events.defaultFailure({ name: "process.exited", data: { code: 0 } }));
  assert.ok(!events.defaultFailure({ name: "client.disconnected", data: {} }));
});

test("readEvents skips a half-written or foreign line and keeps the order", () => {
  const { file } = game([event(1, "server.starting"), "{\"seq\":2,\"na", "not json", "{\"seq\":3}", event(4, "server.ready")]);
  assert.deepEqual(events.readEvents(file).map((each) => each.event.seq), [1, 4]);
  assert.deepEqual(events.readEvents(path.join(os.tmpdir(), "no-such-puppet-file.jsonl")), []);
});

test("appendEvent continues the sequence as the launcher, cuts long text, drops undefined", () => {
  const { file } = game([event(1, "server.starting"), event(7, "server.ready")]);
  const line = events.appendEvent(file, "process.exited", "error", { code: 3, report: "x".repeat(1000), gone: undefined }, new Date("2026-10-07T18:00:00Z"));
  const written = JSON.parse(line);
  assert.equal(written.seq, 8);
  assert.equal(written.side, "launcher");
  assert.equal(written.ts, "2026-10-07T18:00:00.000Z");
  assert.equal(written.data.code, 3);
  assert.ok(!("gone" in written.data));
  assert.ok(written.data.report.length <= events.MAX_TEXT + 3);
  assert.equal(events.readEvents(file).length, 3);
});

test("resetEvents empties the file; ensureStarted adds process.started once, with the given time", () => {
  const { file } = game([event(1, "server.starting")]);
  events.resetEvents(file);
  assert.equal(fs.readFileSync(file, "utf8"), "");
  const at = new Date("2026-10-07T17:00:00Z");
  events.ensureStarted(file, at, { pid: 5 });
  events.ensureStarted(file, at, { pid: 5 });
  const all = events.readEvents(file).map((each) => each.event);
  assert.equal(all.length, 1);
  assert.equal(all[0].name, "process.started");
  assert.equal(all[0].ts, at.toISOString());
});

test("newestCrashReport finds a report written since the start; not an old one", () => {
  const { dir } = game();
  const reports = path.join(dir, "crash-reports");
  fs.mkdirSync(reports);
  const old = path.join(reports, "crash-old-client.txt");
  fs.writeFileSync(old, "x");
  const past = new Date(Date.now() - 3600 * 1000);
  fs.utimesSync(old, past, past);
  const since = Date.now() - 1000;
  assert.equal(events.newestCrashReport(dir, since), null);
  fs.writeFileSync(path.join(reports, "crash-new-client.txt"), "x");
  assert.equal(events.newestCrashReport(dir, since), "crash-reports/crash-new-client.txt");
});

test("newestCrashReport also finds the JVM's own hs_err file", () => {
  const { dir } = game();
  fs.writeFileSync(path.join(dir, "hs_err_pid42.log"), "x");
  assert.equal(events.newestCrashReport(dir, Date.now() - 1000), "hs_err_pid42.log");
});

test("eventFiles finds the file in run/ under a project and in runs/<name>", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "puppet-events-root-"));
  const a = events.eventsFile(path.join(root, "fabric", "run"));
  const b = events.eventsFile(path.join(root, "runs", "bot1"));
  for (const file of [a, b]) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, "");
  }
  assert.deepEqual(events.eventFiles([root]).map((file) => path.resolve(file)).sort(), [a, b].map((file) => path.resolve(file)).sort());
  assert.deepEqual(events.eventFiles([path.join(root, "nothing")]), []);
});

test("waitEvent: a wanted event that is already in the file wins at once, with its line", async () => {
  const { dir } = game([event(1, "server.starting"), event(2, "server.ready", { port: 1 })]);
  const result = await events.waitEvent({ dirs: [dir], want: "server.ready", timeoutMs: 5000 });
  assert.equal(result.code, 0);
  assert.equal(result.event.seq, 2);
  assert.equal(JSON.parse(result.line).name, "server.ready");
});

test("waitEvent: a crash ends the wait in failure before the timeout; so does a non-zero exit", async () => {
  const crashed = game([event(1, "server.starting"), event(2, "server.crash", { cause: "boom" })]);
  const started = Date.now();
  const result = await events.waitEvent({ dirs: [crashed.dir], want: "server.ready", timeoutMs: 30000 });
  assert.equal(result.code, 1);
  assert.equal(result.event.name, "server.crash");
  assert.ok(Date.now() - started < 5000, "did not sleep to the timeout");
  // The exit is seen when it happens during the wait (a file already ending in it is a finished run).
  const killed = game([event(1, "process.started")]);
  setTimeout(() => fs.appendFileSync(killed.file, JSON.stringify(event(2, "process.exited", { code: 137 })) + "\n"), 100);
  assert.equal((await events.waitEvent({ dirs: [killed.dir], want: "server.ready", timeoutMs: 30000, pollMs: 20 })).code, 1);
});

test("waitEvent: a file that already ends in process.exited is a finished run and is ignored without --since", async () => {
  const done = () => game([event(1, "process.started"), event(2, "client.ready", {}, "client"), event(3, "process.exited", { code: 0 })]);
  const stale = done();
  const old = await events.waitEvent({ dirs: [stale.dir], want: "client.ready", timeoutMs: 300, pollMs: 20 });
  assert.equal(old.code, 2);
  assert.match(old.reason, /timed out/);
  // Even a non-zero exit of a finished run is not a failure of the wait that starts after it.
  const crashed = game([event(1, "process.started"), event(2, "process.exited", { code: 137 })]);
  assert.equal((await events.waitEvent({ dirs: [crashed.dir], want: "client.ready", timeoutMs: 300, pollMs: 20 })).code, 2);
  // With --since the old behaviour stays: what is in the file counts.
  const since = done();
  const seen = await events.waitEvent({ dirs: [since.dir], want: "client.ready", since: 0, timeoutMs: 5000 });
  assert.equal(seen.code, 0);
  assert.equal(seen.event.seq, 2);
});

test("waitEvent: a dedicated server's file ending in server.stopped is a finished run; a single-player one is not", async () => {
  const lines = (list) => list.map((each) => JSON.stringify(each)).join("\n") + "\n";
  const dedicated = () => game([event(1, "server.starting"), event(2, "server.ready"), event(3, "server.stopped")]);
  // The previous run's server.ready is not matched: exit 2.
  const stale = dedicated();
  const old = await events.waitEvent({ dirs: [stale.dir], want: "server.ready", timeoutMs: 300, pollMs: 20 });
  assert.equal(old.code, 2);
  // A fresh run replaces the file (seq restarts): its server.ready matches.
  const replaced = dedicated();
  setTimeout(() => fs.writeFileSync(replaced.file, lines([event(1, "server.starting")])), 100);
  setTimeout(() => fs.appendFileSync(replaced.file, lines([event(2, "server.ready")])), 250);
  const fresh = await events.waitEvent({ dirs: [replaced.dir], want: "server.ready", timeoutMs: 10000, pollMs: 20 });
  assert.equal(fresh.code, 0);
  assert.equal(fresh.event.seq, 2);
  // A single-player file has client events: ending in server.stopped is not "finished" for the new clause.
  const single = game([event(1, "process.started"), event(2, "client.ready", {}, "client"), event(3, "server.ready"), event(4, "server.stopped")]);
  const live = await events.waitEvent({ dirs: [single.dir], want: "server.ready", timeoutMs: 5000, pollMs: 20 });
  assert.equal(live.code, 0);
  assert.equal(live.event.seq, 3);
  // --since is unchanged: what is in the dedicated file counts.
  const since = dedicated();
  const seen = await events.waitEvent({ dirs: [since.dir], want: "server.ready", since: 0, timeoutMs: 5000 });
  assert.equal(seen.code, 0);
  assert.equal(seen.event.seq, 2);
});

test("puppet: a word without = is refused with the working form", () => {
  const { dir } = game([]);
  const result = run(["--dir", dir, "server", "command", "stop"]);
  assert.notEqual(result.status, 0);
  assert.match(result.stdout + result.stderr, /puppet server command command=stop/);
});

test("waitEvent: after a finished run, a newer run in the same file matches (seq restarts or a new process.started)", async () => {
  const restart = game([event(1, "process.started"), event(2, "client.ready", {}, "client"), event(3, "process.exited", { code: 0 })]);
  const lines = (list) => list.map((each) => JSON.stringify(each)).join("\n") + "\n";
  // The old run's lines are not matched; the new run's are, here after the file is replaced.
  setTimeout(() => fs.writeFileSync(restart.file, lines([event(1, "process.started"), event(2, "client.starting", {}, "client")])), 100);
  setTimeout(() => fs.appendFileSync(restart.file, lines([event(3, "client.ready", {}, "client")])), 250);
  const result = await events.waitEvent({ dirs: [restart.dir], want: "client.ready", timeoutMs: 10000, pollMs: 20 });
  assert.equal(result.code, 0);
  assert.equal(result.event.seq, 3);
  // Appended to the old file with a new process.started.
  const appended = game([event(1, "process.started"), event(2, "client.ready", {}, "client"), event(3, "process.exited", { code: 0 })]);
  setTimeout(() => fs.appendFileSync(appended.file, lines([event(4, "process.started"), event(5, "client.ready", {}, "client")])), 100);
  const next = await events.waitEvent({ dirs: [appended.dir], want: "client.ready", timeoutMs: 10000, pollMs: 20 });
  assert.equal(next.code, 0);
  assert.equal(next.event.seq, 5);
});

test("waitEvent: a clean exit is not a failure; --fail-on replaces the default list", async () => {
  const clean = game([event(1, "process.exited", { code: 0 })]);
  assert.equal((await events.waitEvent({ dirs: [clean.dir], want: "server.ready", timeoutMs: 300, pollMs: 20 })).code, 2);
  const crash = game([event(1, "server.crash")]);
  assert.equal((await events.waitEvent({ dirs: [crash.dir], want: "server.ready", failOn: "client.disconnected", timeoutMs: 300, pollMs: 20 })).code, 2);
  const left = game([event(1, "client.disconnected")]);
  assert.equal((await events.waitEvent({ dirs: [left.dir], want: "server.ready", failOn: "client.disconnected", timeoutMs: 300 })).code, 1);
});

test("waitEvent: --since ignores earlier events; an event that arrives later is seen; a timeout says so", async () => {
  const { dir, file } = game([event(1, "client.connected"), event(2, "client.disconnected")]);
  const none = await events.waitEvent({ dirs: [dir], want: "client.connected", since: 1, timeoutMs: 300, pollMs: 20 });
  assert.equal(none.code, 2);
  assert.match(none.reason, /timed out/);
  setTimeout(() => fs.appendFileSync(file, JSON.stringify(event(3, "client.connected")) + "\n"), 150);
  const later = await events.waitEvent({ dirs: [dir], want: "client.connected", since: 2, timeoutMs: 10000, pollMs: 20 });
  assert.equal(later.code, 0);
  assert.equal(later.event.seq, 3);
});

test("waitEvent: no events file is a timeout that says where to look; no event wanted is an error", async () => {
  const { dir } = game();
  fs.rmSync(path.join(dir, "mc_puppet"), { recursive: true });
  const result = await events.waitEvent({ dirs: [dir], want: "server.ready", timeoutMs: 200, pollMs: 20 });
  assert.equal(result.code, 2);
  assert.match(result.reason, /no events file/);
  await assert.rejects(events.waitEvent({ dirs: [dir], want: "", timeoutMs: 100 }), /--event/);
});

test("a file truncated by a new run is read again from its start", () => {
  const { dir, file } = game([event(1, "server.starting"), event(2, "server.ready"), event(3, "server.stopping")]);
  const seen = [];
  const watch = new events.Watch([dir]);
  for (const each of watch.poll()) seen.push(each.event.seq);
  fs.writeFileSync(file, JSON.stringify(event(1, "client.starting")) + "\n");
  for (const each of watch.poll()) seen.push(each.event.name);
  assert.deepEqual(seen, [1, 2, 3, "client.starting"]);
});

test("a file rewritten by a new run and grown past the old offset is read again from its start", () => {
  const { dir, file } = game([event(1, "server.starting"), event(2, "server.ready")]);
  const seen = [];
  const watch = new events.Watch([dir]);
  for (const each of watch.poll()) seen.push(each.event.name);
  // Between two polls: truncated, and refilled with more than the old size, the first line unchanged.
  const fresh = [event(1, "server.starting"), event(2, "player.joined", { n: "x".repeat(40) }), event(3, "server.ready"), event(4, "server.stopping")];
  fs.writeFileSync(file, fresh.map((each) => JSON.stringify(each)).join("\n") + "\n");
  for (const each of watch.poll()) seen.push(each.event.name);
  assert.deepEqual(seen, ["server.starting", "server.ready", "server.starting", "player.joined", "server.ready", "server.stopping"]);
});

test("a line still being written is not read until its newline arrives", () => {
  const { dir, file } = game([event(1, "server.starting")]);
  const watch = new events.Watch([dir]);
  assert.equal(watch.poll().length, 1);
  const whole = JSON.stringify(event(2, "server.ready"));
  fs.appendFileSync(file, whole.slice(0, 20));
  assert.equal(watch.poll().length, 0);
  fs.appendFileSync(file, whole.slice(20) + "\n");
  const fresh = watch.poll();
  assert.equal(fresh.length, 1);
  assert.equal(fresh[0].event.seq, 2);
});

test("showEvents prints the lines, filtered by name and by --since", async () => {
  const { dir } = game([event(1, "server.starting"), event(2, "server.ready"), event(3, "client.connected", {}, "client")]);
  const lines = [];
  await events.showEvents({ dirs: [dir], only: "server.*", follow: false, emit: (line) => lines.push(JSON.parse(line).seq) });
  assert.deepEqual(lines, [1, 2]);
  const later = [];
  await events.showEvents({ dirs: [dir], since: 2, follow: false, emit: (line) => later.push(JSON.parse(line).seq) });
  assert.deepEqual(later, [3]);
});

test("showEvents with follow prints what arrives until told to stop", async () => {
  const { dir, file } = game([event(1, "server.starting")]);
  const lines = [];
  let stop = false;
  setTimeout(() => fs.appendFileSync(file, JSON.stringify(event(2, "server.ready")) + "\n"), 100);
  setTimeout(() => { stop = true; }, 600);
  await events.showEvents({ dirs: [dir], follow: true, emit: (line) => lines.push(JSON.parse(line).seq), stop: () => stop, pollMs: 20 });
  assert.deepEqual(lines, [1, 2]);
});

test("puppet wait: exit 0 and the event's line when wanted; 1 on a crash; 2 on timeout", () => {
  const { dir } = game([event(1, "server.starting"), event(2, "server.ready")]);
  const ready = run(["--dir", dir, "wait", "--event", "server.ready", "--timeout", "10"]);
  assert.equal(ready.status, 0, ready.stderr);
  assert.equal(JSON.parse(ready.stdout.trim()).name, "server.ready");
  const crashed = game([event(1, "client.starting", {}, "client"), event(2, "client.crash", { cause: "x" }, "client")]);
  const failed = run(["--dir", crashed.dir, "wait", "--event", "client.connected", "--timeout", "30"]);
  assert.equal(failed.status, 1);
  assert.equal(JSON.parse(failed.stdout.trim()).name, "client.crash");
  const empty = game([event(1, "client.starting", {}, "client")]);
  const timedOut = run(["--dir", empty.dir, "wait", "--event", "client.connected", "--timeout", "0.3"]);
  assert.equal(timedOut.status, 2);
  assert.match(timedOut.stderr, /timed out/);
  assert.notEqual(run(["--dir", dir, "wait", "--timeout", "1"]).status, 0, "wait without --event is refused");
});

test("puppet wait --fail-on and --since", () => {
  const { dir } = game([event(1, "client.connected", {}, "client"), event(2, "client.disconnected", {}, "client")]);
  const failed = run(["--dir", dir, "wait", "--event", "client.reconnected", "--fail-on", "client.disconnected", "--timeout", "5"]);
  assert.equal(failed.status, 1);
  const since = run(["--dir", dir, "wait", "--event", "client.connected", "--since", "1", "--timeout", "0.3"]);
  assert.equal(since.status, 2);
});

test("puppet events prints one JSON line per event, filtered by --name and --since", () => {
  const { dir } = game([event(1, "server.starting"), event(2, "server.ready"), event(3, "client.connected", {}, "client")]);
  const all = run(["--dir", dir, "events"]);
  assert.equal(all.status, 0, all.stderr);
  assert.deepEqual(seqs(all.stdout), [1, 2, 3]);
  assert.deepEqual(seqs(run(["--dir", dir, "events", "--name", "client.connected,server.ready"]).stdout), [2, 3]);
  assert.deepEqual(seqs(run(["--dir", dir, "events", "--since", "2"]).stdout), [3]);
});

test("supervise.js records process.started and process.exited with the exit code", () => {
  const { dir, file } = game();
  events.resetEvents(file);
  const given = { bridge: true, file, gameDir: dir, cmd: process.execPath, args: ["-e", "process.exit(3)"], cwd: dir, task: ":fabric:runClient", name: "bot1" };
  const result = spawnSync(process.execPath, [SUPERVISE, JSON.stringify(given)], { encoding: "utf8", timeout: 20000 });
  assert.equal(result.status, 3);
  const all = events.readEvents(file).map((each) => each.event);
  assert.deepEqual(all.map((each) => each.name), ["process.started", "process.exited"]);
  assert.deepEqual(all.map((each) => each.seq), [1, 2]);
  assert.equal(all[0].side, "launcher");
  assert.equal(all[1].level, "error");
  assert.equal(all[1].data.code, 3);
  assert.equal(all[1].data.name, "bot1");
  assert.equal(all[1].data.task, ":fabric:runClient");
  assert.ok(!("report" in all[1].data));
});

test("supervise.js: a clean exit is info, a crash report written during the run is named, and a mod that wiped the file does not lose process.started", () => {
  const { dir, file } = game();
  events.resetEvents(file);
  // The child plays the game: truncates the events file as the mod does, writes a crash report, leaves.
  const script = [
    "const fs=require('fs'),path=require('path');",
    `fs.writeFileSync(${JSON.stringify(file)},'');`,
    `fs.mkdirSync(path.join(${JSON.stringify(dir)},'crash-reports'),{recursive:true});`,
    `fs.writeFileSync(path.join(${JSON.stringify(dir)},'crash-reports','crash-x-client.txt'),'x');`,
    "process.exit(0)",
  ].join("");
  const given = { bridge: true, file, gameDir: dir, cmd: process.execPath, args: ["-e", script], cwd: dir, task: "t" };
  assert.equal(spawnSync(process.execPath, [SUPERVISE, JSON.stringify(given)], { encoding: "utf8", timeout: 20000 }).status, 0);
  const all = events.readEvents(file).map((each) => each.event);
  assert.deepEqual(all.map((each) => each.name), ["process.started", "process.exited"]);
  assert.equal(all[1].level, "info");
  assert.equal(all[1].data.code, 0);
  assert.equal(all[1].data.report, "crash-reports/crash-x-client.txt");
});

test("supervise.js writes nothing for a run whose bridge is not on", () => {
  const { dir, file } = game();
  fs.rmSync(file);
  const given = { file, gameDir: dir, cmd: process.execPath, args: ["-e", "process.exit(2)"], cwd: dir, task: "t" };
  assert.equal(spawnSync(process.execPath, [SUPERVISE, JSON.stringify(given)], { encoding: "utf8", timeout: 20000 }).status, 2);
  assert.equal(fs.existsSync(file), false);
});

test("supervise.js: a command that cannot start is recorded as an exit with its error", () => {
  const { dir, file } = game();
  const given = { bridge: true, file, gameDir: dir, cmd: path.join(dir, "no-such-command"), args: [], cwd: dir, task: "t" };
  const result = spawnSync(process.execPath, [SUPERVISE, JSON.stringify(given)], { encoding: "utf8", timeout: 20000 });
  assert.equal(result.status, 1);
  const all = events.readEvents(file).map((each) => each.event);
  const last = all[all.length - 1];
  assert.equal(last.name, "process.exited");
  assert.equal(last.data.code, 1);
  assert.ok(last.data.error);
});

test("a scenario's wait_event substitutes saved values in since, so it waits for what came after", async () => {
  const { dir } = game([event(1, "client.connected", {}, "client"), event(2, "client.disconnected", {}, "client")]);
  const puppet = { dirs: [dir], endpoints: () => [], call: async () => { throw new Error("no calls expected"); } };
  const report = await scenario.run(puppet, { name: "s", steps: [
    { wait_event: "client.connected", save: "first", timeout: 5 },
    { wait_event: "client.connected", since: "${first.seq}", timeout: 0.3 },
  ] }, { watchLog: false });
  assert.equal(report.steps[0].ok, true, JSON.stringify(report));
  assert.equal(report.steps[1].ok, false);
  assert.match(report.steps[1].problems[0], /timed out/);
});

test("a scenario's wait_event step passes on a wanted event, fails at once on a crash, fails on timeout", async () => {
  const puppet = { dirs: [], endpoints: () => [], call: async () => { throw new Error("no calls expected"); } };
  const play = async (lines, step) => {
    const { dir } = game(lines);
    puppet.dirs = [dir];
    return scenario.run(puppet, { name: "w", steps: [step] }, { watchLog: false });
  };
  const good = await play([event(1, "client.connected", {}, "client")], { wait_event: "client.connected", timeout: 5 });
  assert.equal(good.ok, true, JSON.stringify(good));
  assert.equal(good.steps[0].op, "wait_event");
  const crash = await play([event(1, "client.crash", { cause: "x" }, "client")], { wait_event: "client.connected", timeout: 30 });
  assert.equal(crash.ok, false);
  assert.match(crash.steps[0].problems[0], /client\.crash/);
  assert.ok(crash.steps[0].ms < 5000);
  const late = await play([event(1, "client.starting", {}, "client")], { wait_event: "client.connected", timeout: 0.3 });
  assert.equal(late.ok, false);
  assert.match(late.steps[0].problems[0], /timed out/);
});
