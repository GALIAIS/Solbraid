const fs = require('node:fs');
const path = require('node:path');
const {readManifest, digestFile, sdkFiles, planFiles, terminalFiles} = require('./component-manifest.cjs');

// 打包 runtime/ 平台脚本与官方 Claude Agent SDK 到 assets/galiais/runtime。
const [runtime, claudeSdk, destination] = process.argv.slice(2);
if (!runtime || !claudeSdk || !destination) throw new Error('Usage: runtime-assets.cjs <runtime-dir> <claude-sdk.tgz> <output-dir>');
const output = path.resolve(destination);
if (path.basename(output) !== 'runtimeAssets') throw new Error('Refusing to clean an unexpected asset output directory: ' + output);
const manifestFile = path.join(runtime, 'components.json');
const manifest = readManifest(manifestFile);
const names = manifest.runtimeFiles;
for (const name of names) if (!fs.statSync(path.join(runtime, name)).isFile()) throw new Error('Missing runtime file: ' + name);
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
