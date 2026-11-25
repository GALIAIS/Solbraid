const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');

function readManifest(file) {
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));
  const version = /^[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?$/;
  const filename = /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/;
  if (data.formatVersion !== 1 || [data.engines?.codex, data.engines?.claude, data.engines?.antigravity, data.sdk?.version].some(value => typeof value !== 'string' || !version.test(value)) ||
      typeof data.sdk?.archive !== 'string' || !filename.test(data.sdk.archive) || typeof data.sdk?.sha512 !== 'string' ||
      !/^[A-Za-z0-9+/]{86}==$/.test(data.sdk?.sha512)) throw new Error('Invalid runtime component manifest');
  if (typeof data.plan?.version !== 'string' || !version.test(data.plan.version) || typeof data.plan?.archive !== 'string' ||
      !filename.test(data.plan.archive) || !/^[a-f0-9]{64}$/.test(data.plan?.sha256)) throw new Error('Invalid official Plan component');
  if (!Array.isArray(data.runtimeFiles) || !data.runtimeFiles.length || new Set(data.runtimeFiles).size !== data.runtimeFiles.length ||
      data.runtimeFiles.some(name => typeof name !== 'string' || !filename.test(name))) throw new Error('Invalid runtime file list');
  if (!Array.isArray(data.terminal) || data.terminal.length !== 4 || data.terminal.some(pkg =>
    typeof pkg.name !== 'string' || !version.test(pkg.version) || !filename.test(pkg.archive) || !/^[A-Za-z0-9+/]{86}==$/.test(pkg.sha512) ||
    !pkg.files || Object.values(pkg.files).some(name => !filename.test(name)))) throw new Error('Invalid terminal component list');
  if (!data.native || Object.keys(data.native).sort().join(',') !== 'arm64-v8a,x86_64') throw new Error('Invalid runtime architectures');
  for (const abi of ['arm64-v8a', 'x86_64']) {
    const entries = data.native?.[abi];
    if (!Array.isArray(entries) || !entries.length || new Set(entries.map(entry => entry.name)).size !== entries.length)
      throw new Error(`Invalid native component list: ${abi}`);
    for (const entry of entries) {
      if (typeof entry.name !== 'string' || !/^lib[a-zA-Z0-9_-]+\.so$/.test(entry.name) || !['offline', 'jniLibs'].includes(entry.source) ||
          !Number.isSafeInteger(entry.size) || entry.size <= 0 || !/^[a-f0-9]{64}$/.test(entry.sha256))
        throw new Error(`Invalid native component: ${abi}/${entry.name}`);
    }
  }
  return data;
}

function digestFile(file, algorithm = 'sha256', encoding = 'hex') {
  const hash = crypto.createHash(algorithm);
  const fd = fs.openSync(file, 'r');
  try {
    const buffer = Buffer.alloc(1024 * 1024);
    let length;
    while ((length = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) hash.update(buffer.subarray(0, length));
    return hash.digest(encoding);
  } finally { fs.closeSync(fd); }
}

function archiveFiles(archive, stripPrefix) {
  const tar = zlib.gunzipSync(archive, {maxOutputLength:64 * 1024 * 1024});
  const files = new Map();
  for (let offset = 0; offset + 512 <= tar.length;) {
    const header = tar.subarray(offset, offset + 512);
    if (header.every(value => value === 0)) break;
    const name = header.subarray(0, 100).toString('utf8').split('\0')[0];
    const prefix = header.subarray(345, 500).toString('utf8').split('\0')[0];
    const fullName = prefix ? `${prefix}/${name}` : name;
    const sizeText = header.subarray(124, 136).toString('ascii').replace(/\0/g, '').trim();
    if (!/^[0-7]+$/.test(sizeText)) throw new Error('Invalid SDK archive size');
    const size = Number.parseInt(sizeText, 8);
    const next = offset + 512 + Math.ceil(size / 512) * 512;
    if (!Number.isSafeInteger(next) || next > tar.length || !fullName.startsWith(stripPrefix)) throw new Error('Invalid component archive entry');
    const relative = fullName.slice(stripPrefix.length);
    if (relative.split('/').some(part => part === '..') || relative.includes('\\') || path.posix.isAbsolute(relative))
      throw new Error('SDK archive path escapes its package');
    if (header[156] === 0 || header[156] === 48) {
      if (!relative || relative.split('/').some(part => !/^[a-zA-Z0-9._-]+$/.test(part)) || files.has(relative)) throw new Error('Invalid or duplicate SDK archive file');
      const bytes = tar.subarray(offset + 512, offset + 512 + size);
      files.set(relative, {bytes, sha256:crypto.createHash('sha256').update(bytes).digest('hex'), size});
    } else if (header[156] !== 53) throw new Error('Unsupported SDK archive entry type');
    offset = next;
  }
  return files;
}

function sdkFiles(archive, spec, cliVersion) {
  if (crypto.createHash('sha512').update(archive).digest('base64') !== spec.sha512)
    throw new Error(`Claude Agent SDK checksum mismatch: ${spec.version}`);
  const files = archiveFiles(archive, 'package/');
  for (const required of ['sdk.mjs', 'core.mjs', 'package.json', 'manifest.json', 'LICENSE.md']) {
    if (!files.has(required)) throw new Error(`Missing SDK component: ${required}`);
  }
  const packageInfo = JSON.parse(files.get('package.json').bytes.toString('utf8'));
  if (packageInfo.version !== spec.version || packageInfo.claudeCodeVersion !== cliVersion)
    throw new Error('Claude CLI and SDK versions do not match');
  return files;
}

function planFiles(archive, spec) {
  if (crypto.createHash('sha256').update(archive).digest('hex') !== spec.sha256) throw new Error('Official Plan checksum mismatch');
  const files = archiveFiles(archive, '');
  for (const required of ['.codex-plugin/plugin.json', 'skills/plan/SKILL.md', 'skills/plan/references/brief/markdown.md']) {
    if (!files.has(required)) throw new Error(`Missing Plan component: ${required}`);
  }
  const info = JSON.parse(files.get('.codex-plugin/plugin.json').bytes.toString('utf8'));
  if (info.name !== 'plan' || info.version !== spec.version) throw new Error('Official Plan version mismatch');
  return files;
}

function terminalFiles(archive, spec) {
  if (crypto.createHash('sha512').update(archive).digest('base64') !== spec.sha512) throw new Error('Terminal package checksum mismatch: ' + spec.name);
  const entries = archiveFiles(archive, 'package/');
  const metadata = JSON.parse(entries.get('package.json')?.bytes.toString('utf8') || '{}');
  if (metadata.name !== spec.name || metadata.version !== spec.version) throw new Error('Terminal package version mismatch');
  return Object.entries(spec.files).map(([source, destination]) => {
    const file = entries.get(source);
    if (!file) throw new Error('Missing terminal component: ' + source);
    return {name: destination, bytes: file.bytes};
  });
}

module.exports = {readManifest, digestFile, sdkFiles, planFiles, terminalFiles};
