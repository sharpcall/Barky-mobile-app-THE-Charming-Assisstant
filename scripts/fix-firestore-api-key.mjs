/**
 * Ensure an unrestricted API key works for Firestore REST (Barky phone book).
 * Lists devices with SA; if API key 403, creates a new browser-style key.
 */
import fs from "fs";
import path from "path";
import crypto from "crypto";
import https from "https";
import { fileURLToPath } from "url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sa = JSON.parse(
  fs.readFileSync(path.join(root, "secrets", "firebase-service-account.json"), "utf8")
);
const PROJECT = sa.project_id;
const DATABASE = "barkydefault";

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

async function token() {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(
    JSON.stringify({
      iss: sa.client_email,
      scope: "https://www.googleapis.com/auth/cloud-platform",
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
  if (!res.json.access_token) throw new Error(JSON.stringify(res.json));
  return res.json.access_token;
}

const access = await token();
const authH = { Authorization: `Bearer ${access}` };

// Project number
const proj = await request(
  "GET",
  `https://cloudresourcemanager.googleapis.com/v1/projects/${PROJECT}`,
  { headers: authH }
);
const projectNumber = proj.json.projectNumber;
console.log("projectNumber", projectNumber);

// List with SA
const listSa = await request(
  "GET",
  `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents/devices?pageSize=10`,
  { headers: authH }
);
console.log("list SA", listSa.status, "docs", (listSa.json.documents || []).length);
for (const doc of listSa.json.documents || []) {
  const id = doc.name.split("/").pop();
  const f = doc.fields || {};
  const s = (n) => f[n]?.stringValue || f[n]?.integerValue || "";
  console.log(" ", id, s("displayName"), s("localIp"), s("publicIp"), s("appVersion"), s("updatedAtMs"));
}

// Try existing key from config
let apiKey = "AIzaSyAIiOipucaH02JyB7d4XWjkaQEw8yILjMU";
let listKey = await request(
  "GET",
  `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents/devices?pageSize=5&key=${apiKey}`
);
console.log("list API key (existing)", listKey.status, listKey.text.slice(0, 200));

if (listKey.status !== 200) {
  // Enable apikeys + create unrestricted key for Firestore
  await request(
    "POST",
    `https://serviceusage.googleapis.com/v1/projects/${PROJECT}/services/apikeys.googleapis.com:enable`,
    { headers: authH, body: "{}" }
  );
  await new Promise((r) => setTimeout(r, 3000));
  const created = await request(
    "POST",
    `https://apikeys.googleapis.com/v2/projects/${projectNumber}/locations/global/keys`,
    {
      headers: authH,
      body: JSON.stringify({
        displayName: "Barky Firestore REST unrestricted",
        restrictions: {
          apiTargets: [{ service: "firestore.googleapis.com" }],
        },
      }),
    }
  );
  console.log("create key", created.status, JSON.stringify(created.json).slice(0, 400));
  let keyName = created.json.name;
  if (created.json.name && String(created.json.name).includes("operations")) {
    for (let i = 0; i < 20; i++) {
      await new Promise((r) => setTimeout(r, 2000));
      const op = await request(
        "GET",
        `https://apikeys.googleapis.com/v2/${created.json.name}`,
        { headers: authH }
      );
      if (op.json.done) {
        keyName = op.json.response?.name;
        console.log("op done keyName", keyName);
        break;
      }
    }
  }
  if (keyName) {
    const ks = await request(
      "GET",
      `https://apikeys.googleapis.com/v2/${keyName}/keyString`,
      { headers: authH }
    );
    if (ks.json.keyString) {
      apiKey = ks.json.keyString;
      console.log("NEW_API_KEY", apiKey);
      listKey = await request(
        "GET",
        `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents/devices?pageSize=5&key=${apiKey}`
      );
      console.log("list with new key", listKey.status, listKey.text.slice(0, 200));
    }
  }
}

// Write config for app
const out = {
  project_id: PROJECT,
  database_id: DATABASE,
  api_key: apiKey,
  list_status: listKey.status,
};
fs.writeFileSync(path.join(root, "secrets", "firebase-app-config.json"), JSON.stringify(out, null, 2));
console.log("Wrote secrets/firebase-app-config.json", out);

// Write a probe device as SA so we know DB write path works
const probe = {
  fields: {
    deviceId: { stringValue: "_pc_probe" },
    displayName: { stringValue: "PC Probe" },
    phoneModel: { stringValue: "seed-script" },
    manufacturer: { stringValue: "xAI" },
    androidVersion: { stringValue: "n/a" },
    androidSdk: { integerValue: "0" },
    appVersion: { stringValue: "seed" },
    localIp: { stringValue: "" },
    publicIp: { stringValue: "" },
    updatedAtMs: { integerValue: String(Date.now()) },
    note: { stringValue: "probe from fix-firestore-api-key.mjs" },
  },
};
const patch = await request(
  "PATCH",
  `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents/devices/_pc_probe`,
  { headers: { ...authH, "Content-Type": "application/json" }, body: JSON.stringify(probe) }
);
console.log("probe write SA", patch.status);
