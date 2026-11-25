const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const {Writable} = require('node:stream');
const {pipeline} = require('node:stream/promises');
const {readManifest, digestFile} = require('./component-manifest.cjs');

const [apkDirectory, assetDirectory, reportFile] = process.argv.slice(2);
if (!apkDirectory || !assetDirectory || !reportFile) throw new Error('Usage: verify-runtime-package.cjs <apk-dir> <runtime-assets-dir> <report.json>');

function readExactly(fd, size, offset) {
  const result = Buffer.alloc(size);
  let count = 0;
  while (count < size) {
    const length = fs.readSync(fd, result, count, size - count, offset + count);
    if (!length) throw new Error('Truncated APK');
    count += length;
  }
  return result;
}

function zipEntries(file) {
  const fd = fs.openSync(file, 'r');
  try {
    const size = fs.fstatSync(fd).size;
    const tailSize = Math.min(size, 65557);
    const tail = readExactly(fd, tailSize, size - tailSize);
    let end = tail.length - 22;
    while (end >= 0 && (tail.readUInt32LE(end) !== 0x06054b50 || end + 22 + tail.readUInt16LE(end + 20) !== tail.length)) end--;
    if (end < 0) throw new Error('APK central directory was not found');
    const count = tail.readUInt16LE(end + 10), length = tail.readUInt32LE(end + 12), offset = tail.readUInt32LE(end + 16);
    if (tail.readUInt16LE(end + 4) || tail.readUInt16LE(end + 6) || count === 65535 || length > 64 * 1024 * 1024 || offset + length > size - tailSize + end)
      throw new Error('Unsupported or invalid APK archive');
    const central = readExactly(fd, length, offset), result = new Map();
    let cursor = 0;
    for (let i = 0; i < count; i++) {
      if (cursor + 46 > central.length || central.readUInt32LE(cursor) !== 0x02014b50) throw new Error('Invalid APK directory entry');
      const nameLength = central.readUInt16LE(cursor + 28), extraLength = central.readUInt16LE(cursor + 30), commentLength = central.readUInt16LE(cursor + 32);
      const next = cursor + 46 + nameLength + extraLength + commentLength;
      if (next > central.length) throw new Error('Truncated APK directory entry');
      const name = central.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
      if (result.has(name) || name.startsWith('/') || name.includes('\\') || name.split('/').includes('..')) throw new Error('Invalid or duplicate APK path');
      const entry = {name, method:central.readUInt16LE(cursor + 10), compressedSize:central.readUInt32LE(cursor + 20), size:central.readUInt32LE(cursor + 24), offset:central.readUInt32LE(cursor + 42)};
      if (central.readUInt16LE(cursor + 8) & 1 || entry.offset === 0xffffffff || entry.size === 0xffffffff) throw new Error('Unsupported APK entry encoding');
      result.set(name, entry);
      cursor = next;
    }
    return result;
  } finally { fs.closeSync(fd); }
}

async function verifyEntry(apk, entries, name, expected) {
  const entry = entries.get(name);
  if (!entry || entry.size !== expected.size) throw new Error(`Missing or incomplete APK component: ${name}`);
  const fd = fs.openSync(apk, 'r');
  let bodyOffset;
  try {
    const local = readExactly(fd, 30, entry.offset);
    if (local.readUInt32LE(0) !== 0x04034b50) throw new Error(`Invalid APK local header: ${name}`);
    const nameLength = local.readUInt16LE(26), extraLength = local.readUInt16LE(28);
    if (readExactly(fd, nameLength, entry.offset + 30).toString('utf8') !== name) throw new Error('APK local path does not match its directory');
    bodyOffset = entry.offset + 30 + nameLength + extraLength;
    if (bodyOffset + entry.compressedSize > fs.fstatSync(fd).size) throw new Error(`Truncated APK component: ${name}`);
  } finally { fs.closeSync(fd); }
  const hash = crypto.createHash('sha256');
  let size = 0;
  const sink = new Writable({write(chunk, encoding, done) {
    size += chunk.length;
    if (size > expected.size) { done(new Error(`APK component exceeds expected size: ${name}`)); return; }
    hash.update(chunk); done();
  }});
  if (entry.compressedSize) {
    const input = fs.createReadStream(apk, {start:bodyOffset, end:bodyOffset + entry.compressedSize - 1});
    if (entry.method === 8) await pipeline(input, zlib.createInflateRaw(), sink);
    else if (entry.method === 0) await pipeline(input, sink);
    else { input.destroy(); throw new Error(`Unsupported APK compression: ${name}`); }
  }
  if (size !== expected.size || hash.digest('hex') !== expected.sha256) throw new Error(`APK component checksum mismatch: ${name}`);
  return {name, size, sha256:expected.sha256};
}

async function verify() {
  const assets = path.join(assetDirectory, 'galiais', 'runtime');
  const components = readManifest(path.join(assets, 'components.json'));
  const manifestPath = path.join(assets, 'package-manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  if (manifest.formatVersion !== 1 || !Array.isArray(manifest.files) || JSON.stringify(manifest.native) !== JSON.stringify(components.native)) throw new Error('Package manifest does not match runtime components');
  const expectedAssets = [...manifest.files, {name:'package-manifest.json', size:fs.statSync(manifestPath).size, sha256:digestFile(manifestPath)}];
  const listing = JSON.parse(fs.readFileSync(path.join(apkDirectory, 'output-metadata.json'), 'utf8'));
  if (listing.applicationId !== 'com.galiais.agent' || !Array.isArray(listing.elements) || !listing.elements.length) throw new Error('Invalid APK build metadata');
  const packages = [];
  for (const element of listing.elements) {
    if (typeof element.outputFile !== 'string' || !/^[a-zA-Z0-9._-]+\.apk$/.test(element.outputFile)) throw new Error('Invalid APK output path');
    const abi = element.filters?.find(filter => filter.filterType === 'ABI')?.value;
    if (!Object.hasOwn(components.native, abi)) throw new Error('APK architecture is not declared in the component manifest');
    const apk = path.join(apkDirectory, element.outputFile), entries = zipEntries(apk);
    const runtimePrefix = 'assets/galiais/runtime/';
    const assetNames = new Set(expectedAssets.map(entry => runtimePrefix + entry.name));
    for (const name of entries.keys()) {
      if (name.startsWith(runtimePrefix) && !name.endsWith('/') && !assetNames.has(name)) throw new Error(`Undeclared runtime asset in APK: ${name}`);
      if (name.startsWith('lib/') && name.split('/')[1] !== abi) throw new Error('Unexpected architecture in split APK');
    }
    const verified = [];
    for (const expected of components.native[abi]) verified.push(await verifyEntry(apk, entries, `lib/${abi}/${expected.name}`, expected));
    for (const expected of expectedAssets) verified.push(await verifyEntry(apk, entries, runtimePrefix + expected.name, expected));
    packages.push({file:element.outputFile, abi, nativeFiles:components.native[abi].length, runtimeFiles:expectedAssets.length, verified});
  }
  return {verified:true, engines:components.engines, sdkVersion:components.sdk.version, sdkFiles:manifest.sdkFiles,
    planVersion:components.plan.version, planFiles:manifest.planFiles, packages};
}

verify().then(report => {
  fs.mkdirSync(path.dirname(reportFile), {recursive:true});
  fs.writeFileSync(reportFile, JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({verified:true, packages:report.packages.map(({file, abi, nativeFiles, runtimeFiles}) => ({file, abi, nativeFiles, runtimeFiles})), sdkFiles:report.sdkFiles}));
}).catch(error => {
  fs.mkdirSync(path.dirname(reportFile), {recursive:true});
  fs.writeFileSync(reportFile, JSON.stringify({verified:false, error:error.message}, null, 2) + '\n');
  console.error(error.message); process.exitCode = 1;
});
