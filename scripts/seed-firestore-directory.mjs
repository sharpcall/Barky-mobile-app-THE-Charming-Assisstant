/**
 * Seed Barky Firestore phone-book schema on database id "barkydefault".
 * Creates:
 *   _meta/schema  — field catalogue (docs only)
 *   devices/_example_template — sample device document with all fields
 *
 * Usage: node scripts/seed-firestore-directory.mjs
 */
import fs from "fs";
import path from "path";
import crypto from "crypto";
import https from "https";
import { fileURLToPath } from "url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const saPath = path.join(root, "secrets", "firebase-service-account.json");
const cfgPath = path.join(root, "secrets", "firebase-app-config.json");

const PROJECT = "barky-mobile-app---assisstan";
const DATABASE = "barkydefault";
const API_KEY =
  (fs.existsSync(cfgPath) && JSON.parse(fs.readFileSync(cfgPath, "utf8")).api_key) ||
  "AIzaSyAIiOipucaH02JyB7d4XWjkaQEw8yILjMU";

function b64url(buf) {
  return Buffer.from(buf)
    .toString("base64")
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function request(method, urlStr, { headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlStr);
    const req = https.request(
      {
        method,
        hostname: u.hostname,
        path: u.pathname + u.search,
        headers: {
          ...headers,
          ...(body
            ? {
                "Content-Type": headers["Content-Type"] || "application/json",
                "Content-Length": Buffer.byteLength(body),
              }
            : {}),
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          let json;
          try {
            json = text ? JSON.parse(text) : {};
          } catch {
            json = { raw: text };
          }
          resolve({ status: res.statusCode, json, text });
        });
      }
    );
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}

function str(v) {
  return { stringValue: String(v ?? "") };
}
function int(v) {
  return { integerValue: String(v ?? 0) };
}
function bool(v) {
  return { booleanValue: !!v };
}
function dbl(v) {
  return { doubleValue: Number(v) };
}

const FIELD_DOCS = {
  deviceId: "Stable Barky id (doc id matches)",
  phoneNumber: "Carrier number or NoPhoneNumber",
  displayName: "Manufacturer + model label",
  phoneModel: "Build.MODEL",
  manufacturer: "Build.MANUFACTURER",
  androidVersion: "Build.VERSION.RELEASE",
  androidSdk: "Build.VERSION.SDK_INT",
  androidId: "Settings Secure ANDROID_ID",
  localIp: "LAN IPv4 when on Wi-Fi",
  localUdpPort: "Walkie UDP port",
  localVideoPort: "Walkie TCP video port",
  publicIp: "STUN public IPv4",
  publicUdpPort: "STUN mapped UDP port",
  wifiSsid: "Optional SSID hint",
  onWifi: "Currently on Wi-Fi",
  lat: "Optional coarse latitude",
  lon: "Optional coarse longitude",
  appVersion: "Barky versionName",
  updatedAtMs: "Last directory write (ms epoch)",
  note: "Phone book only — media is always peer-to-peer IP to IP",
};

async function getAccessToken() {
  if (!fs.existsSync(saPath)) return null;
  const sa = JSON.parse(fs.readFileSync(saPath, "utf8"));
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(
    JSON.stringify({
      iss: sa.client_email,
      scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/cloud-platform",
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
  const res = await request("POST", "https://oauth2.googleapis.com/token", {
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  return res.json.access_token || null;
}

async function patchDoc(path, fields, { bearer, apiKey }) {
  const base = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents/${path}`;
  const url = apiKey ? `${base}?key=${apiKey}` : base;
  const headers = { "Content-Type": "application/json" };
  if (bearer) headers.Authorization = `Bearer ${bearer}`;
  return request("PATCH", url, {
    headers,
    body: JSON.stringify({ fields }),
  });
}

async function listDevices({ bearer, apiKey }) {
  const base = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents/devices?pageSize=5`;
  const url = apiKey ? `${base}&key=${apiKey}` : base;
  const headers = {};
  if (bearer) headers.Authorization = `Bearer ${bearer}`;
  return request("GET", url, { headers });
}

const now = Date.now();
const exampleFields = {
  deviceId: str("_example_template"),
  phoneNumber: str("NoPhoneNumber"),
  displayName: str("Example Template Phone"),
  phoneModel: str("ExampleModel"),
  manufacturer: str("ExampleMaker"),
  androidVersion: str("14"),
  androidSdk: int(34),
  androidId: str("example-android-id"),
  localIp: str("192.168.1.10"),
  localUdpPort: int(41234),
  localVideoPort: int(41235),
  publicIp: str("203.0.113.10"),
  publicUdpPort: int(41234),
  wifiSsid: str("ExampleWifi"),
  onWifi: bool(true),
  lat: dbl(0),
  lon: dbl(0),
  appVersion: str("5.17-device-meta"),
  updatedAtMs: int(now),
  note: str("TEMPLATE — safe to delete; real phones write their own deviceId docs"),
};

const schemaFields = {
  purpose: str("Barky device directory (phone book). Media never stored here."),
  collection: str("devices"),
  databaseId: str(DATABASE),
  projectId: str(PROJECT),
  fieldList: str(Object.keys(FIELD_DOCS).join(", ")),
  fieldsJson: str(JSON.stringify(FIELD_DOCS, null, 2)),
  updatedAtMs: int(now),
};

const bearer = await getAccessToken();
console.log("Auth:", bearer ? "service-account token" : "API key only");

const auth = { bearer, apiKey: API_KEY };

let r = await patchDoc("_meta/schema", schemaFields, auth);
console.log("PATCH _meta/schema", r.status, r.status < 300 ? "ok" : JSON.stringify(r.json).slice(0, 400));

r = await patchDoc("devices/_example_template", exampleFields, auth);
console.log(
  "PATCH devices/_example_template",
  r.status,
  r.status < 300 ? "ok" : JSON.stringify(r.json).slice(0, 400)
);

r = await listDevices(auth);
console.log("LIST devices", r.status, JSON.stringify(r.json).slice(0, 500));

if (r.status >= 200 && r.status < 300) {
  console.log("\nSUCCESS: barkydefault is ready. Collection devices has template fields.");
  process.exit(0);
}
console.error("\nFAILED to list devices — check Console rules / database id.");
process.exit(2);
