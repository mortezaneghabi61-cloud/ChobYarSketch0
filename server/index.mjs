import http from "node:http";
import OpenAI from "openai";

const client = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });
const port = Number(process.env.PORT || 8787);
const model = process.env.OPENAI_MODEL || "gpt-5-mini";

const instructions = `
You are ChobYar AI, the Persian CAD copilot inside an Android woodworking CAD app.
Reply in Persian unless the user explicitly asks for another language.
You can either answer a question or propose CAD commands that the Android app can execute.
Never invent unsupported commands. Units are millimeters.

Supported commands:
LINE x1 y1 x2 y2
RECT x y width height
CIRCLE cx cy radius
ARC cx cy radius startAngle sweepAngle
POINT x y
POLYGON sides cx cy radius
MOVE dx dy
COPY dx dy
OFFSET distance
ROTATE degrees
SCALE factor
MIRROR X axisValue
MIRROR Y axisValue
ARRAY count dx dy
LENGTH value
SIZE width height
RADIUS value
DIAMETER value
GUIDE X value
GUIDE Y value
DIST x1 y1 x2 y2
EXTRUDE height
DELETE
UNDO
FIT
AXIS
GRID
GUIDES
DIMENSIONS
SNAP
ORTHO
SELECT
DIM
FREE

Rules:
- If the user is only asking for advice/explanation, return no commands.
- If the user asks to create or modify geometry and the intent is clear, return the minimal safe command sequence.
- Commands act on the currently selected entity when applicable.
- For a circle, CIRCLE uses radius, not diameter.
- POLYGON supports 3 to 64 sides.
- Do not use 3D solid commands that are not implemented.
- Keep message concise and useful.
`;

const schema = {
  type: "object",
  properties: {
    message: { type: "string" },
    commands: {
      type: "array",
      items: { type: "string" },
      maxItems: 24
    }
  },
  required: ["message", "commands"],
  additionalProperties: false
};

function send(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store"
  });
  res.end(body);
}

async function readJson(req) {
  let body = "";
  for await (const chunk of req) {
    body += chunk;
    if (body.length > 100_000) throw new Error("request_too_large");
  }
  return body ? JSON.parse(body) : {};
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    return send(res, 200, { ok: true, model });
  }

  if (req.method !== "POST" || req.url !== "/api/chobyar-ai") {
    return send(res, 404, { error: "not_found" });
  }

  if (!process.env.OPENAI_API_KEY) {
    return send(res, 500, { error: "OPENAI_API_KEY روی سرور تنظیم نشده" });
  }

  try {
    const body = await readJson(req);
    const prompt = String(body.prompt || "").trim();
    const selected = String(body.selected || "").trim();

    if (!prompt) return send(res, 400, { error: "prompt خالی است" });

    const input = [
      `وضعیت شیء انتخاب‌شده در چوب‌یار: ${selected || "هیچ شیئی انتخاب نشده"}`,
      `درخواست کاربر: ${prompt}`
    ].join("\n\n");

    const response = await client.responses.create({
      model,
      instructions,
      input,
      store: false,
      text: {
        format: {
          type: "json_schema",
          name: "chobyar_cad_reply",
          strict: true,
          schema
        }
      }
    });

    const parsed = JSON.parse(response.output_text || "{}");
    return send(res, 200, {
      message: String(parsed.message || ""),
      commands: Array.isArray(parsed.commands) ? parsed.commands : []
    });
  } catch (error) {
    console.error(error);
    return send(res, 500, {
      error: error?.message || "خطای ناشناخته در AI"
    });
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`ChobYar AI server listening on :${port} using ${model}`);
});
