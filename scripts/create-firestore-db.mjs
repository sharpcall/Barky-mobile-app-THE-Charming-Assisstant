/**
 * Create Firestore (default) for Barky if missing. Uses secrets/firebase-service-account.json.
 * node scripts/create-firestore-db.mjs
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
          resolve({ status: res.statusCode, json });
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
      scope:
        "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/datastore",
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
  if (!res.json.access_token) {
    console.error(res.json);
    process.exit(1);
  }
  return res.json.access_token;
}

const access = await token();
const projectId = sa.project_id;
const list = await request(
  "GET",
  `https://firestore.googleapis.com/v1/projects/${projectId}/databases`,
  { headers: { Authorization: `Bearer ${access}` } }
);
console.log("list", list.status, JSON.stringify(list.json).slice(0, 400));
const names = (list.json.databases || []).map((d) => d.name);
if (names.length) {
  console.log("Already have:", names.join(", "));
  process.exit(0);
}
const create = await request(
  "POST",
  `https://firestore.googleapis.com/v1/projects/${projectId}/databases?databaseId=(default)`,
  {
    headers: { Authorization: `Bearer ${access}` },
    body: JSON.stringify({ type: "FIRESTORE_NATIVE", locationId: "nam5" }),
  }
);
console.log("create", create.status, JSON.stringify(create.json).slice(0, 800));
if (create.status >= 300 && create.status !== 409) process.exit(2);
