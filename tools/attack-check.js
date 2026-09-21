#!/usr/bin/env node
/*
 * Tries a running bridge the way something hostile on the same machine would: a browser's request, a wrong
 * token, no token, silence, a world name that walks out of saves/, a line break in an operation's name,
 * a step hidden in a batch. Nineteen checks, from the review before the first release.
 *
 *   node tools/attack-check.js <gameDir>     against a client whose bridge is on, with a world loaded
 *
 * It changes nothing in the game: every operation it gets through is info or help.
 */
const net = require("net");
const fs = require("fs");
const path = require("path");
const dir = process.argv[2];
const endpoint = JSON.parse(fs.readFileSync(path.join(dir, "mc_puppet", "endpoint-client.json"), "utf8"));
const auditFile = path.join(dir, "mc_puppet", "audit-client.log");

function talk(lines, { waitMs = 1500, keepOpen = false } = {}) {
  return new Promise((resolve) => {
    const socket = net.connect(endpoint.port, "127.0.0.1");
    let got = ""; let closedAfter = null; const started = Date.now();
    socket.setEncoding("utf8");
    socket.on("data", (chunk) => { got += chunk; });
    socket.on("close", () => { closedAfter = Date.now() - started; if (keepOpen) resolve({ got, closedAfter }); });
    socket.on("error", () => {});
    socket.on("connect", () => { for (const line of lines) socket.write(line); });
    if (!keepOpen) setTimeout(() => { const open = closedAfter === null; socket.destroy(); resolve({ got, closedAfter, open }); }, waitMs);
  });
}
const ask = (op, args, id = 1) => JSON.stringify({ id, token: endpoint.token, op, args: args || {} }) + "\n";
const results = [];
const check = (what, ok, detail) => { results.push(ok); console.log(`${ok ? "  ok  " : "  FAIL"} ${what}${detail ? "\n         " + String(detail).replace(/\s+/g, " ").slice(0, 220) : ""}`); };

(async () => {
  const before = fs.readFileSync(auditFile, "utf8").split("\n").length;

  let r = await talk(["POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\n\r\n",
    JSON.stringify({ id: 1, token: "guess", op: "quit" }) + "\n"]);
  check("a browser's request is dropped at its first line, before its body is ever read", r.closedAfter !== null && !/"ok":true/.test(r.got), r.got);

  r = await talk([JSON.stringify({ id: 1, token: "0".repeat(64), op: "info" }) + "\n", ask("info")]);
  check("a wrong token ends the connection: the right one after it is never heard", r.closedAfter !== null && !/"ok":true/.test(r.got), r.got);

  r = await talk([JSON.stringify({ id: 1, op: "info" }) + "\n"]);
  check("and so does none at all", r.closedAfter !== null && !/"ok":true/.test(r.got), r.got);

  console.log("       (waiting out the ten seconds a silent connection is given)");
  r = await talk([], { keepOpen: true });
  check("a connection that says nothing is dropped after about ten seconds", r.closedAfter > 8000 && r.closedAfter < 15000, r.closedAfter + " ms");

  r = await talk([ask("info"), "this is not json\n", ask("info", {}, 2)], { waitMs: 2500 });
  check("one that has shown the token may mistype a line and go on", (r.got.match(/"ok":true/g) || []).length === 2 && r.open, r.got.slice(0, 120));

  console.log("       (holding a proven connection quiet for twelve seconds)");
  r = await new Promise((resolve) => {
    const socket = net.connect(endpoint.port, "127.0.0.1"); let got = ""; let closed = false;
    socket.setEncoding("utf8"); socket.on("data", (c) => { got += c; }); socket.on("close", () => { closed = true; }); socket.on("error", () => {});
    socket.on("connect", () => { socket.write(ask("info")); setTimeout(() => { socket.write(ask("info", {}, 2)); setTimeout(() => { socket.destroy(); resolve({ got, closed }); }, 1500); }, 12000); });
  });
  check("a harness that is quiet between steps is not timed out", (r.got.match(/"ok":true/g) || []).length === 2, r.got.slice(0, 80));

  for (const name of ["../escaped", "..", "a/b", "a\\b", "C:evil", "con|x", " lead", "trail.", ""]) {
    r = await talk([ask("open_world", { name })]);
    check(`open_world ${JSON.stringify(name)} is refused`, /"ok":false/.test(r.got) && /one folder's name|name/.test(r.got), r.got);
  }

  r = await talk([ask("help\n2026-01-01T00:00:00Z quit {} -> ok (0ms)", {})]);
  r = await talk([ask("batch", { steps: [{ op: "help", args: { pad: "x".repeat(600) } }, { op: "info", args: { marker: "seen-inside-a-batch" } }] })], { waitMs: 2500 });
  r = await talk([ask("wait_until", { op: "info", args: { marker: "polled" }, path: "side", equals: "client", timeout_ms: 2000 })], { waitMs: 2500 });
  await new Promise((done) => setTimeout(done, 500));
  const audit = fs.readFileSync(auditFile, "utf8").split("\n").slice(before - 1).filter(Boolean);
  check("a line break in an operation's name forged no line", !audit.some((line) => /^2026-01-01T00:00:00Z quit/.test(line)), audit.find((l) => /help/.test(l)));
  check("a batch's hidden step has a line of its own", audit.some((line) => /batch> info/.test(line) && /seen-inside-a-batch/.test(line)), audit.find((l) => /batch> info/.test(l)));
  check("and so has what a wait_until polls", audit.some((line) => /wait_until> info/.test(line)), audit.find((l) => /wait_until>/.test(l)));
  check("the token is nowhere in the audit log", !fs.readFileSync(auditFile, "utf8").includes(endpoint.token));

  console.log(`\n${results.filter(Boolean).length} of ${results.length} passed`);
  process.exit(results.every(Boolean) ? 0 : 1);
})();
