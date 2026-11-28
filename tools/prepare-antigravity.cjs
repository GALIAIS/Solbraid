const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');

const root = path.resolve(__dirname, '..');
const manifest = require('./component-manifest.cjs').readManifest(path.join(root, 'runtime/components.json'));
for (const [abi, platform, machine] of [['arm64-v8a', 'arm64', 183], ['x86_64', 'x64', 62]]) {
  const spec = require('./component-manifest.cjs').resolveNativeSource(manifest, abi, 'libbin_antigravity.so');
  const archive = fs.readFileSync(path.join(root, `downloads/runtime/antigravity-${manifest.engines.antigravity}-linux-${platform}.tar.gz`));
  if (crypto.createHash(spec.sha512hex ? 'sha512' : 'sha256').update(archive).digest('hex') !== (spec.sha512hex || spec.sha256))
    throw new Error('Antigravity archive checksum mismatch: ' + abi);
  const tar = zlib.gunzipSync(archive, {maxOutputLength: 512 * 1024 * 1024});
  let binary;
  for (let offset = 0; offset + 512 <= tar.length;) {
    const header = tar.subarray(offset, offset + 512);
    if (header.every(value => value === 0)) break;
    const name = header.subarray(0, 100).toString('utf8').split('\0')[0];
    const length = Number.parseInt(header.subarray(124, 136).toString('ascii').replace(/\0/g, '').trim(), 8);
    const end = offset + 512 + length;
    if (!Number.isSafeInteger(length) || length < 0 || end > tar.length || ![0, 48].includes(header[156])) throw new Error('Invalid Antigravity archive');
    if (name !== spec.member || binary) throw new Error('Unexpected Antigravity archive entry: ' + name);
    binary = tar.subarray(offset + 512, end);
    offset += 512 + Math.ceil(length / 512) * 512;
  }
  if (!binary || binary.readUInt32BE(0) !== 0x7f454c46 || binary[4] !== 2 || binary[5] !== 1 || binary.readUInt16LE(18) !== machine) throw new Error('Antigravity ELF architecture mismatch');
  const destination = path.join(root, 'downloads/offline', abi, 'libbin_antigravity.so');
  const expected = manifest.native[abi].find(entry => entry.name === 'libbin_antigravity.so');
  if (!expected || expected.size !== binary.length || expected.sha256 !== crypto.createHash('sha256').update(binary).digest('hex'))
    throw new Error('Antigravity archive does not match the release manifest: ' + abi);
  if (!fs.existsSync(destination) || fs.statSync(destination).size !== expected.size ||
      require('./component-manifest.cjs').digestFile(destination) !== expected.sha256) {
    const tmp = destination + '.' + crypto.randomUUID() + '.part';
    try { fs.writeFileSync(tmp, binary, {flag:'wx'}); fs.renameSync(tmp, destination); }
    finally { fs.rmSync(tmp, {force:true}); }
  }
  console.log(JSON.stringify({abi, name: 'libbin_antigravity.so', size: binary.length, sha256: crypto.createHash('sha256').update(binary).digest('hex'), source: 'offline'}));
}
