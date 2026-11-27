const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const {readManifest, digestFile, sdkFiles, planFiles, terminalFiles} = require('./component-manifest.cjs');

/** 无外部依赖构造确定性 USTAR tar.gz，将 pc-agent-native Go 源码随 APK 离线分发。 */
function buildDeterministicTarGz(dir) {
  const names = fs.readdirSync(dir).filter(f => (f.endsWith('.go') && !f.endsWith('_test.go')) || f === 'go.mod').sort();
  const chunks = [];
  for (const name of names) {
    const data = Buffer.from(fs.readFileSync(path.join(dir, name), 'utf8').replace(/\r\n/g, '\n'), 'utf8');
    const hdr = Buffer.alloc(512, 0);
    hdr.write(name, 0, Math.min(100, Buffer.byteLength(name)), 'utf8');
    hdr.write('0000644\0', 100, 8, 'ascii');
    hdr.write('0000000\0', 108, 8, 'ascii');
    hdr.write('0000000\0', 116, 8, 'ascii');
    hdr.write(data.length.toString(8).padStart(11, '0') + '\0', 124, 12, 'ascii');
    hdr.write('00000000000\0', 136, 12, 'ascii'); // deterministic mtime=0
    hdr.fill(0x20, 148, 156); // checksum placeholder
    hdr.write('0', 156, 1, 'ascii'); // regular file
    hdr.write('ustar\0', 257, 6, 'ascii');
    hdr.write('00', 263, 2, 'ascii');
    let sum = 0;
    for (let i = 0; i < 512; i++) sum += hdr[i];
    hdr.write(sum.toString(8).padStart(6, '0') + '\0 ', 148, 8, 'ascii');
    chunks.push(hdr, data);
    const pad = (512 - (data.length % 512)) % 512;
    if (pad) chunks.push(Buffer.alloc(pad, 0));
  }
  chunks.push(Buffer.alloc(1024, 0)); // EOF markers
  return zlib.gzipSync(Buffer.concat(chunks), {level: 9});
}

// 打包 runtime/ 平台脚本、三端原生桌面程序源码与官方 Claude Agent SDK 到 assets/galiais/runtime。
const [runtime, claudeSdk, destination] = process.argv.slice(2);
if (!runtime || !claudeSdk || !destination) throw new Error('Usage: runtime-assets.cjs <runtime-dir> <claude-sdk.tgz> <output-dir>');
const output = path.resolve(destination);
if (path.basename(output) !== 'runtimeAssets') throw new Error('Refusing to clean an unexpected asset output directory: ' + output);
const manifestFile = path.join(runtime, 'components.json');
const manifest = readManifest(manifestFile);
const names = manifest.runtimeFiles;
for (const name of names) if (!fs.statSync(path.join(runtime, name)).isFile()) throw new Error('Missing runtime file: ' + name);
const nativeSrcDir = path.resolve(runtime, '..', 'pc-agent-native');
if (!fs.existsSync(path.join(nativeSrcDir, 'go.mod'))) throw new Error('Missing pc-agent-native source directory: ' + nativeSrcDir);
const sdk = fs.readFileSync(claudeSdk);
const sdkEntries = sdkFiles(sdk, manifest.sdk, manifest.engines.claude);
const plan = fs.readFileSync(path.join(path.dirname(claudeSdk), manifest.plan.archive));
const planEntries = planFiles(plan, manifest.plan);
const terminal = manifest.terminal.flatMap(spec => terminalFiles(fs.readFileSync(path.join(path.dirname(claudeSdk), spec.archive)), spec));
fs.rmSync(output, {recursive: true, force: true});
const target = path.join(output, 'galiais', 'runtime');
fs.mkdirSync(target, {recursive: true});
for (const name of names) {
  const src = path.join(runtime, name);
  const dst = path.join(target, name);
  if (name.endsWith('.sh')) {
    fs.writeFileSync(dst, fs.readFileSync(src, 'utf8').replace(/\r\n/g, '\n'), 'utf8');
  } else {
    fs.copyFileSync(src, dst);
  }
}
fs.writeFileSync(path.join(target, 'pc-agent-native.tgz'), buildDeterministicTarGz(nativeSrcDir));
for (const entry of terminal) fs.writeFileSync(path.join(target, entry.name), entry.bytes);
fs.copyFileSync(manifestFile, path.join(target, 'components.json'));
for (const [abi, native] of Object.entries(manifest.native)) {
  const tools = native.find(entry => entry.name === 'libpayload_tools.so');
  if (!tools) throw new Error(`Missing installer tools component: ${abi}`);
  fs.writeFileSync(path.join(target, `installer-tools-${abi}.sha256`), `${tools.sha256}  ${tools.name}\n`);
}
fs.writeFileSync(path.join(target, manifest.sdk.archive), sdk);
fs.writeFileSync(path.join(target, manifest.plan.archive), plan);
fs.writeFileSync(path.join(target, 'official-plan-files.sha256'), [...planEntries].map(([name, entry]) => `${entry.sha256}  ${name}\n`).join(''));
fs.writeFileSync(path.join(target, 'claude-sdk-files.sha256'), [...sdkEntries].map(([name, entry]) => `${entry.sha256}  ${name}\n`).join(''));
fs.writeFileSync(path.join(target, 'engine-versions.sh'), `CLAUDE_VERSION='${manifest.engines.claude}'\nCLAUDE_SDK_VERSION='${manifest.sdk.version}'\nCLAUDE_SDK_ARCHIVE='${manifest.sdk.archive}'\nPLAN_VERSION='${manifest.plan.version}'\nPLAN_ARCHIVE='${manifest.plan.archive}'\n`);
const files = fs.readdirSync(target).sort().map(name => ({name, size:fs.statSync(path.join(target, name)).size, sha256:digestFile(path.join(target, name))}));
fs.writeFileSync(path.join(target, 'package-manifest.json'), JSON.stringify({formatVersion:1, engines:manifest.engines, sdkFiles:sdkEntries.size, planFiles:planEntries.size, native:manifest.native, files}, null, 2) + '\n');
console.log(JSON.stringify({runtimeFiles:files.length + 1, sdkFiles:sdkEntries.size, output}));
