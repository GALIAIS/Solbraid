const path = require('node:path');
const fs = require('node:fs/promises');
const {execFileSync} = require('node:child_process');
const {readManifest} = require('../../tools/component-manifest.cjs');

async function main() {
  const source = process.env.COMPONENTS_URL;
  if (!source) throw new Error('请在仓库 Actions 变量中设置 SOLBRAID_COMPONENTS_URL。');
  const base = new URL(source);
  if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash) {
    throw new Error('SOLBRAID_COMPONENTS_URL 必须是无凭据的 HTTPS 目录地址。');
  }
  if (!base.pathname.endsWith('/')) base.pathname += '/';
  const manifest = readManifest('runtime/components.json');
  const inputs = [
    ...manifest.native['arm64-v8a'].filter(entry => entry.source === 'offline')
      .map(entry => ['offline/arm64-v8a', entry.name]),
    ...[manifest.sdk, manifest.plan, ...manifest.terminal].map(entry => ['runtime', entry.archive]),
  ];
  for (const [dir, name] of inputs) {
    const target = path.join('downloads', dir, name);
    const tmp = target + '.part';
    await fs.mkdir(path.dirname(target), {recursive: true});
    try {
      const url = new URL(dir + '/' + encodeURIComponent(name), base);
      execFileSync('curl', ['--fail', '--silent', '--show-error', '--location', '--proto', '=https',
        '--proto-redir', '=https', '--retry', '3', '--connect-timeout', '15', '--max-time', '360',
        '--output', tmp, url.href], {stdio: 'inherit'});
      await fs.rename(tmp, target);
    } finally {
      await fs.rm(tmp, {force: true});
    }
  }
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
