const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs');
const {createHash} = require('node:crypto');
const {execFileSync} = require('node:child_process');
const {readManifest, digestFile} = require('../../tools/component-manifest.cjs');

function download(url, target) {
  execFileSync('curl', ['--fail', '--silent', '--show-error', '--location', '--proto', '=https',
    '--proto-redir', '=https', '--retry', '3', '--connect-timeout', '15', '--max-time', '900',
    '--output', target, url], {stdio: 'inherit'});
}

function checkFile(file, entry) {
  const digest = entry.sha512
    ? digestFile(file, 'sha512', 'base64')
    : digestFile(file);
  if (digest !== (entry.sha512 || entry.sha256)) throw new Error('组件校验失败：' + entry.file);
}

function main() {
  const [, , command, manifestFile = 'runtime/components.json', outputDir = '.'] = process.argv;
  if (command && command !== '--pack') throw new Error('未知参数：' + command);
  const manifest = readManifest(manifestFile);
  const inputs = [
    ...manifest.native['arm64-v8a'].filter(entry => entry.source === 'offline')
      .map(entry => ({file: 'offline/arm64-v8a/' + entry.name, sha256: entry.sha256})),
    ...[manifest.sdk, manifest.plan, ...manifest.terminal].map(entry =>
      ({file: 'runtime/' + entry.archive, sha256: entry.sha256, sha512: entry.sha512})),
  ].sort((a, b) => a.file < b.file ? -1 : a.file > b.file ? 1 : 0);
  const fingerprint = createHash('sha256').update(JSON.stringify(inputs)).digest('hex');
  const archiveName = 'solbraid-components-arm64-' + fingerprint + '.zip';
  if (command === '--pack') {
    for (const entry of inputs) checkFile(path.join('downloads', entry.file), entry);
    const dest = path.resolve(outputDir, archiveName);
    const staging = fs.mkdtempSync(path.join(path.dirname(dest), '.pack-'));
    try {
      const archive = path.join(staging, archiveName);
      const files = inputs.map(entry => entry.file);
      if (process.platform === 'win32') execFileSync('tar.exe', ['-a', '-cf', archive, ...files], {cwd: 'downloads'});
      else execFileSync('zip', ['-q', archive, ...files], {cwd: 'downloads'});
      fs.renameSync(archive, dest);
    } finally { fs.rmSync(staging, {recursive: true, force: true}); }
    console.log(dest);
    return;
  }

  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'solbraid-components-'));
  try {
    let base, archive;
    if (process.env.COMPONENTS_URL) {
      base = new URL(process.env.COMPONENTS_URL);
      if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash) {
        throw new Error('SOLBRAID_COMPONENTS_URL 必须是无凭据的 HTTPS 目录地址。');
      }
      if (!base.pathname.endsWith('/')) base.pathname += '/';
    } else {
      const draftId = execFileSync('gh', ['api', '--paginate', 'repos/{owner}/{repo}/releases?per_page=100',
        '--jq', '.[] | select(.draft and .tag_name == "ci-components") | .id'], {encoding: 'utf8'}).trim();
      if (!/^\d+$/.test(draftId)) throw new Error('无法定位 ci-components 构建组件草稿。');
      const assetId = execFileSync('gh', ['api', '--paginate', `repos/{owner}/{repo}/releases/${draftId}/assets?per_page=100`,
        '--jq', `.[] | select(.name == "${archiveName}" and .state == "uploaded") | .id`], {encoding: 'utf8'}).trim();
      if (!/^\d+$/.test(assetId)) throw new Error('构建组件草稿中缺少与清单匹配的文件：' + archiveName);
      archive = path.join(temp, archiveName);
      const fd = fs.openSync(archive, 'wx');
      try {
        execFileSync('gh', ['api', '-H', 'Accept: application/octet-stream', `repos/{owner}/{repo}/releases/assets/${assetId}`],
          {stdio: ['ignore', fd, 'inherit']});
      } finally { fs.closeSync(fd); }
    }
    for (const entry of inputs) {
      const target = path.join('downloads', entry.file);
      fs.mkdirSync(path.dirname(target), {recursive: true});
      const tmp = target + '.part';
      try {
        if (base) download(new URL(entry.file, base).href, tmp);
        else {
          const fd = fs.openSync(tmp, 'w');
          try { execFileSync('unzip', ['-p', archive, entry.file], {stdio: ['ignore', fd, 'inherit']}); }
          finally { fs.closeSync(fd); }
        }
        checkFile(tmp, entry);
        fs.renameSync(tmp, target);
      } finally { fs.rmSync(tmp, {force: true}); }
    }
  } finally { fs.rmSync(temp, {recursive: true, force: true}); }
}

try { main(); }
catch (error) { console.error(error.message); process.exitCode = 1; }
