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
 * @returns {{side: string, host: string, port: number, token: string, pid: number, dir: string}[]}
 */
function discover(dirs) {
  const roots = (dirs && dirs.length ? dirs : (process.env.MC_PUPPET_DIRS || ".").split(/[;]/))
    .map((dir) => dir.trim()).filter(Boolean);
  const found = [];
  const seen = new Set();
  for (const root of roots) {
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
          if (pidAlive(endpoint.pid)) found.push({ ...endpoint, dir });
        } catch (unreadable) {
          // Half-written as the game starts; the next look finds it whole.
        }
      }
    }
  }
  // Newest first: after a restart the live game is the one to talk to.
  return found.sort((a, b) => (b.started || 0) - (a.started || 0));
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

  async side(side) {
    const live = this.endpoints().find((endpoint) => endpoint.side === side);
    if (!live) {
      throw new Error(`no running game has its ${side} bridge on. Start the game with `
        + `-Dmc_puppet.enabled=true (or "enabled": true in config/mc_puppet.json); `
        + `looked under: ${(this.dirs && this.dirs.length ? this.dirs : [process.env.MC_PUPPET_DIRS || "."]).join(", ")}`);
    }
    const held = this.connections.get(side);
    if (held && held.endpoint.token === live.token && held.socket) return held;
    if (held) held.close();
    const fresh = new Connection(live);
    this.connections.set(side, fresh);
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

module.exports = { discover, Connection, Puppet };
