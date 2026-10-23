/**
 * Uses local service-account JSON (secrets/) to obtain a browser/Android API key
 * for Firestore REST (?key=...) used by Barky. Never ship the SA private key in the APK.
 *
 * Usage: node scripts/fetch-firebase-web-api-key.mjs
 */
import fs from "fs";
import path from "path";
import crypto from "crypto";
import https from "https";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, "..");
const saPath = path.join(root, "secrets", "firebase-service-account.json");

if (!fs.existsSync(saPath)) {
  console.error("Missing secrets/firebase-service-account.json");
  process.exit(2);
}

const sa = JSON.parse(fs.readFileSync(saPath, "utf8"));
const projectId = sa.project_id;

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
                "Content-Type":
                  headers["Content-Type"] || "application/json",
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
          resolve({ ok: res.statusCode >= 200 && res.statusCode < 300, status: res.statusCode, json });
        });
      }
    );
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}

async function getAccessToken() {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(
    JSON.stringify({
      iss: sa.client_email,
      scope:
        "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    })
  );
  const unsigned = `${header}.${claim}`;
  const sign = crypto.createSign("RSA-SHA256");
  sign.update(unsigned);
  sign.end();
  const sig = sign.sign(sa.private_key);
  const jwt = `${unsigned}.${b64url(sig)}`;

  const body = new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion: jwt,
  }).toString();

  const res = await request("POST", "https://oauth2.googleapis.com/token", {
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!res.json.access_token) {
    console.error("Token error:", JSON.stringify(res.json, null, 2));
    process.exit(1);
  }
  return res.json.access_token;
}

async function api(token, method, url, bodyObj) {
  return request(method, url, {
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: bodyObj !== undefined ? JSON.stringify(bodyObj) : undefined,
  });
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function main() {
  console.log("Project:", projectId);
  const token = await getAccessToken();
  console.log("Got access token");

  let projectNumber = null;
  {
    const r = await api(
      token,
      "GET",
      `https://cloudresourcemanager.googleapis.com/v1/projects/${projectId}`
    );
    if (r.ok) {
      projectNumber = r.json.projectNumber;
      console.log("Project number:", projectNumber);
    } else {
      console.warn("Could not get project metadata:", r.status, JSON.stringify(r.json));
    }
  }

  for (const svc of [
    "firestore.googleapis.com",
    "firebase.googleapis.com",
    "apikeys.googleapis.com",
    "firebaserules.googleapis.com",
    "serviceusage.googleapis.com",
  ]) {
    const r = await api(
      token,
      "POST",
      `https://serviceusage.googleapis.com/v1/projects/${projectId}/services/${svc}:enable`,
      {}
    );
    const msg = r.ok ? "ok" : r.json?.error?.message || JSON.stringify(r.json).slice(0, 200);
    console.log("Enable", svc, r.status, msg);
  }

  // Wait briefly for API enable
  await sleep(3000);

  {
    const list = await api(
      token,
      "GET",
      `https://firestore.googleapis.com/v1/projects/${projectId}/databases`
    );
    console.log("Firestore list:", list.status, JSON.stringify(list.json).slice(0, 500));
    const names = (list.json.databases || []).map((d) => d.name);
    if (!names.length) {
      const create = await api(
        token,
        "POST",
        `https://firestore.googleapis.com/v1/projects/${projectId}/databases?databaseId=(default)`,
        {
          type: "FIRESTORE_NATIVE",
          locationId: "nam5",
        }
      );
      console.log("Create Firestore:", create.status, JSON.stringify(create.json).slice(0, 500));
    }
  }

  let apiKey = null;
  if (projectNumber) {
    let list = await api(
      token,
      "GET",
      `https://apikeys.googleapis.com/v2/projects/${projectNumber}/locations/global/keys`
    );
    if (!list.ok) {
      console.log("API keys list failed, retry after enable:", list.status, JSON.stringify(list.json).slice(0, 300));
      await sleep(5000);
      list = await api(
        token,
        "GET",
        `https://apikeys.googleapis.com/v2/projects/${projectNumber}/locations/global/keys`
      );
    }
    console.log("API keys list status:", list.status);
    const keys = list.json.keys || [];
    for (const k of keys) {
      console.log("  key:", k.displayName || k.name);
    }
    for (const k of keys) {
      const detail = await api(
        token,
        "GET",
        `https://apikeys.googleapis.com/v2/${k.name}/keyString`
      );
      if (detail.ok && detail.json.keyString) {
        apiKey = detail.json.keyString;
        console.log("Using existing key:", k.displayName || k.name);
        break;
      } else {
        console.log("keyString fail:", detail.status, JSON.stringify(detail.json).slice(0, 200));
      }
    }
    if (!apiKey) {
      const created = await api(
        token,
        "POST",
        `https://apikeys.googleapis.com/v2/projects/${projectNumber}/locations/global/keys`,
        {
          displayName: "Barky Android REST (Firestore directory)",
          restrictions: {
            apiTargets: [{ service: "firestore.googleapis.com" }],
          },
        }
      );
      console.log("Create key status:", created.status, JSON.stringify(created.json).slice(0, 600));
      if (created.json.name && String(created.json.name).includes("operations")) {
        for (let i = 0; i < 20; i++) {
          await sleep(2000);
          const op = await api(token, "GET", `https://apikeys.googleapis.com/v2/${created.json.name}`);
          if (op.json.done) {
            const keyName = op.json.response?.name;
            if (keyName) {
              const ks = await api(token, "GET", `https://apikeys.googleapis.com/v2/${keyName}/keyString`);
              if (ks.ok) apiKey = ks.json.keyString;
            }
            console.log("Create op done:", JSON.stringify(op.json).slice(0, 500));
            break;
          }
        }
      } else if (created.json.keyString) {
        apiKey = created.json.keyString;
      } else if (created.json.name) {
        const ks = await api(
          token,
          "GET",
          `https://apikeys.googleapis.com/v2/${created.json.name}/keyString`
        );
        if (ks.ok) apiKey = ks.json.keyString;
      }
    }
  }

  if (!apiKey) {
    const apps = await api(
      token,
      "GET",
      `https://firebase.googleapis.com/v1beta1/projects/${projectId}/webApps`
    );
    console.log("Web apps:", apps.status, JSON.stringify(apps.json).slice(0, 400));
    const app = (apps.json.apps || [])[0];
    if (app?.appId) {
      const cfg = await api(
        token,
        "GET",
        `https://firebase.googleapis.com/v1beta1/projects/${projectId}/webApps/${app.appId}/config`
      );
      console.log("Web config:", cfg.status, JSON.stringify(cfg.json).slice(0, 400));
      if (cfg.json.apiKey) apiKey = cfg.json.apiKey;
    }
  }

  // Fallback: create Android app in Firebase and read config
  if (!apiKey) {
    const androidApps = await api(
      token,
      "GET",
      `https://firebase.googleapis.com/v1beta1/projects/${projectId}/androidApps`
    );
    console.log("Android apps:", androidApps.status, JSON.stringify(androidApps.json).slice(0, 400));
    let appId = (androidApps.json.apps || [])[0]?.appId;
    if (!appId) {
      const createApp = await api(
        token,
        "POST",
        `https://firebase.googleapis.com/v1beta1/projects/${projectId}/androidApps`,
        {
          packageName: "com.example.barky",
          displayName: "Barky",
        }
      );
      console.log("Create Android app:", createApp.status, JSON.stringify(createApp.json).slice(0, 500));
      if (createApp.json.name && String(createApp.json.name).includes("operations")) {
        for (let i = 0; i < 15; i++) {
          await sleep(2000);
          const op = await api(
            token,
            "GET",
            `https://firebase.googleapis.com/v1beta1/${createApp.json.name}`
          );
          if (op.json.done) {
            appId = op.json.response?.appId;
            console.log("Android app op done, appId=", appId);
            break;
          }
        }
      } else {
        appId = createApp.json.appId;
      }
    }
    if (appId) {
      const cfg = await api(
        token,
        "GET",
        `https://firebase.googleapis.com/v1beta1/projects/${projectId}/androidApps/${appId}/config`
      );
      // config may be binary/base64 google-services style
      console.log("Android config:", cfg.status, JSON.stringify(cfg.json).slice(0, 600));
      if (cfg.json.apiKey) apiKey = cfg.json.apiKey;
      if (cfg.json.configFileContents) {
        const decoded = Buffer.from(cfg.json.configFileContents, "base64").toString("utf8");
        console.log("google-services snippet:", decoded.slice(0, 800));
        try {
          const gs = JSON.parse(decoded);
          apiKey = gs.client?.[0]?.api_key?.[0]?.current_key || apiKey;
          fs.writeFileSync(path.join(root, "secrets", "google-services.json"), decoded);
          console.log("Wrote secrets/google-services.json");
        } catch (e) {
          console.warn("Parse google-services failed", e.message);
        }
      }
    }
  }

  const out = {
    project_id: projectId,
    project_number: projectNumber,
    api_key: apiKey || null,
    note: "Paste into firebase_config.xml — do not commit service account",
  };
  const outPath = path.join(root, "secrets", "firebase-app-config.json");
  fs.writeFileSync(outPath, JSON.stringify(out, null, 2));
  console.log("\n=== RESULT ===");
  console.log(JSON.stringify(out, null, 2));
  console.log("Wrote", outPath);
  if (!apiKey) process.exit(3);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
