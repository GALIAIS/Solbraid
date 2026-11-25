import fs from "node:fs/promises";
import path from "node:path";
import {randomUUID} from "node:crypto";

const CONFIG_FILE = path.join(process.env.HOME || "/home/agent", ".galiais", "download-network.json");
const PROXY_KEYS = ["HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy", "NPM_CONFIG_PROXY", "NPM_CONFIG_HTTPS_PROXY", "npm_config_proxy", "npm_config_https_proxy", "PIP_PROXY"];
const SOURCE_KEYS = ["NPM_CONFIG_REGISTRY", "npm_config_registry", "PIP_INDEX_URL", "UV_INDEX_URL", "UV_DEFAULT_INDEX"];

export function normalizeDownloadNetwork(raw = {}) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) throw new Error("下载网络配置无效");
  function address(value, label, schemes, proxy = false) {
    if (value === undefined || value === null || value === "") return "";
    if (typeof value !== "string") throw new Error(`${label}格式无效`);
    const text = value.trim();
    if (!text) return "";
    let url;
    try { url = new URL(text); } catch { throw new Error(`${label}格式无效`); }
    if (!schemes.includes(url.protocol) || !url.hostname || (url.port && Number(url.port) < 1) || url.search || url.hash || /[\s\x00-\x1f]/.test(text)) {
      throw new Error(`${label}的协议、主机或路径无效`);
    }
    if (proxy && url.pathname && url.pathname !== "/") throw new Error("代理地址不能包含路径");
    return url.toString().replace(/\/+$/, "");
  }
  const proxyMode = raw.proxyMode ?? "system";
  if (!["system", "manual", "none"].includes(proxyMode)) throw new Error("代理方式无效");
  if (raw.preferIpv4 !== undefined && typeof raw.preferIpv4 !== "boolean") throw new Error("IPv4 配置无效");
  const proxyUrl = address(raw.proxyUrl, "代理地址", ["http:", "https:", "socks5:", "socks5h:"], true);
  if (proxyMode === "manual" && !proxyUrl) throw new Error("请填写手动代理地址");
  return {
    npmRegistry: address(raw.npmRegistry, "npm 镜像地址", ["http:", "https:"]),
    pypiIndexUrl: address(raw.pypiIndexUrl, "Python 镜像地址", ["http:", "https:"]),
    githubMirrorPrefix: address(raw.githubMirrorPrefix, "GitHub 仓库镜像地址", ["http:", "https:"]),
    proxyMode,
    proxyUrl: proxyMode === "none" ? "" : proxyUrl,
    preferIpv4: raw.preferIpv4 === true,
  };
}

export async function readDownloadNetwork() {
  let raw;
  try { raw = await fs.readFile(CONFIG_FILE, "utf8"); }
  catch (error) { if (error.code === "ENOENT") return normalizeDownloadNetwork(); throw error; }
  let parsed;
  try { parsed = JSON.parse(raw); } catch { throw new Error("下载网络配置损坏，请重新保存设置"); }
  return normalizeDownloadNetwork(parsed);
}

export async function saveDownloadNetwork(raw) {
  const config = normalizeDownloadNetwork(raw);
  await fs.mkdir(path.dirname(CONFIG_FILE), {recursive: true});
  const tmp = `${CONFIG_FILE}.${randomUUID()}.tmp`;
  try {
    await fs.writeFile(tmp, JSON.stringify(config), {mode: 0o600, flag: "wx"});
    await fs.rename(tmp, CONFIG_FILE);
  } finally { await fs.rm(tmp, {force: true}); }
  return config;
}

/** 只投影网络相关变量；系统未提供代理时保留原环境中的设置。 */
export function downloadEnvironment(config, original = process.env) {
  const env = {};
  for (const key of [...PROXY_KEYS, ...SOURCE_KEYS]) {
    if (original[key] !== undefined) env[key] = original[key];
  }
  const exclusions = new Set([...(original.NO_PROXY || "").split(","), ...(original.no_proxy || "").split(","), "127.0.0.1", "localhost", "::1"].map(s => s.trim()).filter(Boolean));
  env.NO_PROXY = env.no_proxy = [...exclusions].join(",");
  if (config.proxyUrl || config.proxyMode === "none") {
    for (const key of PROXY_KEYS) env[key] = config.proxyUrl;
  }
  if (config.npmRegistry) env.NPM_CONFIG_REGISTRY = env.npm_config_registry = config.npmRegistry;
  if (config.pypiIndexUrl) {
    env.PIP_INDEX_URL = env.UV_INDEX_URL = env.UV_DEFAULT_INDEX = config.pypiIndexUrl;
  }
  if (config.preferIpv4) {
    const options = String(original.NODE_OPTIONS || "").trim();
    env.NODE_OPTIONS = options.includes("--dns-result-order=") ? options : `${options} --dns-result-order=ipv4first`.trim();
  }
  return env;
}

export function githubDownloadUrl(url, config) {
  return config.githubMirrorPrefix && url.startsWith("https://github.com/")
    ? `${config.githubMirrorPrefix}/${url}` : url;
}

export function redactNetworkText(value) {
  return String(value || "")
    .replace(/\x1b\[[0-9;]*[A-Za-z]/g, "")
    .replace(/(Bearer\s+)[^\s]+/gi, "$1[redacted]")
    .replace(/((?:api[_-]?key|token|password|authorization)\s*[=:]\s*)[^\s,;]+/gi, "$1[redacted]")
    .replace(/((?:https?|socks5h?):\/\/)[^\s/@]*@/gi, "$1[redacted]@");
}
