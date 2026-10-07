/**
 * Lifecycle events, from the command line: the file <gameDir>/mc_puppet/events.jsonl, one JSON
 * object per line (the mod writes the in-game ones; the launcher writes process.started and
 * process.exited into the same file). See docs/backlog.md, task 1.
 *
 * Reading only: this file never opens a socket and never looks outside a run directory's own
 * mc_puppet folder. Writing is for the launcher's two events, through appendEvent.
 */
const fs = require("fs");
const path = require("path");
const lib = require("./lib");

const FILE_NAME = "events.jsonl";
/** Longest text kept in one launcher event, as in the mod. */
const MAX_TEXT = 300;

const eventsFile = (gameDir) => path.join(gameDir, "mc_puppet", FILE_NAME);

const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

/** "client.*", "*.crash" and "*" are patterns; anything else is one exact name. */
function matcher(pattern) {
  const text = String(pattern).trim();
  if (!text.includes("*")) return (name) => name === text;
  const expression = new RegExp("^" + text.split("*").map((part) => part.replace(/[.+?^${}()|[\]\\]/g, "\\$&")).join(".*") + "$");
  return (name) => expression.test(name);
}

/** "a,b" or ["a","b"] as a list of names. */
function names(value) {
  if (value === undefined || value === null || value === false) return [];
  const list = Array.isArray(value) ? value : String(value).split(",");
  return list.map((each) => String(each).trim()).filter(Boolean);
}

const anyOf = (patterns) => {
  const tests = names(patterns).map(matcher);
  return (name) => tests.some((test) => test(name));
};

/**
 * What ends a wait in failure when --fail-on is not given: a crash of any side, a connect that
 * failed, and a process that exited with a code other than 0.
 */
function defaultFailure(event) {
  const name = String(event.name);
  if (name.endsWith(".crash") || name === "client.connect_failed") return true;
  return name === "process.exited" && !!event.data && event.data.code !== 0;
}

function cut(value) {
  const text = String(value);
  return text.length > MAX_TEXT ? text.slice(0, MAX_TEXT) + "..." : text;
}

/**
 * Every place an events file may be: the given game directories (or MC_PUPPET_DIRS, or here),
 * each looked for in the usual run folders and under runs/<name>, whether or not a game is
 * still listening. Only files that exist.
 */
function eventFiles(dirs) {
  const roots = (dirs && dirs.length ? dirs : (process.env.MC_PUPPET_DIRS || ".").split(/[;]/))
    .map((dir) => dir.trim()).filter(Boolean).map(lib.named);
  const found = [];
  const seen = new Set();
  for (const { game, root } of roots) {
    for (const { dir } of lib.placesUnder(root, game)) {
      const file = eventsFile(dir);
      if (seen.has(file) || !fs.existsSync(file)) continue;
      seen.add(file);
      found.push(file);
    }
  }
  return found;
}

/** Every event line of a file, in order; a half-written or foreign line is skipped. */
function readEvents(file) {
  let text;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch (absent) {
    return [];
  }
  const events = [];
  for (const line of text.split(/\r?\n/)) {
    const event = parseLine(line);
    if (event) events.push({ event, line });
  }
  return events;
}

function parseLine(line) {
  if (!line.trim()) return null;
  try {
    const event = JSON.parse(line);
    return event && typeof event === "object" && typeof event.name === "string" ? event : null;
  } catch (unreadable) {
    return null;
  }
}

/**
 * Appends one event as the launcher: the next sequence number after the file's last, the time
 * now (or the given one), side "launcher". Returns the line written. Never throws: a launcher
 * that cannot write its record still has to do its job.
 */
function appendEvent(file, name, level, data, at) {
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    let sequence = 0;
    for (const { event } of readEvents(file)) {
      if (Number.isFinite(event.seq) && event.seq > sequence) sequence = event.seq;
    }
    const small = {};
    for (const [key, value] of Object.entries(data || {})) {
      if (value !== undefined) small[key] = typeof value === "string" ? cut(value) : value;
    }
    const line = JSON.stringify({ seq: sequence + 1, ts: (at || new Date()).toISOString(), side: "launcher", name, level, data: small });
    fs.appendFileSync(file, line + "\n", "utf8");
    return line;
  } catch (cannot) {
    return null;
  }
}

/** Empties the file as a run begins, so that a watcher reads this run only (as the mod does). */
function resetEvents(file) {
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, "");
  } catch (cannot) {
    // Without a file there is nothing to watch; the launch itself goes on.
  }
}

/**
 * The mod truncates the file as it starts, which wipes what the launcher wrote before the JVM
 * got that far. Whoever writes a later launcher event calls this first, so that process.started
 * is always in the record, with the time the process was really started.
 */
function ensureStarted(file, startedAt, data) {
  if (readEvents(file).some(({ event }) => event.name === "process.started")) return;
  appendEvent(file, "process.started", "info", data, startedAt);
}

/**
 * The newest crash report of a game directory that is newer than the start: a Minecraft crash
 * report, or the JVM's own hs_err file for a crash too deep for Minecraft to write one. Relative
 * to the game directory, with forward slashes; null when there is none.
 */
function newestCrashReport(gameDir, sinceMs) {
  let best = null;
  const look = (folder, pattern) => {
    let listed;
    try {
      listed = fs.readdirSync(path.join(gameDir, folder));
    } catch (absent) {
      return;
    }
    for (const name of listed) {
      if (!pattern.test(name)) continue;
      try {
        const modified = fs.statSync(path.join(gameDir, folder, name)).mtimeMs;
        if (modified >= sinceMs - 1000 && (!best || modified > best.modified)) {
          best = { modified, file: folder ? folder + "/" + name : name };
        }
      } catch (gone) {
        // Deleted between the listing and the look.
      }
    }
  };
  look("crash-reports", /^crash-.*\.txt$/);
  look("", /^hs_err_pid\d+\.log$/);
  return best ? best.file : null;
}

/** Follows one file as it grows. A file that shrinks was truncated by a new run: read from its start. */
class Tail {
  constructor(file) {
    this.file = file;
    this.offset = 0;
    this.partial = "";
    this.anchor = null; // the last bytes read, to notice a file rewritten and grown past our offset
  }

  /** True if the bytes just before our offset are not the ones we read: another run wrote this file. */
  rewritten() {
    if (!this.anchor || this.offset === 0) return false;
    let handle;
    try {
      handle = fs.openSync(this.file, "r");
      const probe = Buffer.alloc(this.anchor.length);
      const got = fs.readSync(handle, probe, 0, probe.length, this.offset - this.anchor.length);
      return got !== probe.length || !probe.equals(this.anchor);
    } catch (busy) {
      return false;
    } finally {
      if (handle !== undefined) fs.closeSync(handle);
    }
  }

  /** The events written since the last call, each with its raw line. */
  read() {
    let size;
    try {
      size = fs.statSync(this.file).size;
    } catch (absent) {
      return [];
    }
    if (size < this.offset || this.rewritten()) {
      this.offset = 0;
      this.partial = "";
      this.anchor = null;
    }
    if (size === this.offset) return [];
    const buffer = Buffer.alloc(size - this.offset);
    let got = 0;
    try {
      const handle = fs.openSync(this.file, "r");
      try {
        got = fs.readSync(handle, buffer, 0, buffer.length, this.offset);
      } finally {
        fs.closeSync(handle);
      }
    } catch (busy) {
      return [];
    }
    this.offset += got;
    this.anchor = buffer.subarray(Math.max(0, got - 64), got);
    const text = this.partial + buffer.toString("utf8", 0, got);
    const lines = text.split("\n");
    this.partial = lines.pop();
    const events = [];
    for (const line of lines) {
      const event = parseLine(line.replace(/\r$/, ""));
      if (event) events.push({ event, line: line.replace(/\r$/, ""), file: this.file });
    }
    return events;
  }
}

/** New events from every file there is now, files that appear later included, oldest first by time. */
class Watch {
  constructor(dirs, since) {
    this.dirs = dirs;
    this.since = since === undefined || since === null ? null : Number(since);
    this.tails = new Map();
  }

  poll() {
    for (const file of eventFiles(this.dirs)) if (!this.tails.has(file)) this.tails.set(file, new Tail(file));
    const fresh = [];
    for (const tail of this.tails.values()) {
      for (const each of tail.read()) {
        if (this.since !== null && !(each.event.seq > this.since)) continue;
        fresh.push(each);
      }
    }
    // One file is already in order; several are put in the order of the time they say.
    if (this.tails.size > 1) fresh.sort((a, b) => String(a.event.ts).localeCompare(String(b.event.ts)));
    return fresh;
  }
}

/**
 * Waits for an event. Everything already in the files counts, in order, then what arrives: the
 * first event that is wanted ends the wait in success, the first that is a failure (--fail-on, or
 * by default a crash, a failed connect, a non-zero process exit) in failure. A wanted name wins
 * over the same name as a failure.
 *
 * @returns {Promise<{code: 0|1|2, event: object|null, line: string|null, reason: string}>}
 *   code 0 wanted, 1 failure, 2 timeout
 */
async function waitEvent({ dirs, want, failOn, timeoutMs, since, pollMs = 200 }) {
  const wanted = anyOf(want);
  if (names(want).length === 0) throw new Error("wait needs an event to wait for: --event NAME[,NAME]");
  const explicit = names(failOn).length > 0;
  const failing = explicit ? anyOf(failOn) : null;
  const watch = new Watch(dirs, since);
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    for (const { event, line } of watch.poll()) {
      if (wanted(event.name)) return { code: 0, event, line, reason: `${event.name} arrived` };
      if (explicit ? failing(event.name) : defaultFailure(event)) {
        return { code: 1, event, line, reason: `${event.name} arrived, which ends the wait in failure` };
      }
    }
    if (Date.now() >= deadline) {
      const none = watch.tails.size === 0 ? "; no events file was found (is the bridge on, and --dir right?)" : "";
      return { code: 2, event: null, line: null, reason: `timed out after ${Math.round(timeoutMs / 1000)}s waiting for ${names(want).join(",")}${none}` };
    }
    await sleep(Math.min(pollMs, Math.max(1, deadline - Date.now())));
  }
}

/**
 * The lines of the files, filtered, and with follow true, the lines that come after, until stopped.
 * `emit` is called with each raw line.
 */
async function showEvents({ dirs, since, only, follow, emit, stop = () => false, pollMs = 200 }) {
  const wanted = names(only).length ? anyOf(only) : () => true;
  const watch = new Watch(dirs, since);
  for (;;) {
    for (const { event, line } of watch.poll()) if (wanted(event.name)) emit(line);
    if (!follow || stop()) return;
    await sleep(pollMs);
  }
}

module.exports = {
  eventsFile, eventFiles, readEvents, appendEvent, resetEvents, ensureStarted, newestCrashReport,
  Tail, Watch, waitEvent, showEvents, matcher, names, defaultFailure, MAX_TEXT,
};
