#!/usr/bin/env node
/**
 * Barky On-Demand Live Debug Monitor
 *
 * Reads real-time telemetry from Firestore collection `live_debug_stream`
 * emitted by phones during SEE, LISTEN, SCRN, and PTT calls.
 *
 * Usage:
 *   node scripts/live-monitor.mjs
 *   node scripts/live-monitor.mjs --timeout 180
 *   node scripts/live-monitor.mjs --once
 */

import fs from "fs";
import path from "path";
import crypto from "crypto";
import https from "https";
import { fileURLToPath } from "url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const saPath = path.join(root, "secrets", "firebase-service-account.json");
if (!fs.existsSync(saPath)) {
  console.error("Missing Firebase service account:", saPath);
  process.exit(1);
}

const sa = JSON.parse(fs.readFileSync(saPath, "utf8"));
const PROJECT = sa.project_id;
const DATABASE = "barkydefault";

// Command-line arguments
const args = process.argv.slice(2);
const onceMode = args.includes("--once");
const timeoutIdx = args.indexOf("--timeout");
const timeoutSec = timeoutIdx !== -1 && args[timeoutIdx + 1] ? Number(args[timeoutIdx + 1]) : 300;

function b64url(buf) {
  return Buffer.from(buf)
    .toString("base64")
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

let cachedToken = null;
let tokenExpiresAt = 0;

async function getAccessToken() {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && tokenExpiresAt > now + 60) {
    return cachedToken;
  }
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(
    JSON.stringify({
      iss: sa.client_email,
      scope: "https://www.googleapis.com/auth/datastore",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    })
  );
  const unsigned = `${header}.${claim}`;
  const sign = crypto.createSign("RSA-SHA256");
  sign.update(unsigned);
  sign.end();
  const jwt = `${unsigned}.${b64url(sign.sign(sa.private_key))}`;
  const body = new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion: jwt,
  }).toString();

  return new Promise((resolve, reject) => {
    const req = https.request(
      "https://oauth2.googleapis.com/token",
      {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
      },
      (res) => {
        let text = "";
        res.on("data", (c) => (text += c));
        res.on("end", () => {
          try {
            const data = JSON.parse(text);
            cachedToken = data.access_token;
            tokenExpiresAt = now + (data.expires_in || 3600);
            resolve(cachedToken);
          } catch (e) {
            reject(new Error("Token parse error: " + text));
          }
        });
      }
    );
    req.on("error", reject);
    req.write(body);
    req.end();
  });
}

async function request(method, urlPath, body = null) {
  const token = await getAccessToken();
  const url = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents${urlPath}`;
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = https.request(
      {
        method,
        hostname: u.hostname,
        path: u.pathname + u.search,
        headers: {
          Authorization: `Bearer ${token}`,
          ...(body ? { "Content-Type": "application/json" } : {}),
        },
      },
      (res) => {
        let text = "";
        res.on("data", (c) => (text += c));
        res.on("end", () => {
          try {
            resolve({ status: res.statusCode, data: text ? JSON.parse(text) : {} });
          } catch (e) {
            resolve({ status: res.statusCode, raw: text });
          }
        });
      }
    );
    req.on("error", reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

// ANSI colors for clean CLI readability
const C = {
  reset: "\x1b[0m",
  bold: "\x1b[1m",
  dim: "\x1b[2m",
  red: "\x1b[31m",
  green: "\x1b[32m",
  yellow: "\x1b[33m",
  blue: "\x1b[34m",
  magenta: "\x1b[35m",
  cyan: "\x1b[36m",
  white: "\x1b[37m",
};

function colorizeLog(line) {
  if (line.includes("TIMEOUT") || line.includes("FAIL") || line.includes("ERROR") || line.includes("broken")) {
    return `${C.bold}${C.red}${line}${C.reset}`;
  }
  if (line.includes("SUCCESS") || line.includes("RX SEE_ACK") || line.includes("First") || line.includes("accepted")) {
    return `${C.bold}${C.green}${line}${C.reset}`;
  }
  if (line.includes("SEE_REQ") || line.includes("SEE_SERVE") || line.includes("Targets") || line.includes("Armed watchdog")) {
    return `${C.yellow}${line}${C.reset}`;
  }
  if (line.includes("UI") || line.includes("clicked") || line.includes("switchCamera")) {
    return `${C.cyan}${line}${C.reset}`;
  }
  if (line.includes("VIDEO")) {
    return `${C.magenta}${line}${C.reset}`;
  }
  return `${C.white}${line}${C.reset}`;
}

async function activateLiveDebugInFirestore() {
  const metaBody = {
    fields: {
      enabled: { booleanValue: true },
      requestedAt: { integerValue: String(Date.now()) },
      expiresAt: { integerValue: String(Date.now() + (timeoutSec + 60) * 1000) },
    },
  };
  await request("PATCH", "/_meta/live_debug", metaBody);
}

// Track printed log lines per device to prevent repeating old events
const seenLines = new Map();

async function pollOnce() {
  const res = await request("GET", "/live_debug_stream");
  if (res.status !== 200 || !res.data || !res.data.documents) {
    return 0;
  }

  let newLinesCount = 0;
  for (const doc of res.data.documents) {
    const fields = doc.fields || {};
    const deviceId = fields.deviceId?.stringValue || doc.name.split("/").pop();
    const model = fields.deviceModel?.stringValue || "Unknown Phone";
    const net = fields.network?.stringValue || "";
    const active = fields.activeSession?.stringValue || "IDLE";
    const logsArr = fields.recentLogs?.arrayValue?.values || [];

    if (!seenLines.has(deviceId)) {
      seenLines.set(deviceId, new Set());
    }
    const deviceSeen = seenLines.get(deviceId);

    for (const item of logsArr) {
      const line = item.stringValue;
      if (!line || deviceSeen.has(line)) continue;
      deviceSeen.add(line);
      newLinesCount++;

      const prefix = `${C.bold}${C.blue}[${model}]${C.reset} ${C.dim}(${net} | ${active})${C.reset} `;
      console.log(prefix + colorizeLog(line));
    }
  }
  return newLinesCount;
}

async function main() {
  console.log(`\n${C.bold}======================================================================${C.reset}`);
  console.log(`${C.bold}${C.green}🟢 BARKY ON-DEMAND LIVE DEBUG MONITOR ACTIVE${C.reset}`);
  console.log(`${C.bold}======================================================================${C.reset}`);
  console.log(`Listening for real-time telemetry from Firestore live_debug_stream...`);
  console.log(`Timeout: ${timeoutSec}s. Press Ctrl+C to stop.`);
  console.log(`${C.dim}Perform the action (e.g. tap SEE, flip camera) on your phone now!${C.reset}\n`);

  try {
    await activateLiveDebugInFirestore();
  } catch (e) {
    console.warn("Could not set _meta/live_debug (continuing stream poll):", e.message);
  }

  if (onceMode) {
    await pollOnce();
    process.exit(0);
  }

  const startTime = Date.now();
  const interval = setInterval(async () => {
    if (Date.now() - startTime > timeoutSec * 1000) {
      console.log(`\n${C.yellow}Live monitor timed out after ${timeoutSec}s.${C.reset}`);
      clearInterval(interval);
      process.exit(0);
    }
    try {
      await pollOnce();
    } catch (e) {
      // Network glitch, keep polling
    }
  }, 1200);

  process.on("SIGINT", () => {
    console.log(`\n${C.yellow}Stopping live debug monitor.${C.reset}`);
    clearInterval(interval);
    process.exit(0);
  });
}

main().catch((err) => {
  console.error("Fatal monitor error:", err);
  process.exit(1);
});
