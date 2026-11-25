const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');

const root = path.resolve(__dirname, '..');
const inputs = [
  ['arm64-v8a', 'arm64', 183, 'd64ad759781b50dfacfa7d9826c0ad469959d01bdf5a170acd4c83472eba31c677baf0052ac8ab48eef40f0d3f0e26057e752aabd24331c79f4c881d4b872125'],
  ['x86_64', 'x64', 62, 'af3f0be58b8475940d79f9abea19e762ecc3d265ea9f56cff8dd805e27ba299b44a305a79504a7ffe6ebdfa29a294c56edc4caed1875990bfaf2fef1508ec8b0'],
];
for (const [abi, platform, machine, checksum] of inputs) {
  const archive = fs.readFileSync(path.join(root, 'downloads/runtime/antigravity-1.5.15-linux-' + platform + '.tar.gz'));
  if (crypto.createHash('sha512').update(archive).digest('hex') !== checksum) throw new Error('Antigravity archive checksum mismatch: ' + abi);
  const tar = zlib.gunzipSync(archive, {maxOutputLength: 512 * 1024 * 1024});
  let binary;
  for (let offset = 0; offset + 512 <= tar.length;) {
    const header = tar.subarray(offset, offset + 512);
    if (header.every(value => value === 0)) break;
    const name = header.subarray(0, 100).toString('utf8').split('\0')[0];
    const length = Number.parseInt(header.subarray(124, 136).toString('ascii').replace(/\0/g, '').trim(), 8);
    const end = offset + 512 + length;
    if (!Number.isSafeInteger(length) || length < 0 || end > tar.length || ![0, 48].includes(header[156])) throw new Error('Invalid Antigravity archive');
    if (name !== 'antigravity' || binary) throw new Error('Unexpected Antigravity archive entry: ' + name);
    binary = tar.subarray(offset + 512, end);
    offset += 512 + Math.ceil(length / 512) * 512;
  }
  if (!binary || binary.readUInt32BE(0) !== 0x7f454c46 || binary[4] !== 2 || binary[5] !== 1 || binary.readUInt16LE(18) !== machine) throw new Error('Antigravity ELF architecture mismatch');
  const destination = path.join(root, 'downloads/offline', abi, 'libbin_antigravity.so');
  fs.writeFileSync(destination, binary);
  console.log(JSON.stringify({abi, name: 'libbin_antigravity.so', size: binary.length, sha256: crypto.createHash('sha256').update(binary).digest('hex'), source: 'offline'}));
}
