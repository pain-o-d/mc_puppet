// The MCP server, spoken to the way an agent's host speaks to it: a child process, newline-delimited
// JSON-RPC on stdio, and a fake game bridge on a loopback port found through an endpoint file.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const net = require("node:net");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const { spawn } = require("node:child_process");

/** A bridge that answers every request through `handle(request) -> {result} | {error}`. */
async function bridge(t, handle) {
  const requests = [];
  const sockets = new Set();
  const server = net.createServer((socket) => {
    sockets.add(socket);
    socket.setEncoding("utf8");
    socket.on("close", () => sockets.delete(socket));
    socket.on("error", () => {});
    let buffer = "";
    socket.on("data", (data) => {
      buffer += data;
      let end;
      while ((end = buffer.indexOf("\n")) >= 0) {
        const request = JSON.parse(buffer.slice(0, end));
        buffer = buffer.slice(end + 1);
        requests.push(request);
        const answer = handle(request);
        const reply = answer.error !== undefined
          ? { id: request.id, ok: false, error: answer.error }
          : { id: request.id, ok: true, result: answer.result };
        socket.write(JSON.stringify(reply) + "\n");
      }
    });
  });
  await new Promise((resolve, reject) => { server.once("error", reject); server.listen(0, "127.0.0.1", resolve); });
  t.after(async () => {
    for (const socket of sockets) socket.destroy();
    await new Promise((resolve) => server.close(resolve));
  });
  return { requests, port: server.address().port };
}

/** A game directory holding an endpoint file for each side given, all pointing at one bridge. */
function gameDir(t, port, sides) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "puppet-mcp-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, "mc_puppet"));
  for (const side of sides) {
    fs.writeFileSync(path.join(dir, "mc_puppet", `endpoint-${side}.json`), JSON.stringify(
      { side, port, protocol: 1, pid: process.pid, token: "unit-test-token", dir, started: Date.now() }));
  }
  return dir;
}

/** mcp.js as a child process, with a way to send a line and wait for the next answer. */
function server(t, dir) {
  const child = spawn(process.execPath, [path.join(__dirname, "mcp.js")], {
    env: { ...process.env, MC_PUPPET_DIRS: dir },
    stdio: ["pipe", "pipe", "pipe"],
  });
  const answers = [];
  const waiting = [];
  let buffer = "";
  let stderr = "";
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  child.stdout.on("data", (chunk) => {
    buffer += chunk;
    let end;
    while ((end = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, end);
      buffer = buffer.slice(end + 1);
      if (!line.trim()) continue;
      const message = JSON.parse(line);
      const next = waiting.shift();
      if (next) next(message); else answers.push(message);
    }
  });
  const exited = new Promise((resolve) => child.once("exit", (code) => resolve(code)));
  t.after(() => { child.stdin.end(); child.kill(); });
  const next = () => new Promise((resolve, reject) => {
    if (answers.length) return resolve(answers.shift());
    const timer = setTimeout(() => reject(new Error("no answer from mcp.js within 5s; stderr: " + stderr)), 5000);
    waiting.push((message) => { clearTimeout(timer); resolve(message); });
  });
  return {
    exited,
    sendRaw: (line) => child.stdin.write(line + "\n"),
    async request(message) {
      child.stdin.write(JSON.stringify({ jsonrpc: "2.0", ...message }) + "\n");
      return next();
    },
    close: () => child.stdin.end(),
    get stderr() { return stderr; },
  };
}

const callTool = (mcp, id, name, args) => mcp.request({ id, method: "tools/call", params: { name, arguments: args } });

test("initialize answers with the protocol, the tools capability and this package's version", async (t) => {
  const mcp = server(t, os.tmpdir());
  const reply = await mcp.request({ id: 1, method: "initialize", params: {} });
  assert.equal(reply.jsonrpc, "2.0");
  assert.equal(reply.id, 1);
  assert.equal(reply.result.protocolVersion, "2024-11-05");
  assert.deepEqual(reply.result.capabilities, { tools: {} });
  assert.equal(reply.result.serverInfo.name, "mc-puppet");
  assert.equal(reply.result.serverInfo.version, require("./package.json").version);
});

test("tools/list names the five tools, each with an object schema", async (t) => {
  const mcp = server(t, os.tmpdir());
  const reply = await mcp.request({ id: 2, method: "tools/list" });
  const names = reply.result.tools.map((tool) => tool.name).sort();
  assert.deepEqual(names, ["puppet_call", "puppet_help", "puppet_run", "puppet_screenshot", "puppet_status"]);
  for (const tool of reply.result.tools) {
    assert.equal(tool.inputSchema.type, "object", tool.name);
    assert.ok(tool.description.length > 0, tool.name);
  }
});

test("a notification is not answered, and ping is", async (t) => {
  const mcp = server(t, os.tmpdir());
  mcp.sendRaw(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }));
  const reply = await mcp.request({ id: 3, method: "ping" });
  // The notification would have been the first answer had it been given one.
  assert.equal(reply.id, 3);
  assert.deepEqual(reply.result, {});
});

test("an unknown method is a JSON-RPC error, not a crash", async (t) => {
  const mcp = server(t, os.tmpdir());
  const reply = await mcp.request({ id: 4, method: "resources/list" });
  assert.equal(reply.id, 4);
  assert.equal(reply.error.code, -32601);
  assert.match(reply.error.message, /resources\/list/);
  assert.deepEqual((await mcp.request({ id: 5, method: "ping" })).result, {});
});

test("a malformed line is dropped and the server keeps answering", async (t) => {
  const mcp = server(t, os.tmpdir());
  mcp.sendRaw("{ this is not json");
  mcp.sendRaw("");
  const reply = await mcp.request({ id: 6, method: "ping" });
  assert.equal(reply.id, 6);
  assert.deepEqual(reply.result, {});
});

test("tools/call without params is a refusal the model can read, not a dead server", async (t) => {
  const mcp = server(t, os.tmpdir());
  const reply = await mcp.request({ id: 7, method: "tools/call" });
  assert.equal(reply.id, 7);
  assert.equal(reply.result.isError, true);
  assert.match(reply.result.content[0].text, /^Refused: /);
  assert.deepEqual((await mcp.request({ id: 8, method: "ping" })).result, {});
});

test("an unknown tool is refused in words", async (t) => {
  const mcp = server(t, os.tmpdir());
  const reply = await callTool(mcp, 9, "puppet_nothing", {});
  assert.equal(reply.result.isError, true);
  assert.equal(reply.result.content[0].text, "Refused: no such tool: puppet_nothing");
});

test("puppet_status with no game says where it looked", async (t) => {
  const empty = fs.mkdtempSync(path.join(os.tmpdir(), "puppet-mcp-empty-"));
  t.after(() => fs.rmSync(empty, { recursive: true, force: true }));
  const mcp = server(t, empty);
  const reply = await callTool(mcp, 10, "puppet_status", {});
  assert.equal(reply.result.isError, undefined);
  const body = reply.result.content[0].text;
  assert.match(body, /No game is listening/);
  assert.ok(body.includes(empty), body);
});

test("puppet_status, puppet_help and puppet_call go through to the game", async (t) => {
  const remote = await bridge(t, (request) => {
    if (request.op === "info") return { result: { version: "1.21.1", mods: ["a", "b", "c"], world: true } };
    if (request.op === "help") return { result: { ops: [{ op: "echo", args: "text", does: "Says it back." }] } };
    if (request.op === "echo") return { result: { said: request.args.text, nested: { deep: [1, 2, 3] } } };
    return { error: "unexpected " + request.op };
  });
  const mcp = server(t, gameDir(t, remote.port, ["server"]));

  const status = JSON.parse((await callTool(mcp, 1, "puppet_status", {})).result.content[0].text);
  assert.equal(status.length, 1);
  assert.equal(status[0].side, "server");
  assert.equal(status[0].port, remote.port);
  assert.equal(status[0].version, "1.21.1");
  assert.equal(status[0].mods, "3 mods", "a long mod list is counted, not listed");

  const help = (await callTool(mcp, 2, "puppet_help", { side: "server" })).result.content[0].text;
  assert.equal(help, "echo text\n    Says it back.");

  const called = await callTool(mcp, 3, "puppet_call", { side: "server", op: "echo", args: { text: "hi" } });
  assert.deepEqual(JSON.parse(called.result.content[0].text), { said: "hi", nested: { deep: [1, 2, 3] } });
  assert.equal(remote.requests.at(-1).token, "unit-test-token");

  const picked = await callTool(mcp, 4, "puppet_call",
    { side: "server", op: "echo", args: { text: "hi" }, path: "nested.deep[1]" });
  assert.equal(picked.result.content[0].text, "2");
});

test("a refusal by the game reaches the model verbatim", async (t) => {
  const words = "no screen is open: open one first, with the key or command that opens it";
  const remote = await bridge(t, () => ({ error: words }));
  const mcp = server(t, gameDir(t, remote.port, ["client"]));
  const reply = await callTool(mcp, 1, "puppet_call", { side: "client", op: "click_widget", args: { text: "Done" } });
  assert.equal(reply.id, 1);
  assert.equal(reply.result.isError, true);
  assert.equal(reply.result.content.length, 1);
  assert.equal(reply.result.content[0].type, "text");
  assert.equal(reply.result.content[0].text, "Refused: " + words);
});

test("asking for a side nobody is running is a refusal that says how to start one", async (t) => {
  const remote = await bridge(t, () => ({ result: {} }));
  const mcp = server(t, gameDir(t, remote.port, ["server"]));
  const reply = await callTool(mcp, 1, "puppet_call", { side: "client", op: "screen" });
  assert.equal(reply.result.isError, true);
  assert.match(reply.result.content[0].text, /no running game has its client bridge on/);
  assert.match(reply.result.content[0].text, /-Dmc_puppet\.enabled=true/);
});

test("an answer longer than the limit is cut with a note on how to ask for less", async (t) => {
  const remote = await bridge(t, () => ({ result: { filler: "x".repeat(20000) } }));
  const mcp = server(t, gameDir(t, remote.port, ["server"]));
  const reply = await callTool(mcp, 1, "puppet_call", { side: "server", op: "big" });
  const body = reply.result.content[0].text;
  assert.ok(body.length < 13000, "length " + body.length);
  assert.match(body, /… cut \d+ characters\. Ask for less/);
});

test("puppet_screenshot returns the file's path as text and its bytes as a PNG image block", async (t) => {
  const shot = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "puppet-shot-")), "one.png");
  t.after(() => fs.rmSync(path.dirname(shot), { recursive: true, force: true }));
  const bytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);
  fs.writeFileSync(shot, bytes);
  const remote = await bridge(t, (request) =>
    request.op === "screenshot" ? { result: { path: shot, width: 2, height: 2, name: request.args.name } }
      : { error: "unexpected " + request.op });
  const mcp = server(t, gameDir(t, remote.port, ["client"]));
  const reply = await callTool(mcp, 1, "puppet_screenshot", { name: "one" });
  const [first, second] = reply.result.content;
  assert.equal(reply.result.content.length, 2);
  assert.equal(first.type, "text");
  assert.equal(JSON.parse(first.text).path, shot);
  assert.equal(second.type, "image");
  assert.equal(second.mimeType, "image/png");
  assert.deepEqual(Buffer.from(second.data, "base64"), bytes);
  assert.equal(remote.requests.at(-1).args.name, "one");
});

test("puppet_run runs a scenario and reports a pass in one line and a failure in words", async (t) => {
  const remote = await bridge(t, (request) => request.op === "echo"
    ? { result: { text: request.args.text } } : { error: "no such operation: " + request.op });
  const mcp = server(t, gameDir(t, remote.port, ["server"]));
  const passing = await callTool(mcp, 1, "puppet_run", {
    name: "says it back",
    steps: [{ side: "server", op: "echo", args: { text: "hi" }, expect: [{ path: "text", equals: "hi" }] }],
  });
  assert.equal(passing.result.content[0].text, "PASS says it back: 1/1 steps");

  const refused = await callTool(mcp, 2, "puppet_run", {
    name: "refused",
    steps: [{ side: "server", op: "nothing" }],
  });
  const lines = refused.result.content[0].text.split("\n");
  assert.match(lines[0], /^FAIL refused: 0\/1 steps/);
  assert.ok(lines.some((line) => line.includes("no such operation: nothing")), lines.join("\n"));

  const bad = await callTool(mcp, 3, "puppet_run", { name: "no steps" });
  assert.equal(bad.result.isError, true);
  assert.match(bad.result.content[0].text, /give "steps"/);
});

test("closing stdin ends the server", async (t) => {
  const mcp = server(t, os.tmpdir());
  await mcp.request({ id: 1, method: "ping" });
  mcp.close();
  assert.equal(await mcp.exited, 0);
});
