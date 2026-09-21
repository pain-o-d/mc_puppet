/**
 * Talking to MC Puppet from Node: finding a running game, connecting, asking.
 *
 * No dependencies. The mod writes where it is listening to
 * <gameDir>/mc_puppet/endpoint-<side>.json, with a token made afresh each
 * start, so nothing here is configured by hand: point it at a game directory
 * (or let it look in the usual ones) and it finds the rest.
 */
const fs = require("fs");
const net = require("net");
const path = require("path");

/**
 * The versions of the protocol these tools speak. The mod says which it speaks in its endpoint
 * file. The two are installed separately and will not always be of an age, and a mismatch should
 * be reported as one, not as an operation that mysteriously is not there.
 */
const PROTOCOLS = [1];

/** What is wrong between these tools and a game's mod, in words, or null. */
function mismatch(endpoint) {
  const theirs = endpoint.protocol;
  if (theirs === undefined) {
    return "the MC Puppet mod in that game is older than these tools and does not say which protocol it speaks; "
      + "update the mod";
  }
  if (PROTOCOLS.includes(theirs)) return null;
  return theirs > Math.max(...PROTOCOLS)
    ? `the MC Puppet mod in that game speaks protocol ${theirs} and these tools only ${PROTOCOLS.join(", ")}; update the tools (npm i -g mc-puppet@latest)`
    : `the MC Puppet mod in that game speaks protocol ${theirs}, which these tools no longer do (${PROTOCOLS.join(", ")}); update the mod`;
}

/** Where consent to drive a game outside a development environment is kept: see the mod's Consent. */
function consentFile(home = require("os").homedir()) {
  return path.join(home, ".mc_puppet", "allowed.json");
}

function readAllowed(home) {
  try {
    const read = JSON.parse(fs.readFileSync(consentFile(home), "utf8"));
    return Array.isArray(read.allowed) ? read.allowed.filter((each) => typeof each === "string") : [];
  } catch (absent) {
    return [];
  }
}

const sameDir = (a, b) => path.resolve(a).replace(/\\/g, "/").toLowerCase() === path.resolve(b).replace(/\\/g, "/").toLowerCase();

/** Allows, or with allow false stops allowing, one game directory to be driven. Returns the list as it then is. */
function setAllowed(gameDir, allow, home) {
  const file = consentFile(home);
  const kept = readAllowed(home).filter((each) => !sameDir(each, gameDir));
  if (allow) kept.push(path.resolve(gameDir));
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify({
    _: "Game directories that programs on this machine may drive through MC Puppet, outside a development "
      + "environment. Written by: mc-puppet allow <gameDir>. Nothing you download should ever write here.",
    allowed: kept,
  }, null, 2) + "\n");
  return kept;
}

/** Where a game directory usually is, relative to a mod project's root. */
const USUAL_DIRS = [".", "run", "fabric/run", "neoforge/run", "forge/run", "common/run"];

function pidAlive(pid) {
  if (!pid) return true;
  try {
    process.kill(pid, 0);
    return true;
  } catch (failure) {
    // EPERM means it exists and is not ours to signal, which is alive.
    return failure.code === "EPERM";
  }
}

/**
 * Every live endpoint under the given game directories.
 *
 * @param {string[]} dirs game directories, or project roots to look under;
 *   defaults to MC_PUPPET_DIRS (separated by ; or the platform's delimiter)
 *   and then to the current directory
 *   A directory may be given a name, "second=E:/games/two": a scenario then
 *   says "client@second". One game directory per game: two games in one
 *   would write the same endpoint file, and the same log.
 * @returns {{side: string, host: string, port: number, token: string, pid: number, dir: string, game?: string}[]}
 */
function discover(dirs) {
  const roots = (dirs && dirs.length ? dirs : (process.env.MC_PUPPET_DIRS || ".").split(/[;]/))
    .map((dir) => dir.trim()).filter(Boolean).map(named);
  const found = [];
  const seen = new Set();
  for (const { game, root } of roots) {
    for (const usual of USUAL_DIRS) {
      const dir = path.resolve(root, usual);
      const folder = path.join(dir, "mc_puppet");
      let names;
      try {
        names = fs.readdirSync(folder);
      } catch (absent) {
        continue;
      }
      for (const name of names) {
        if (!/^endpoint-.*\.json$/.test(name)) continue;
        const file = path.join(folder, name);
        if (seen.has(file)) continue;
        seen.add(file);
        try {
          const endpoint = JSON.parse(fs.readFileSync(file, "utf8"));
          // A game that was killed leaves its file behind. The pid says so.
          if (pidAlive(endpoint.pid)) found.push({ ...endpoint, dir, ...(game ? { game } : {}) });
        } catch (unreadable) {
          // Half-written as the game starts; the next look finds it whole.
        }
      }
    }
  }
  // Newest first: after a restart the live game is the one to talk to.
  return found.sort((a, b) => (b.started || 0) - (a.started || 0));
}

/** "name=path" is a named game; a Windows path's own "C:" is not a name. */
function named(entry) {
  const match = /^([A-Za-z_][\w-]*)=(.+)$/.exec(entry);
  return match ? { game: match[1], root: match[2] } : { root: entry };
}

/** "client@second" is the client of the game called second; "client" is the newest client there is. */
function parseSide(side) {
  const at = String(side).indexOf("@");
  return at < 0 ? { side, game: undefined } : { side: side.slice(0, at), game: side.slice(at + 1) };
}

/** One connection to one side of one game. Requests are numbered, so they may overlap. */
class Connection {
  constructor(endpoint) {
    this.endpoint = endpoint;
    this.socket = null;
    this.nextId = 1;
    this.waiting = new Map();
    this.buffer = "";
  }

  connect() {
    if (this.socket) return Promise.resolve(this);
    return new Promise((resolve, reject) => {
      const socket = net.createConnection({ host: "127.0.0.1", port: this.endpoint.port }, () => {
        this.socket = socket;
        resolve(this);
      });
      socket.setNoDelay(true);
      socket.setEncoding("utf8");
      socket.on("data", (chunk) => this.onData(chunk));
      socket.on("error", (failure) => {
        reject(failure);
        this.failAll(failure);
      });
      socket.on("close", () => {
        this.socket = null;
        this.failAll(new Error("the game closed the connection"));
      });
    });
  }

  onData(chunk) {
    this.buffer += chunk;
    let end;
    while ((end = this.buffer.indexOf("\n")) >= 0) {
      const line = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + 1);
      if (!line.trim()) continue;
      let response;
      try {
        response = JSON.parse(line);
      } catch (garbled) {
        continue;
      }
      const entry = this.waiting.get(response.id);
      if (!entry) continue;
      this.waiting.delete(response.id);
      clearTimeout(entry.timer);
      if (response.ok) entry.resolve(response.result);
      else entry.reject(new Error(response.error || "refused"));
    }
  }

  failAll(failure) {
    for (const entry of this.waiting.values()) {
      clearTimeout(entry.timer);
      entry.reject(failure);
    }
    this.waiting.clear();
  }

  /**
   * One operation. Rejects with the game's own words when it refuses.
   *
   * @param {number} timeoutMs how long to wait for the answer; an operation
   *   that waits in the game should be given longer than its own timeout
   */
  async call(op, args = {}, timeoutMs = 60000) {
    await this.connect();
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiting.delete(id);
        reject(new Error(`no answer to ${op} within ${timeoutMs}ms`));
      }, timeoutMs);
      this.waiting.set(id, { resolve, reject, timer });
      this.socket.write(JSON.stringify({ id, token: this.endpoint.token, op, args }) + "\n");
    });
  }

  close() {
    if (this.socket) this.socket.destroy();
    this.socket = null;
  }
}

/** Connections by side, found on first use and found again if the game restarted. */
class Puppet {
  constructor(dirs) {
    this.dirs = dirs;
    this.connections = new Map();
  }

  endpoints() {
    return discover(this.dirs);
  }

  async side(sideName) {
    const { side, game } = parseSide(sideName);
    const live = this.endpoints().find((endpoint) => endpoint.side === side
      && (game === undefined || endpoint.game === game));
    if (!live && game !== undefined) {
      throw new Error(`no running game called "${game}" has its ${side} bridge on. Name a game directory with `
        + `--dir ${game}=<gameDir> (or ${game}=<gameDir> in MC_PUPPET_DIRS)`);
    }
    if (!live) {
      throw new Error(`no running game has its ${side} bridge on. Start the game with `
        + `-Dmc_puppet.enabled=true (or "enabled": true in config/mc_puppet.json); `
        + `looked under: ${(this.dirs && this.dirs.length ? this.dirs : [process.env.MC_PUPPET_DIRS || "."]).join(", ")}`);
    }
    const wrong = mismatch(live);
    if (wrong) throw new Error(wrong);
    const held = this.connections.get(sideName);
    if (held && held.endpoint.token === live.token && held.socket) return held;
    if (held) held.close();
    const fresh = new Connection(live);
    this.connections.set(sideName, fresh);
    return fresh.connect();
  }

  async call(side, op, args, timeoutMs) {
    const waits = args && args.timeout_ms ? Number(args.timeout_ms) + 10000 : undefined;
    return (await this.side(side)).call(op, args || {}, timeoutMs || waits);
  }

  close() {
    for (const connection of this.connections.values()) connection.close();
    this.connections.clear();
  }
}

module.exports = { discover, Connection, Puppet, parseSide, named, PROTOCOLS, mismatch, consentFile, readAllowed, setAllowed };
