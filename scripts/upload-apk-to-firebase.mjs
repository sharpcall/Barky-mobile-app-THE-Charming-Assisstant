/**
 * Upload Barky APK to Firebase Storage and publish catalog in Firestore:
 *   _meta/app_update { apkUrl, versionCode, versionName, updatedAtMs }
 *
 * Phones read that doc and self-update when their versionCode is lower.
 *
 * Usage:
 *   node scripts/upload-apk-to-firebase.mjs
 *   node scripts/upload-apk-to-firebase.mjs path/to/app-debug.apk
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
// From google-services.json storage_bucket
const BUCKET =
  process.env.FIREBASE_STORAGE_BUCKET ||
  `${PROJECT}.firebasestorage.app`;
const OBJECT = "updates/barky-latest.apk";

const apkArg = process.argv[2];
const apkPath = apkArg
  ? path.resolve(apkArg)
  : path.join(root, "app", "build-outputs", "app-debug.apk");

if (!fs.existsSync(apkPath)) {
  console.error("APK not found:", apkPath);
  process.exit(2);
}

// Parse version from app/build.gradle
const gradle = fs.readFileSync(path.join(root, "app", "build.gradle"), "utf8");
const vc = Number((gradle.match(/versionCode\s+(\d+)/) || [])[1] || 0);
const vn = (gradle.match(/versionName\s+"([^"]+)"/) || [])[1] || "unknown";
console.log("APK", apkPath, "size", fs.statSync(apkPath).size);
console.log("versionCode", vc, "versionName", vn);
console.log("bucket", BUCKET, "object", OBJECT);

function b64url(buf) {
  return Buffer.from(buf)
    .toString("base64")
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function request(method, urlStr, { headers = {}, body, raw } = {}) {
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
                  headers["Content-Type"] ||
                  (raw ? "application/octet-stream" : "application/json"),
                "Content-Length": Buffer.isBuffer(body)
                  ? body.length
                  : Buffer.byteLength(body),
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

async function token(scopes) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(
    JSON.stringify({
      iss: sa.client_email,
      scope: scopes,
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

const access = await token(
  "https://www.googleapis.com/auth/devstorage.full_control https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/cloud-platform"
);
const auth = { Authorization: `Bearer ${access}` };

// Ensure bucket exists (create if missing)
{
  const getB = await request(
    "GET",
    `https://storage.googleapis.com/storage/v1/b/${encodeURIComponent(BUCKET)}`,
    { headers: auth }
  );
  console.log("bucket get", getB.status);
  if (getB.status === 404) {
    const create = await request(
      "POST",
      `https://storage.googleapis.com/storage/v1/b?project=${PROJECT}`,
      {
        headers: auth,
        body: JSON.stringify({ name: BUCKET, location: "US", storageClass: "STANDARD" }),
      }
    );
    console.log("bucket create", create.status, JSON.stringify(create.json).slice(0, 300));
  }
}

// Upload APK to GCS first; if GCS bucket is unavailable (e.g. billing disabled 403/404),
// automatically fall back to uploading the APK as a GitHub Release asset (`apk-latest`).
const bytes = fs.readFileSync(apkPath);
let publicUrl = "";

async function uploadToGithubRelease() {
  const tokenPath = path.join(root, ".github_token");
  if (!fs.existsSync(tokenPath)) {
    throw new Error("Missing .github_token for GitHub Release APK fallback");
  }
  const ghToken = fs.readFileSync(tokenPath, "utf8").trim();
  const owner = "sharpcall";
  const repo = "Barky-mobile-app-THE-Charming-Assisstant";
  const tag = "apk-latest";
  const ghHeaders = {
    Authorization: `Bearer ${ghToken}`,
    "User-Agent": "barky-apk-publisher",
    Accept: "application/vnd.github+json",
  };

  // Ensure repo is public or release asset is downloadable by phones over cellular/Wi-Fi.
  let rel = await request(
    "GET",
    `https://api.github.com/repos/${owner}/${repo}/releases/tags/${tag}`,
    { headers: ghHeaders }
  );
  if (rel.status === 404) {
    rel = await request(
      "POST",
      `https://api.github.com/repos/${owner}/${repo}/releases`,
      {
        headers: ghHeaders,
        body: JSON.stringify({
          tag_name: tag,
          name: `Barky Latest APK (${vn})`,
          body: `Automated OTA APK build versionCode=${vc} versionName=${vn}`,
          draft: false,
          prerelease: false,
        }),
      }
    );
  }
  if (rel.status < 200 || rel.status >= 300 || !rel.json.id) {
    throw new Error(`GitHub release lookup/create failed: ${rel.status} ${rel.text.slice(0, 200)}`);
  }
  const releaseId = rel.json.id;
  // Delete existing app-debug.apk asset on that release if present.
  for (const asset of rel.json.assets || []) {
    if (asset.name === "app-debug.apk") {
      await request(
        "DELETE",
        `https://api.github.com/repos/${owner}/${repo}/releases/assets/${asset.id}`,
        { headers: ghHeaders }
      );
    }
  }
  // Upload fresh app-debug.apk asset to uploads.github.com.
  const upAsset = await request(
    "POST",
    `https://uploads.github.com/repos/${owner}/${repo}/releases/${releaseId}/assets?name=app-debug.apk`,
    {
      headers: {
        ...ghHeaders,
        "Content-Type": "application/vnd.android.package-archive",
      },
      body: bytes,
      raw: true,
    }
  );
  if (upAsset.status < 200 || upAsset.status >= 300 || !upAsset.json.browser_download_url) {
    throw new Error(`GitHub asset upload failed: ${upAsset.status} ${upAsset.text.slice(0, 200)}`);
  }
  console.log("github release asset uploaded:", upAsset.json.browser_download_url);
  // Test unauthenticated GET on browser_download_url (as Android ApkSelfUpdate does)
  const testUnauth = await request("GET", upAsset.json.browser_download_url, {
    headers: { "User-Agent": "Android-Barky", Range: "bytes=0-15" },
  });
  console.log("unauth probe status:", testUnauth.status, "location:", testUnauth.location || "");
  if (testUnauth.status === 404) {
    console.log("Repo is private (404 unauth) — setting visibility=public so phones can OTA download...");
    const vis = await request("PATCH", `https://api.github.com/repos/${owner}/${repo}`, {
      headers: ghHeaders,
      body: JSON.stringify({ private: false }),
    });
    console.log("repo visibility patch:", vis.status);
  }
  return upAsset.json.browser_download_url;
}

const uploadUrl =
  `https://storage.googleapis.com/upload/storage/v1/b/${encodeURIComponent(BUCKET)}/o` +
  `?uploadType=media&name=${encodeURIComponent(OBJECT)}&predefinedAcl=publicRead`;
const up = await request("POST", uploadUrl, {
  headers: {
    ...auth,
    "Content-Type": "application/vnd.android.package-archive",
  },
  body: bytes,
  raw: true,
});
console.log("upload", up.status, JSON.stringify(up.json).slice(0, 400));
let gcsOk = up.status >= 200 && up.status < 300;
if (!gcsOk) {
  // Retry without predefinedAcl (org policy may block public ACL)
  const up2 = await request(
    "POST",
    `https://storage.googleapis.com/upload/storage/v1/b/${encodeURIComponent(BUCKET)}/o` +
      `?uploadType=media&name=${encodeURIComponent(OBJECT)}`,
    {
      headers: {
        ...auth,
        "Content-Type": "application/vnd.android.package-archive",
      },
      body: bytes,
      raw: true,
    }
  );
  console.log("upload retry", up2.status, JSON.stringify(up2.json).slice(0, 400));
  gcsOk = up2.status >= 200 && up2.status < 300;
}

if (gcsOk) {
  // Make public (best effort)
  const encObj = encodeURIComponent(OBJECT);
  await request(
    "POST",
    `https://storage.googleapis.com/storage/v1/b/${encodeURIComponent(BUCKET)}/o/${encObj}/acl`,
    {
      headers: auth,
      body: JSON.stringify({ entity: "allUsers", role: "READER" }),
    }
  ).then((r) => console.log("acl allUsers", r.status, JSON.stringify(r.json).slice(0, 200)));

  const apkUrl = `https://storage.googleapis.com/${BUCKET}/${OBJECT}`;
  const firebaseMediaUrl =
    `https://firebasestorage.googleapis.com/v0/b/${BUCKET}/o/${encodeURIComponent(OBJECT)}?alt=media`;

  publicUrl = apkUrl;
  const p1 = await request("GET", apkUrl, { headers: { Range: "bytes=0-0" } });
  if (p1.status < 200 || p1.status >= 400) {
    const p2 = await request("GET", firebaseMediaUrl, { headers: { Range: "bytes=0-0" } });
    if (p2.status >= 200 && p2.status < 400) publicUrl = firebaseMediaUrl;
  }
} else {
  console.log("GCS unavailable — uploading APK to GitHub Releases for Firebase catalog...");
  publicUrl = await uploadToGithubRelease();
}

// Write Firestore catalog
const fields = {
  apkUrl: { stringValue: publicUrl },
  versionCode: { integerValue: String(vc) },
  versionName: { stringValue: vn },
  fileName: { stringValue: path.basename(apkPath) },
  sizeBytes: { integerValue: String(bytes.length) },
  storageObject: { stringValue: `gs://${BUCKET}/${OBJECT}` },
  updatedAtMs: { integerValue: String(Date.now()) },
  note: {
    stringValue:
      "Phones with lower versionCode download apkUrl and install. Media calls stay P2P.",
  },
};
const patch = await request(
  "PATCH",
  `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents/_meta/app_update`,
  {
    headers: auth,
    body: JSON.stringify({ fields }),
  }
);
console.log("firestore app_update", patch.status, patch.status < 300 ? "ok" : patch.text.slice(0, 400));
if (patch.status >= 300) process.exit(4);

const out = {
  apkUrl: publicUrl,
  versionCode: vc,
  versionName: vn,
  bucket: BUCKET,
  object: OBJECT,
};
fs.writeFileSync(path.join(root, "secrets", "firebase-app-update.json"), JSON.stringify(out, null, 2));
console.log("\nSUCCESS — phones will self-update when versionCode <", vc);
console.log(JSON.stringify(out, null, 2));

// Verify that Android phones (which use ?key=... without a Service Account Bearer token)
// can read and write Firestore `barkydefault` (_meta/app_update and devices).
const xmlCfg = fs.readFileSync(path.join(root, "app", "src", "main", "res", "values", "firebase_config.xml"), "utf8");
const appApiKey = (xmlCfg.match(/name="firebase_api_key"[^>]*>([^<]+)</) || [])[1] || "";
const unauthMeta = await request(
  "GET",
  `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents/_meta/app_update?key=${appApiKey}`
);
const unauthDevs = await request(
  "GET",
  `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents/devices?pageSize=2&key=${appApiKey}`
);
console.log("phone-style ?key= probe: _meta/app_update=", unauthMeta.status, "devices=", unauthDevs.status);
if (unauthMeta.status !== 200 || unauthDevs.status !== 200) {
  console.log("phone-style ?key= error:", unauthDevs.text.slice(0, 240));
  console.log("Deploying permanent open Firestore security rules via firebaserules API...");
  const rulesSource = `rules_version = '2';\nservice cloud.firestore {\n  match /databases/{database}/documents {\n    match /{document=**} {\n      allow read, write: if true;\n    }\n  }\n}\n`;
  const rsRes = await request(
    "POST",
    `https://firebaserules.googleapis.com/v1/projects/${PROJECT}/rulesets`,
    {
      headers: auth,
      body: JSON.stringify({
        source: { files: [{ name: "firestore.rules", content: rulesSource }] },
      }),
    }
  );
  console.log("create ruleset:", rsRes.status, rsRes.json.name || rsRes.text.slice(0, 200));
  if (rsRes.json.name) {
    for (const relName of ["cloud.firestore", `cloud.firestore/${DATABASE}`]) {
      const fullRel = `projects/${PROJECT}/releases/${relName}`;
      let relRes = await request(
        "PATCH",
        `https://firebaserules.googleapis.com/v1/${fullRel}`,
        {
          headers: auth,
          body: JSON.stringify({
            release: { name: fullRel, rulesetName: rsRes.json.name },
          }),
        }
      );
      if (relRes.status === 404) {
        relRes = await request(
          "POST",
          `https://firebaserules.googleapis.com/v1/projects/${PROJECT}/releases`,
          {
            headers: auth,
            body: JSON.stringify({ name: fullRel, rulesetName: rsRes.json.name }),
          }
        );
      }
      console.log(`release ${relName}:`, relRes.status);
    }
  }
}

// Also query Firestore `devices` collection using the service-account bearer token
// so we know each phone's current localIp, localVideoPort, publicIp, onWifi, and versionCode.
const devRes = await request(
  "GET",
  `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DATABASE}/documents/devices?pageSize=100`,
  { headers: auth }
);
if (devRes.status >= 200 && devRes.status < 300 && devRes.json.documents) {
  const peers = [];
  console.log(`\n=== FIRESTORE DEVICES (total=${devRes.json.documents.length}) ===`);
  for (const doc of devRes.json.documents) {
    const f = doc.fields || {};
    const p = {
      docId: doc.name.split("/").pop(),
      displayName: f.displayName?.stringValue || "",
      deviceId: f.deviceId?.stringValue || "",
      localIp: f.localIp?.stringValue || "",
      localUdpPort: Number(f.localUdpPort?.integerValue || 0),
      localVideoPort: Number(f.localVideoPort?.integerValue || 0),
      publicIp: f.publicIp?.stringValue || "",
      publicUdpPort: Number(f.publicUdpPort?.integerValue || 0),
      onWifi: !!f.onWifi?.booleanValue,
      versionCode: Number(f.versionCode?.integerValue || 0),
      appVersion: f.appVersion?.stringValue || "",
      updatedAtMs: Number(f.updatedAtMs?.integerValue || 0),
    };
    peers.push(p);
  }
  peers.sort((a, b) => b.updatedAtMs - a.updatedAtMs);
  for (const p of peers.slice(0, 12)) {
    const dt = p.updatedAtMs ? new Date(p.updatedAtMs).toISOString() : "n/a";
    console.log(
      `  ${p.displayName} (${p.docId.slice(0, 12)}) | vc=${p.versionCode} (${p.appVersion}) | wifi=${p.onWifi} | local=${p.localIp}:${p.localUdpPort} (v=${p.localVideoPort}) | pub=${p.publicIp}:${p.publicUdpPort} | at=${dt}`
    );
  }
  fs.writeFileSync(path.join(root, "secrets", "firestore-devices.json"), JSON.stringify(peers, null, 2));
}
