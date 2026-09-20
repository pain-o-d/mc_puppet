#!/usr/bin/env node
/**
 * MCP server for MC Puppet: lets an AI coding agent see and drive a running
 * Minecraft, to test the mod it is working on.
 *
 * Register it with the agent's host, pointing it at the project whose game
 * directories it should look in:
 *
 *   "mc-puppet": { "type": "stdio", "command": "npx",
 *                  "args": ["-y", "mc-puppet", "mcp"],
 *                  "env": { "MC_PUPPET_DIRS": "E:/MineMods/my_mod" } }
 *
 * or, from a checkout, "command": "node", "args": ["<path>/tools/puppet/mcp.js"].
 *
 * Five tools, on purpose. Every tool's description is paid for in every
 * conversation, and the game already knows its own operations: puppet_help
 * asks it. What the tools add is what a model needs and a socket does not
 * give: a whole scenario in one call, answers cut to size, and a screenshot
 * returned as an image it can look at.
 *
 * No dependencies; newline-delimited JSON-RPC on stdio.
 */
const fs = require("fs");
const { Puppet } = require("./lib");
const scenario = require("./scenario");

const puppet = new Puppet();

/** Answers longer than this are cut, with a note saying how to ask for less. */
const MAX_TEXT = 12000;

const SIDE = { type: "string", enum: ["client", "server"], description: "Which side of the game." };

const TOOLS = [
  {
    name: "puppet_status",
    description: "Which running Minecraft instances have MC Puppet listening (client and/or server), with "
      + "version, whether a world is loaded and the open screen. Call this first. If nothing is listening, "
      + "start the game with -Dmc_puppet.enabled=true.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "puppet_help",
    description: "The operations one side answers to, with their arguments, from the game itself. "
      + "Client: screen, click_widget, click_slot, select_trade, key, type, command, chat, use_entity, "
      + "use_block, player, count, entities, screenshot, create_world, open_world, leave_world, window, wait. "
      + "Server: command (with captured output), players, inventory, count, entities, entity, block, wait.",
    inputSchema: { type: "object", properties: { side: SIDE }, required: ["side"] },
  },
  {
    name: "puppet_call",
    description: "Runs one operation and returns its answer as JSON. The client's \"screen\" is how to see "
      + "a GUI: widgets with text and position, container slots, a merchant's offers. Prefer puppet_run "
      + "for more than two or three steps.",
    inputSchema: {
      type: "object",
      properties: {
        side: SIDE,
        op: { type: "string", description: "Operation name, from puppet_help." },
        args: { type: "object", description: "Its arguments." },
        path: { type: "string", description: "Return only this part of the answer, e.g. \"offers[0].buy\" or \"widgets[text~=Done]\"." },
      },
      required: ["side", "op"],
    },
  },
  {
    name: "puppet_run",
    description: "Runs a whole scenario in one call and reports which steps passed. Each step is "
      + "{side?, op, args?, expect?: [{path, equals|not|contains|matches|gt|gte|lt|lte|exists}], save?: name, "
      + "show?: path|true, expect_error?: text, optional?: bool, eventually?: true|ms, golden?: {file, "
      + "max_percent?, tolerance?, region?}, note?}. Paths: a.b, a[0], a[key=value], a[key~=part] (the key may "
      + "be a path), a# (count). \"${name.path}\" reuses a saved answer and \"${= a.count - 2 * b.price}\" "
      + "computes with them. setup and teardown run around steps, teardown always. Wait with the wait_until op "
      + "(any op, a path, an expectation, checked each tick), never a number of ticks. Fails if the game logged "
      + "an error meanwhile (allow_log: [regex]). Stops at the first failure unless keep_going. Give steps or file.",
    inputSchema: {
      type: "object",
      properties: {
        name: { type: "string" },
        steps: { type: "array", items: { type: "object" } },
        setup: { type: "array", items: { type: "object" } },
        teardown: { type: "array", items: { type: "object" } },
        allow_log: { type: "array", items: { type: "string" } },
        update_golden: { type: "boolean", description: "Write golden screenshots from this run instead of comparing." },
        file: { type: "string", description: "A scenario JSON file instead of inline steps." },
        keep_going: { type: "boolean" },
      },
    },
  },
  {
    name: "puppet_screenshot",
    description: "Takes a screenshot of the client and returns the image. For what data cannot say: overlap, "
      + "clipping, alignment. The game window must not be minimised. Use \"screen\" for anything that is data.",
    inputSchema: { type: "object", properties: { name: { type: "string", description: "File name, without extension." } } },
  },
];

function text(value) {
  let body = typeof value === "string" ? value : JSON.stringify(value, null, 1);
  if (body.length > MAX_TEXT) {
    body = body.slice(0, MAX_TEXT) + `\n… cut ${body.length - MAX_TEXT} characters. Ask for less: `
      + "puppet_call's \"path\", or the operation's own filters (screen: {slots:false}, entities: {limit}).";
  }
  return { content: [{ type: "text", text: body }] };
}

async function callTool(name, args) {
  if (name === "puppet_status") {
    const endpoints = puppet.endpoints();
    if (!endpoints.length) {
      return text("No game is listening. Start one with -Dmc_puppet.enabled=true, or set \"enabled\": true in "
        + "config/mc_puppet.json. Looked under: " + (process.env.MC_PUPPET_DIRS || process.cwd()));
    }
    const out = [];
    for (const endpoint of endpoints) {
      const info = await puppet.call(endpoint.side, "info").catch((failure) => ({ error: failure.message }));
      if (info.mods) info.mods = info.mods.length + " mods";
      out.push({ side: endpoint.side, port: endpoint.port, dir: endpoint.dir, ...info });
    }
    return text(out);
  }
  if (name === "puppet_help") {
    const help = await puppet.call(args.side, "help");
    return text(help.ops.map((each) => `${each.op} ${each.args}\n    ${each.does}`).join("\n"));
  }
  if (name === "puppet_call") {
    const result = await puppet.call(args.side, args.op, args.args || {});
    return text(args.path ? scenario.valueAt(result, args.path) ?? null : result);
  }
  if (name === "puppet_run") {
    const loaded = args.file ? JSON.parse(fs.readFileSync(args.file, "utf8"))
      : { name: args.name, steps: args.steps, setup: args.setup, teardown: args.teardown, allow_log: args.allow_log };
    if (!loaded || !Array.isArray(loaded.steps)) throw new Error("give \"steps\" (an array) or \"file\"");
    const report = await scenario.run(puppet, loaded, { keepGoing: Boolean(args.keep_going),
      updateGolden: Boolean(args.update_golden),
      baseDir: args.file ? require("path").dirname(require("path").resolve(args.file)) : process.cwd() });
    // What passed is one line. What failed, or was asked to be shown, is spelt out.
    const lines = [`${report.ok ? "PASS" : "FAIL"} ${report.name}: ${report.passed}/${report.of} steps`
      + (report.ran < report.of ? `, stopped after step ${report.ran}` : "")];
    for (const step of report.steps) {
      if (step.ok && step.shown === undefined && !step.skipped) continue;
      lines.push(`${step.ok ? (step.skipped ? "skip" : "ok") : "FAIL"} ${step.step}. ${step.side} ${step.op}`
        + (step.note ? ` - ${step.note}` : ""));
      for (const problem of step.problems || []) lines.push("    " + problem);
      if (step.shown !== undefined) lines.push("    " + JSON.stringify(step.shown));
    }
    for (const problem of report.log_problems || []) lines.push("LOG " + problem.line);
    return text(lines.join("\n"));
  }
  if (name === "puppet_screenshot") {
    const shot = await puppet.call("client", "screenshot", args.name ? { name: args.name } : {});
    const image = fs.readFileSync(shot.path).toString("base64");
    return {
      content: [
        { type: "text", text: JSON.stringify(shot) },
        { type: "image", data: image, mimeType: "image/png" },
      ],
    };
  }
  throw new Error("no such tool: " + name);
}

function send(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

let buffer = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buffer += chunk;
  let end;
  while ((end = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, end).trim();
    buffer = buffer.slice(end + 1);
    if (line) handle(line);
  }
});
process.stdin.on("end", () => {
  puppet.close();
  process.exit(0);
});

async function handle(line) {
  let request;
  try {
    request = JSON.parse(line);
  } catch (garbled) {
    return;
  }
  if (request.id === undefined) return; // A notification; nothing to answer.
  try {
    if (request.method === "initialize") {
      send({
        jsonrpc: "2.0", id: request.id,
        result: {
          protocolVersion: "2024-11-05",
          capabilities: { tools: {} },
          serverInfo: { name: "mc-puppet", version: require("./package.json").version },
        },
      });
    } else if (request.method === "tools/list") {
      send({ jsonrpc: "2.0", id: request.id, result: { tools: TOOLS } });
    } else if (request.method === "tools/call") {
      try {
        const result = await callTool(request.params.name, request.params.arguments || {});
        send({ jsonrpc: "2.0", id: request.id, result });
      } catch (failure) {
        // The game refusing is an answer the model should read, not a protocol error.
        send({ jsonrpc: "2.0", id: request.id, result: { content: [{ type: "text", text: "Refused: " + failure.message }], isError: true } });
      }
    } else if (request.method === "ping") {
      send({ jsonrpc: "2.0", id: request.id, result: {} });
    } else {
      send({ jsonrpc: "2.0", id: request.id, error: { code: -32601, message: "Method not found: " + request.method } });
    }
  } catch (failure) {
    send({ jsonrpc: "2.0", id: request.id, error: { code: -32603, message: failure.message } });
  }
}
