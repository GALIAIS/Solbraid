// Solbraid 编译器历史版本实时索引解析器（支持 Go / Zig / Node / Java Temurin / Rust / Python / pnpm / Bun / APT）。
// 自动拉取官方/镜像发布清单并做 15 分钟本地缓存，提供精确版本、渠道标签、包大小与 SHA-256 校验值。
import fs from "node:fs/promises";
import path from "node:path";
import {execFile} from "node:child_process";
import {promisify} from "node:util";
import {githubDownloadUrl} from "./download-network.mjs";

const execFileAsync = promisify(execFile);
const CACHE_TTL_MS = 15 * 60_000;
export const VALID_VERSION_RE = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$/;

export function assertSafeVersion(ver) {
  const v = String(ver || "").trim();
  if (!v || !VALID_VERSION_RE.test(v)) throw new Error(`版本号格式无效：${ver}`);
  return v;
}

async function readVersionCache(home, id) {
  const file = path.join(home, ".galiais", "toolchains", "version-cache", `${id}.json`);
  try {
    const data = JSON.parse(await fs.readFile(file, "utf8"));
    if (data && Date.now() - Number(data.fetchedAt || 0) < CACHE_TTL_MS && Array.isArray(data.versions)) {
      return data.versions;
    }
  } catch {}
  return null;
}

async function writeVersionCache(home, id, versions) {
  const dir = path.join(home, ".galiais", "toolchains", "version-cache");
  await fs.mkdir(dir, {recursive: true, mode: 0o700});
  await fs.writeFile(path.join(dir, `${id}.json`), JSON.stringify({fetchedAt: Date.now(), versions}, null, 2), "utf8").catch(() => {});
}

async function fetchJson(url, timeoutMs = 15_000) {
  const res = await fetch(url, {
    headers: {"user-agent": "Solbraid-Toolchain-Studio/2.0"},
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return await res.json();
}

async function fetchText(url, timeoutMs = 15_000) {
  const res = await fetch(url, {
    headers: {"user-agent": "Solbraid-Toolchain-Studio/2.0"},
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return await res.text();
}

async function fetchGoVersions() {
  const goArch = process.arch === "arm64" ? "arm64" : "amd64";
  const mirrors = ["https://golang.google.cn/dl", "https://go.dev/dl"];
  for (const base of mirrors) {
    try {
      const releases = await fetchJson(`${base}/?mode=json&include=all`);
      const out = [];
      const seenMinor = new Map();
      for (const rel of releases) {
        if (!rel?.version || !Array.isArray(rel.files)) continue;
        const ver = String(rel.version).replace(/^go/, "");
        // 默认列表仅展示正式稳定版与历史正式归档版，过滤 beta/rc 预发布版（高级用户仍可手动输入）
        if (!rel.stable || !/^\d+\.\d+(?:\.\d+)?$/.test(ver)) continue;
        const file = rel.files.find(x => x.os === "linux" && x.arch === goArch && x.kind === "archive" && x.filename.endsWith(".tar.gz"));
        if (!file) continue;
        const minor = ver.split(".").slice(0, 2).join(".");
        const cnt = seenMinor.get(minor) || 0;
        if (cnt >= 3) continue; // 每个次版本保留最新 3 个修订版，兼顾新旧历史覆盖
        seenMinor.set(minor, cnt + 1);
        out.push({
          version: ver,
          channel: out.length < 2 ? "stable" : "archive",
          url: `${base}/${file.filename}`,
          filename: file.filename,
          sha256: file.sha256 || "",
          sizeBytes: Number(file.size) || 0,
        });
        if (out.length >= 24) break;
      }
      if (out.length) return out;
    } catch {}
  }
  throw new Error("无法获取 Go 官方历史版本列表，请检查网络连接");
}

async function fetchZigVersions() {
  const gnuArch = process.arch === "arm64" ? "aarch64" : "x86_64";
  const key = `${gnuArch}-linux`;
  const idx = await fetchJson("https://ziglang.org/download/index.json");
  const stableList = [];
  let masterItem = null;
  for (const [ver, entry] of Object.entries(idx || {})) {
    const asset = entry?.[key];
    if (!asset?.tarball) continue;
    const item = {
      version: ver === "master" ? (entry.version || "master") : ver,
      channel: ver === "master" ? "nightly" : (stableList.length === 0 ? "stable" : "archive"),
      url: asset.tarball,
      filename: path.basename(asset.tarball),
      sha256: asset.shasum || "",
      sizeBytes: Number(asset.size) || 0,
    };
    if (ver === "master") masterItem = item;
    else stableList.push(item);
  }
  // 正式版本优先排列，master/dev 开发版置于末尾，避免误选每日构建
  return masterItem ? [...stableList, masterItem] : stableList;
}

async function fetchNodeVersions(dlNet = {}) {
  const nodeArch = process.arch === "arm64" ? "arm64" : "x64";
  const preferCn = (dlNet.npmRegistry || "").includes("npmmirror");
  const bases = preferCn
    ? ["https://npmmirror.com/mirrors/node", "https://nodejs.org/dist"]
    : ["https://nodejs.org/dist", "https://npmmirror.com/mirrors/node"];
  for (const base of bases) {
    try {
      const list = await fetchJson(`${base}/index.json`);
      const out = [];
      const seenMajor = new Map();
      for (const item of list) {
        const ver = String(item?.version || "").replace(/^v/, "");
        // 严格过滤 alpha/beta/rc/nightly 预发布构建，仅列正式发布版本
        if (!/^\d+\.\d+\.\d+$/.test(ver)) continue;
        const major = Number(ver.split(".")[0]) || 0;
        if (major < 16) break;
        const cnt = seenMajor.get(major) || 0;
        if (cnt >= 3) continue;
        seenMajor.set(major, cnt + 1);
        const filename = `node-v${ver}-linux-${nodeArch}.tar.gz`;
        out.push({
          version: ver,
          channel: item.lts ? `LTS (${item.lts})` : (out.length === 0 ? "current" : "archive"),
          url: `${base}/v${ver}/${filename}`,
          shasumsUrl: `${base}/v${ver}/SHASUMS256.txt`,
          filename,
          sha256: "",
          sizeBytes: 42 * 1024 * 1024,
        });
        if (out.length >= 24) break;
      }
      if (out.length) return out;
    } catch {}
  }
  throw new Error("无法获取 Node.js 版本索引");
}

async function fetchJavaVersions() {
  const jdkArch = process.arch === "arm64" ? "aarch64" : "x64";
  const features = [25, 21, 17, 11, 8];
  const ltsSet = new Set([25, 21, 17, 11, 8]);
  const results = await Promise.all(features.map(async feat => {
    try {
      const arr = await fetchJson(`https://api.adoptium.net/v3/assets/latest/${feat}/hotspot?architecture=${jdkArch}&image_type=jdk&os=linux&vendor=eclipse`, 12_000);
      const pkg = arr?.[0]?.binary?.package;
      const relName = arr?.[0]?.release_name || `${feat}`;
      if (!pkg?.link) return null;
      return {
        version: relName.replace(/^jdk-?/, ""),
        channel: ltsSet.has(feat) ? `LTS (JDK ${feat})` : `JDK ${feat}`,
        url: pkg.link,
        filename: pkg.name || `temurin-jdk-${feat}.tar.gz`,
        sha256: pkg.checksum || "",
        sizeBytes: Number(pkg.size) || 0,
      };
    } catch { return null; }
  }));
  const valid = results.filter(Boolean);
  if (valid.length) return valid;
  return [
    {version: "21", channel: "LTS (JDK 21)", url: `https://api.adoptium.net/v3/binary/latest/21/ga/linux/${jdkArch}/jdk/hotspot/normal/eclipse`, filename: "temurin-21.tar.gz", sha256: "", sizeBytes: 195 * 1024 * 1024},
    {version: "17", channel: "LTS (JDK 17)", url: `https://api.adoptium.net/v3/binary/latest/17/ga/linux/${jdkArch}/jdk/hotspot/normal/eclipse`, filename: "temurin-17.tar.gz", sha256: "", sizeBytes: 185 * 1024 * 1024},
    {version: "11", channel: "LTS (JDK 11)", url: `https://api.adoptium.net/v3/binary/latest/11/ga/linux/${jdkArch}/jdk/hotspot/normal/eclipse`, filename: "temurin-11.tar.gz", sha256: "", sizeBytes: 180 * 1024 * 1024},
  ];
}

async function fetchPnpmVersions(dlNet = {}) {
  const bases = [dlNet.npmRegistry, "https://registry.npmmirror.com", "https://registry.npmjs.org"].filter(Boolean);
  for (const base of bases) {
    try {
      const meta = await fetchJson(`${base.replace(/\/+$/, "")}/pnpm`, 12_000);
      const vers = Object.keys(meta.versions || {}).filter(v => /^\d+\.\d+\.\d+$/.test(v)).reverse();
      const out = [];
      const seenMinor = new Map();
      for (const v of vers) {
        const major = Number(v.split(".")[0]);
        if (major < 8) break;
        const minor = v.split(".").slice(0, 2).join(".");
        if ((seenMinor.get(minor) || 0) >= 2) continue;
        seenMinor.set(minor, (seenMinor.get(minor) || 0) + 1);
        out.push({version: v, channel: v === meta["dist-tags"]?.latest ? "stable" : "archive", sizeBytes: 14 * 1024 * 1024});
        if (out.length >= 18) break;
      }
      if (out.length) return out;
    } catch {}
  }
  return [
    {version: "10.6.5", channel: "stable", sizeBytes: 14 * 1024 * 1024},
    {version: "9.15.5", channel: "LTS 9.x", sizeBytes: 14 * 1024 * 1024},
    {version: "8.15.9", channel: "archive", sizeBytes: 14 * 1024 * 1024},
  ];
}

async function fetchBunVersions(dlNet = {}) {
  const bunArch = process.arch === "arm64" ? "aarch64" : "x64";
  try {
    const rels = await fetchJson("https://api.github.com/repos/oven-sh/bun/releases?per_page=18", 12_000);
    const out = [];
    for (const r of rels) {
      if (r.draft || r.prerelease) continue;
      const ver = String(r.tag_name || "").replace(/^bun-v/, "");
      if (!ver) continue;
      const rawUrl = `https://github.com/oven-sh/bun/releases/download/bun-v${ver}/bun-linux-${bunArch}.zip`;
      out.push({
        version: ver,
        channel: out.length === 0 ? "stable" : "archive",
        url: githubDownloadUrl(rawUrl, dlNet),
        filename: `bun-linux-${bunArch}-${ver}.zip`,
        sha256: "",
        sizeBytes: 36 * 1024 * 1024,
      });
      if (out.length >= 15) break;
    }
    if (out.length) return out;
  } catch {}
  try {
    const atom = await fetchText("https://github.com/oven-sh/bun/releases.atom", 12_000);
    const vers = [...atom.matchAll(/<title>Bun v(\d+\.\d+\.\d+)<\/title>/gi)].map(m => m[1]);
    const uniq = [...new Set(vers)].slice(0, 15);
    if (uniq.length) {
      return uniq.map((ver, idx) => ({
        version: ver,
        channel: idx === 0 ? "stable" : "archive",
        url: githubDownloadUrl(`https://github.com/oven-sh/bun/releases/download/bun-v${ver}/bun-linux-${bunArch}.zip`, dlNet),
        filename: `bun-linux-${bunArch}-${ver}.zip`,
        sha256: "",
        sizeBytes: 36 * 1024 * 1024,
      }));
    }
  } catch {}
  return [
    {version: "1.2.4", channel: "stable", url: githubDownloadUrl(`https://github.com/oven-sh/bun/releases/download/bun-v1.2.4/bun-linux-${bunArch}.zip`, dlNet), filename: `bun-linux-${bunArch}-1.2.4.zip`, sha256: "", sizeBytes: 36 * 1024 * 1024},
    {version: "1.1.45", channel: "archive", url: githubDownloadUrl(`https://github.com/oven-sh/bun/releases/download/bun-v1.1.45/bun-linux-${bunArch}.zip`, dlNet), filename: `bun-linux-${bunArch}-1.1.45.zip`, sha256: "", sizeBytes: 35 * 1024 * 1024},
  ];
}

async function fetchAndroidSdkVersions() {
  try {
    const xml = await fetchText("https://dl.google.com/android/repository/repository2-3.xml", 15_000);
    const cmds = [...xml.matchAll(/commandlinetools-linux-(\d+)_latest\.zip/g)]
      .map(m => ({num: Number(m[1]), file: m[0]}))
      .sort((a, b) => b.num - a.num);
    const cmdFile = cmds[0]?.file || "commandlinetools-linux-11076708_latest.zip";
    const cmdUrl = `https://dl.google.com/android/repository/${cmdFile}`;
    const apis = [...new Set([...xml.matchAll(/path="platforms;android-(\d+)"/g)].map(m => Number(m[1])))]
      .filter(n => n >= 28)
      .sort((a, b) => b - a);
    const bts = [...new Set([...xml.matchAll(/path="build-tools;(\d+\.\d+\.\d+)"/g)].map(m => m[1]))]
      .sort((a, b) => b.localeCompare(a, undefined, {numeric: true}));
    if (apis.length) {
      return apis.slice(0, 10).map((api, idx) => {
        const matchBt = bts.find(b => b.startsWith(`${api}.`)) || bts[0] || `${api}.0.0`;
        return {
          version: `android-${api}`,
          channel: idx === 0 ? "stable" : `API ${api}`,
          url: cmdUrl,
          cmdlineToolsUrl: cmdUrl,
          buildToolsVersion: matchBt,
          filename: cmdFile,
          sha256: "",
          sizeBytes: 185 * 1024 * 1024,
        };
      });
    }
  } catch {}
  const fallbackCmd = "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip";
  return [36, 35, 34, 33, 31].map((api, idx) => ({
    version: `android-${api}`,
    channel: idx === 0 ? "stable" : `API ${api}`,
    url: fallbackCmd,
    cmdlineToolsUrl: fallbackCmd,
    buildToolsVersion: `${api}.0.0`,
    filename: "commandlinetools-linux-latest.zip",
    sha256: "",
    sizeBytes: 185 * 1024 * 1024,
  }));
}

async function fetchGradleVersions() {
  const arr = await fetchJson("https://services.gradle.org/versions/all", 15_000);
  const out = [];
  const seenMinor = new Map();
  for (const x of arr || []) {
    if (x.snapshot || x.nightly || x.releaseNightly || x.rcFor || x.milestoneFor || x.broken || !x.downloadUrl) continue;
    const ver = String(x.version || "").trim();
    if (!/^\d+\.\d+(?:\.\d+)?$/.test(ver)) continue;
    const major = Number(ver.split(".")[0]) || 0;
    if (major < 7) break;
    const minor = ver.split(".").slice(0, 2).join(".");
    const cnt = seenMinor.get(minor) || 0;
    if (cnt >= 2) continue;
    seenMinor.set(minor, cnt + 1);
    out.push({
      version: ver,
      channel: out.length === 0 ? "stable" : "archive",
      url: x.downloadUrl,
      checksumUrl: x.checksumUrl || `${x.downloadUrl}.sha256`,
      filename: `gradle-${ver}-bin.zip`,
      sha256: "",
      sizeBytes: 135 * 1024 * 1024,
    });
    if (out.length >= 24) break;
  }
  if (out.length) return out;
  throw new Error("无法获取 Gradle 官方历史版本列表");
}

async function fetchKotlinVersions(dlNet = {}) {
  const preferCn = (dlNet.npmRegistry || "").includes("npmmirror");
  const urls = preferCn
    ? [
      "https://maven.aliyun.com/repository/public/org/jetbrains/kotlin/kotlin-compiler/maven-metadata.xml",
      "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler/maven-metadata.xml",
    ]
    : [
      "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler/maven-metadata.xml",
      "https://maven.aliyun.com/repository/public/org/jetbrains/kotlin/kotlin-compiler/maven-metadata.xml",
    ];
  for (const metaUrl of urls) {
    try {
      const xml = await fetchText(metaUrl, 12_000);
      const vers = [...xml.matchAll(/<version>(\d+\.\d+\.\d+)<\/version>/g)].map(m => m[1]).reverse();
      const out = [];
      const seenMinor = new Map();
      for (const ver of vers) {
        const major = Number(ver.split(".")[0]) || 0;
        const minorNum = Number(ver.split(".")[1]) || 0;
        if (major < 1 || (major === 1 && minorNum < 8)) break;
        const minor = `${major}.${minorNum}`;
        if ((seenMinor.get(minor) || 0) >= 2) continue;
        seenMinor.set(minor, (seenMinor.get(minor) || 0) + 1);
        const rawUrl = `https://github.com/JetBrains/kotlin/releases/download/v${ver}/kotlin-compiler-${ver}.zip`;
        out.push({
          version: ver,
          channel: out.length === 0 ? "stable" : "archive",
          url: githubDownloadUrl(rawUrl, dlNet),
          filename: `kotlin-compiler-${ver}.zip`,
          sha256: "",
          sizeBytes: 86 * 1024 * 1024,
        });
        if (out.length >= 20) break;
      }
      if (out.length) return out;
    } catch {}
  }
  throw new Error("无法获取 Kotlin 官方版本索引");
}

async function fetchMavenVersions(dlNet = {}) {
  const preferCn = (dlNet.npmRegistry || "").includes("npmmirror");
  const bases = preferCn
    ? ["https://maven.aliyun.com/repository/public", "https://repo.maven.apache.org/maven2"]
    : ["https://repo.maven.apache.org/maven2", "https://maven.aliyun.com/repository/public"];
  for (const base of bases) {
    try {
      const xml = await fetchText(`${base}/org/apache/maven/apache-maven/maven-metadata.xml`, 12_000);
      const vers = [...xml.matchAll(/<version>(\d+\.\d+\.\d+)<\/version>/g)].map(m => m[1]).reverse();
      const out = [];
      const seenMinor = new Map();
      for (const ver of vers) {
        const major = Number(ver.split(".")[0]) || 0;
        if (major < 3) break;
        const minor = ver.split(".").slice(0, 2).join(".");
        if ((seenMinor.get(minor) || 0) >= 3) continue;
        seenMinor.set(minor, (seenMinor.get(minor) || 0) + 1);
        const dlUrl = `${base}/org/apache/maven/apache-maven/${ver}/apache-maven-${ver}-bin.tar.gz`;
        out.push({
          version: ver,
          channel: out.length === 0 ? "stable" : "archive",
          url: dlUrl,
          checksumUrl: `${dlUrl}.sha512`,
          filename: `apache-maven-${ver}-bin.tar.gz`,
          sha256: "",
          sizeBytes: 9 * 1024 * 1024,
        });
        if (out.length >= 18) break;
      }
      if (out.length) return out;
    } catch {}
  }
  throw new Error("无法获取 Apache Maven 版本索引");
}

async function fetchDotnetVersions() {
  const rid = process.arch === "arm64" ? "linux-arm64" : "linux-x64";
  const idx = await fetchJson("https://builds.dotnet.microsoft.com/dotnet/release-metadata/releases-index.json", 15_000);
  const channels = (idx["releases-index"] || [])
    .filter(c => Number(String(c["channel-version"] || "0").split(".")[0]) >= 8)
    .slice(0, 4);
  const perCh = await Promise.all(channels.map(async ch => {
    try {
      const meta = await fetchJson(ch["releases.json"], 12_000);
      const isLts = String(ch["release-type"] || "").toLowerCase() === "lts";
      const out = [];
      for (const rel of (meta.releases || []).slice(0, 6)) {
        const sdk = rel?.sdk;
        const file = sdk?.files?.find(f => f.rid === rid && String(f.name || "").endsWith(".tar.gz"));
        if (!file?.url || !sdk?.version) continue;
        if (!/^\d+\.\d+\.\d+$/.test(sdk.version)) continue;
        out.push({
          version: sdk.version,
          channel: isLts ? `LTS (.NET ${ch["channel-version"]})` : `.NET ${ch["channel-version"]}`,
          url: file.url,
          filename: `dotnet-sdk-${sdk.version}-${rid}.tar.gz`,
          sha256: "",
          sha512: String(file.hash || "").toLowerCase(),
          sizeBytes: 215 * 1024 * 1024,
        });
        if (out.length >= 3) break;
      }
      return out;
    } catch { return []; }
  }));
  const merged = perCh.flat();
  if (merged.length) return merged;
  throw new Error("无法获取 .NET SDK 官方发布清单");
}

async function fetchDartVersions(dlNet = {}) {
  const dartArch = process.arch === "arm64" ? "arm64" : "x64";
  const preferCn = (dlNet.npmRegistry || "").includes("npmmirror");
  const dlBase = preferCn
    ? "https://storage.flutter-io.cn/dart-archive"
    : "https://storage.googleapis.com/dart-archive";
  const buildEntry = (ver, idx) => {
    const dlUrl = `${dlBase}/channels/stable/release/${ver}/sdk/dartsdk-linux-${dartArch}-release.zip`;
    return {
      version: ver,
      channel: idx === 0 ? "stable" : "archive",
      url: dlUrl,
      checksumUrl: `${dlUrl}.sha256sum`,
      filename: `dartsdk-linux-${dartArch}-${ver}.zip`,
      sha256: "",
      sizeBytes: 250 * 1024 * 1024,
    };
  };
  try {
    const list = await fetchJson("https://storage.googleapis.com/storage/v1/b/dart-archive/o?prefix=channels/stable/release/&delimiter=/&maxResults=300", 12_000);
    const vers = (list.prefixes || [])
      .map(p => p.split("/").filter(Boolean).pop())
      .filter(v => /^\d+\.\d+\.\d+$/.test(v))
      .sort((a, b) => b.localeCompare(a, undefined, {numeric: true}));
    const out = [];
    const seenMinor = new Map();
    for (const ver of vers) {
      const major = Number(ver.split(".")[0]) || 0;
      if (major < 3) break;
      const minor = ver.split(".").slice(0, 2).join(".");
      if ((seenMinor.get(minor) || 0) >= 2) continue;
      seenMinor.set(minor, (seenMinor.get(minor) || 0) + 1);
      out.push(buildEntry(ver, out.length));
      if (out.length >= 20) break;
    }
    if (out.length) return out;
  } catch {}
  try {
    const latest = await fetchJson(`${dlBase}/channels/stable/release/latest/VERSION`, 10_000);
    const lv = String(latest?.version || "").trim();
    const fallbacks = [...new Set([lv, "3.7.2", "3.6.2", "3.5.4", "3.4.4"].filter(v => /^\d+\.\d+\.\d+$/.test(v)))];
    return fallbacks.map((ver, idx) => buildEntry(ver, idx));
  } catch {}
  return ["3.7.2", "3.6.2", "3.5.4", "3.4.4"].map((ver, idx) => buildEntry(ver, idx));
}

async function fetchWasiSdkVersions(dlNet = {}) {
  const wasiArch = process.arch === "arm64" ? "arm64" : "x86_64";
  try {
    const atom = await fetchText("https://github.com/WebAssembly/wasi-sdk/releases.atom", 12_000);
    // arm64-linux 原生预编译包从 wasi-sdk 24 起稳定发布
    const nums = [...new Set([...atom.matchAll(/<title>wasi-sdk-(\d+)<\/title>/g)].map(m => Number(m[1])))]
      .filter(n => n >= 24)
      .sort((a, b) => b - a);
    if (nums.length) {
      return nums.slice(0, 12).map((n, idx) => {
        const ver = `${n}.0`;
        const rawUrl = `https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${n}/wasi-sdk-${ver}-${wasiArch}-linux.tar.gz`;
        return {
          version: ver,
          channel: idx === 0 ? "stable" : "archive",
          url: githubDownloadUrl(rawUrl, dlNet),
          filename: `wasi-sdk-${ver}-${wasiArch}-linux.tar.gz`,
          sha256: "",
          sizeBytes: 184 * 1024 * 1024,
        };
      });
    }
  } catch {}
  return ["25.0", "24.0"].map((ver, idx) => {
    const n = ver.split(".")[0];
    return {
      version: ver,
      channel: idx === 0 ? "stable" : "archive",
      url: githubDownloadUrl(`https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${n}/wasi-sdk-${ver}-${wasiArch}-linux.tar.gz`, dlNet),
      filename: `wasi-sdk-${ver}-${wasiArch}-linux.tar.gz`,
      sha256: "",
      sizeBytes: 184 * 1024 * 1024,
    };
  });
}

function defaultRustVersions() {
  return [
    {version: "stable", channel: "channel", sizeBytes: 260 * 1024 * 1024},
    {version: "beta", channel: "channel", sizeBytes: 260 * 1024 * 1024},
    {version: "nightly", channel: "channel", sizeBytes: 280 * 1024 * 1024},
    {version: "1.85.0", channel: "archive", sizeBytes: 260 * 1024 * 1024},
    {version: "1.84.1", channel: "archive", sizeBytes: 260 * 1024 * 1024},
    {version: "1.83.0", channel: "archive", sizeBytes: 260 * 1024 * 1024},
    {version: "1.82.0", channel: "archive", sizeBytes: 255 * 1024 * 1024},
    {version: "1.80.1", channel: "archive", sizeBytes: 250 * 1024 * 1024},
    {version: "1.78.0", channel: "archive", sizeBytes: 245 * 1024 * 1024},
    {version: "1.75.0", channel: "archive", sizeBytes: 240 * 1024 * 1024},
  ];
}

function defaultPythonVersions() {
  return [
    {version: "3.13", channel: "stable", sizeBytes: 42 * 1024 * 1024},
    {version: "3.12", channel: "LTS 推荐", sizeBytes: 42 * 1024 * 1024},
    {version: "3.11", channel: "archive", sizeBytes: 40 * 1024 * 1024},
    {version: "3.10", channel: "archive", sizeBytes: 38 * 1024 * 1024},
    {version: "3.9", channel: "archive", sizeBytes: 36 * 1024 * 1024},
    {version: "3.8", channel: "archive", sizeBytes: 35 * 1024 * 1024},
  ];
}

async function fetchCppAptVersions() {
  try {
    const {stdout} = await execFileAsync("apt-cache", ["policy", "gcc", "clang", "cmake"], {timeout: 8000});
    const cand = /Candidate:\s*(\S+)/.exec(stdout)?.[1] || "ubuntu-24.04";
    return [{version: cand, channel: "apt-repo", sizeBytes: 320 * 1024 * 1024}];
  } catch {
    return [{version: "ubuntu-24.04", channel: "apt-repo", sizeBytes: 320 * 1024 * 1024}];
  }
}

/** 获取指定编译器的可用版本清单（含最新稳定版与历史归档版本），支持自定义版本。 */
export async function fetchAvailableVersions({home, id, dlNet = {}, refresh = false} = {}) {
  if (!refresh && id !== "cpp-apt") {
    const cached = await readVersionCache(home, id);
    if (cached) return {ok: true, id, cached: true, versions: cached};
  }
  let versions;
  switch (id) {
    case "go": versions = await fetchGoVersions(); break;
    case "zig": versions = await fetchZigVersions(); break;
    case "node": versions = await fetchNodeVersions(dlNet); break;
    case "java": versions = await fetchJavaVersions(); break;
    case "pnpm": versions = await fetchPnpmVersions(dlNet); break;
    case "bun": versions = await fetchBunVersions(dlNet); break;
    case "rust": versions = defaultRustVersions(); break;
    case "python": versions = defaultPythonVersions(); break;
    case "cpp-apt": versions = await fetchCppAptVersions(); break;
    case "android-sdk": versions = await fetchAndroidSdkVersions(); break;
    case "gradle": versions = await fetchGradleVersions(); break;
    case "kotlin": versions = await fetchKotlinVersions(dlNet); break;
    case "maven": versions = await fetchMavenVersions(dlNet); break;
    case "dotnet": versions = await fetchDotnetVersions(); break;
    case "dart": versions = await fetchDartVersions(dlNet); break;
    case "wasi-sdk": versions = await fetchWasiSdkVersions(dlNet); break;
    default: throw new Error(`未知工具链：${id}`);
  }
  if (id !== "cpp-apt") await writeVersionCache(home, id, versions);
  return {ok: true, id, cached: false, versions};
}

/** 根据用户选择的版本（或自定义版本号）解析精确下载地址与 SHA-256/SHA-512。 */
export async function resolveVersionDownload({home, id, version, dlNet = {}} = {}) {
  const requested = version ? assertSafeVersion(version) : "";
  const {versions} = requested
    ? await fetchAvailableVersions({home, id, dlNet}).catch(() => ({versions: []}))
    : await fetchAvailableVersions({home, id, dlNet});
  if (!requested) {
    const picked = versions.find(v => /stable|lts|current|channel/i.test(v.channel)) || versions[0];
    if (!picked) throw new Error(`未找到 ${id} 可用版本`);
    return picked;
  }
  const normalizedReq = id === "android-sdk" && /^\d+$/.test(requested) ? `android-${requested}` : requested;
  const exact = versions.find(v => v.version === normalizedReq || v.version === normalizedReq.replace(/^v|^go/, ""));
  if (exact) return exact;
  // 支持用户输入不在缓存列表中的精确历史版本号
  const clean = normalizedReq.replace(/^v|^go/, "");
  const goArch = process.arch === "arm64" ? "arm64" : "amd64";
  const nodeArch = process.arch === "arm64" ? "arm64" : "x64";
  const gnuArch = process.arch === "arm64" ? "aarch64" : "x86_64";
  if (id === "go") {
    const base = (dlNet.npmRegistry || "").includes("npmmirror") ? "https://golang.google.cn/dl" : "https://go.dev/dl";
    return {version: clean, channel: "custom", url: `${base}/go${clean}.linux-${goArch}.tar.gz`, filename: `go${clean}.linux-${goArch}.tar.gz`, sha256: "", sizeBytes: 0};
  }
  if (id === "node") {
    const base = (dlNet.npmRegistry || "").includes("npmmirror") ? "https://npmmirror.com/mirrors/node" : "https://nodejs.org/dist";
    const filename = `node-v${clean}-linux-${nodeArch}.tar.gz`;
    return {version: clean, channel: "custom", url: `${base}/v${clean}/${filename}`, shasumsUrl: `${base}/v${clean}/SHASUMS256.txt`, filename, sha256: "", sizeBytes: 0};
  }
  if (id === "zig") {
    return {version: clean, channel: "custom", url: `https://ziglang.org/download/${clean}/zig-linux-${gnuArch}-${clean}.tar.xz`, filename: `zig-linux-${gnuArch}-${clean}.tar.xz`, sha256: "", sizeBytes: 0};
  }
  if (id === "bun") {
    const bunArch = process.arch === "arm64" ? "aarch64" : "x64";
    return {version: clean, channel: "custom", url: githubDownloadUrl(`https://github.com/oven-sh/bun/releases/download/bun-v${clean}/bun-linux-${bunArch}.zip`, dlNet), filename: `bun-linux-${bunArch}-${clean}.zip`, sha256: "", sizeBytes: 0};
  }
  if (id === "gradle") {
    const u = `https://services.gradle.org/distributions/gradle-${clean}-bin.zip`;
    return {version: clean, channel: "custom", url: u, checksumUrl: `${u}.sha256`, filename: `gradle-${clean}-bin.zip`, sha256: "", sizeBytes: 0};
  }
  if (id === "kotlin") {
    const rawUrl = `https://github.com/JetBrains/kotlin/releases/download/v${clean}/kotlin-compiler-${clean}.zip`;
    return {version: clean, channel: "custom", url: githubDownloadUrl(rawUrl, dlNet), filename: `kotlin-compiler-${clean}.zip`, sha256: "", sizeBytes: 0};
  }
  if (id === "maven") {
    const base = (dlNet.npmRegistry || "").includes("npmmirror") ? "https://maven.aliyun.com/repository/public" : "https://repo.maven.apache.org/maven2";
    const u = `${base}/org/apache/maven/apache-maven/${clean}/apache-maven-${clean}-bin.tar.gz`;
    return {version: clean, channel: "custom", url: u, checksumUrl: `${u}.sha512`, filename: `apache-maven-${clean}-bin.tar.gz`, sha256: "", sizeBytes: 0};
  }
  if (id === "dotnet") {
    const rid = process.arch === "arm64" ? "linux-arm64" : "linux-x64";
    return {version: clean, channel: "custom", url: `https://builds.dotnet.microsoft.com/dotnet/Sdk/${clean}/dotnet-sdk-${clean}-${rid}.tar.gz`, filename: `dotnet-sdk-${clean}-${rid}.tar.gz`, sha256: "", sizeBytes: 0};
  }
  if (id === "dart") {
    const dartArch = process.arch === "arm64" ? "arm64" : "x64";
    const dlBase = (dlNet.npmRegistry || "").includes("npmmirror") ? "https://storage.flutter-io.cn/dart-archive" : "https://storage.googleapis.com/dart-archive";
    const u = `${dlBase}/channels/stable/release/${clean}/sdk/dartsdk-linux-${dartArch}-release.zip`;
    return {version: clean, channel: "custom", url: u, checksumUrl: `${u}.sha256sum`, filename: `dartsdk-linux-${dartArch}-${clean}.zip`, sha256: "", sizeBytes: 0};
  }
  if (id === "wasi-sdk") {
    const wasiArch = process.arch === "arm64" ? "arm64" : "x86_64";
    const tagNum = clean.split(".")[0];
    const normVer = clean.includes(".") ? clean : `${clean}.0`;
    const rawUrl = `https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${tagNum}/wasi-sdk-${normVer}-${wasiArch}-linux.tar.gz`;
    return {version: normVer, channel: "custom", url: githubDownloadUrl(rawUrl, dlNet), filename: `wasi-sdk-${normVer}-${wasiArch}-linux.tar.gz`, sha256: "", sizeBytes: 0};
  }
  if (id === "android-sdk") {
    const api = clean.replace(/^android-/, "");
    const cmdUrl = versions[0]?.cmdlineToolsUrl || "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip";
    return {version: `android-${api}`, channel: "custom", url: cmdUrl, cmdlineToolsUrl: cmdUrl, buildToolsVersion: `${api}.0.0`, filename: "commandlinetools-linux-latest.zip", sha256: "", sizeBytes: 0};
  }
  return {version: requested, channel: "custom", url: "", filename: "", sha256: "", sizeBytes: 0};
}
