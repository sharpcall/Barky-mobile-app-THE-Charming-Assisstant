/**
 * Push the current branch (main) to GitHub origin using the local .github_token file
 * without exposing or logging credentials in stdout/stderr.
 *
 * Usage: node scripts/push-github.mjs
 */
import fs from "fs";
import path from "path";
import { spawnSync } from "child_process";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const TOKEN_PATH = path.join(ROOT, ".github_token");

if (!fs.existsSync(TOKEN_PATH)) {
  console.error("Missing .github_token in repo root");
  process.exit(1);
}

// Read PAT from .github_token and build Basic auth header for git http.extraheader.
const token = fs.readFileSync(TOKEN_PATH, "utf8").trim();
if (!token) {
  console.error("Empty .github_token");
  process.exit(1);
}

const basicAuth = Buffer.from(`x-access-token:${token}`, "utf8").toString("base64");
// Normalize Windows backslashes to forward slashes for git safe.directory config.
const safeDir = ROOT.split(path.sep).join("/");
const res = spawnSync(
  "git",
  [
    "-c",
    `safe.directory=${safeDir}`,
    "-c",
    `http.https://github.com/.extraheader=AUTHORIZATION: basic ${basicAuth}`,
    "push",
    "origin",
    "main",
  ],
  {
    cwd: ROOT,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }
);

if (res.stdout) process.stdout.write(res.stdout);
if (res.stderr) process.stderr.write(res.stderr);
process.exit(res.status ?? 1);
