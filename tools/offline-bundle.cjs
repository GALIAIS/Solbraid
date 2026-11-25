const fs = require('node:fs');
const path = require('node:path');
const {readManifest, digestFile} = require('./component-manifest.cjs');

// 将预置的离线 Ubuntu 根系统与官方 CLI 原生组件按 ABI 注入 generated jniLibs，由 AGP Split APK 按架构分包。
const [offlineDir, destination, manifestFile = path.join(__dirname, '../runtime/components.json'), abiFilterArg = ''] = process.argv.slice(2);
if (!offlineDir || !destination) throw new Error('Usage: offline-bundle.cjs <offline-dir> <output-dir> [components.json] [abi-list]');
const output = path.resolve(destination);
if (path.basename(output) !== 'offlineJniLibs') throw new Error('Unexpected jniLibs output directory: ' + output);
const manifest = readManifest(manifestFile);
const targetAbis = abiFilterArg
  ? abiFilterArg.split(',').map(s => s.trim()).filter(Boolean)
  : ['arm64-v8a', 'x86_64'];
for (const abi of targetAbis) {
  if (!Object.hasOwn(manifest.native, abi)) throw new Error(`Unsupported target ABI: ${abi}`);
}
const sources = [];
for (const abi of targetAbis) {
  const entries = manifest.native[abi] || [];
  for (const entry of entries.filter(item => item.source === 'offline')) {
    const source = path.join(offlineDir, abi, entry.name);
    if (!fs.statSync(source).isFile() || fs.statSync(source).size !== entry.size || digestFile(source) !== entry.sha256)
      throw new Error(`Offline component integrity mismatch: ${abi}/${entry.name}`);
    sources.push({abi, source, name:entry.name});
  }
}
fs.rmSync(output, {recursive: true, force: true});

for (const abi of targetAbis) {
  const dstAbi = path.join(output, abi);
  fs.mkdirSync(dstAbi, {recursive: true});
  for (const entry of sources.filter(item => item.abi === abi)) {
    fs.copyFileSync(entry.source, path.join(dstAbi, entry.name));
  }
}
console.log(JSON.stringify({abis:targetAbis, verifiedFiles:sources.length, output}));
