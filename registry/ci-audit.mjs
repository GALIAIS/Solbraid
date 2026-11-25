#!/usr/bin/env node
// GitHub Actions 社区资源仓库 PR 强制 AI 安全审查脚本
// 用法：node registry/ci-audit.mjs [--require-llm] [file1.json file2.json ...]
import fs from "node:fs/promises";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {computeBundleSha256, reviewResourceBundle} from "../runtime/ai-reviewer.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

async function collectJsonFiles(dir) {
  const out = [];
  let entries;
  try { entries = await fs.readdir(dir, {withFileTypes: true}); } catch { return out; }
  for (const e of entries) {
    const abs = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...await collectJsonFiles(abs));
    else if (e.isFile() && e.name.endsWith(".json")) out.push(abs);
  }
  return out;
}

const args = process.argv.slice(2);
const requireLlm = args.includes("--require-llm") || process.env.SOLBRAID_REQUIRE_LLM === "1";
const targets = args.filter(a => !a.startsWith("--"));
const files = targets.length
  ? targets.map(f => path.resolve(f))
  : [
    ...await collectJsonFiles(path.join(__dirname, "submissions")),
    ...await collectJsonFiles(path.join(__dirname, "items")),
  ];

if (!files.length) {
  console.log("No community resource submissions to audit.");
  process.exit(0);
}

let failed = 0;
const reportLines = ["# Solbraid 社区资源仓库 AI 安全审查报告\n"];

for (const file of files) {
  const rel = path.relative(process.cwd(), file);
  let bundle;
  try {
    bundle = JSON.parse(await fs.readFile(file, "utf8"));
  } catch (err) {
    failed += 1;
    reportLines.push(`- ❌ **${rel}**：JSON 解析失败（${err.message}）`);
    continue;
  }
  const expectedSha = bundle.sha256 ? String(bundle.sha256) : "";
  const actualSha = computeBundleSha256(bundle);
  if (expectedSha && expectedSha !== actualSha) {
    failed += 1;
    reportLines.push(`- ❌ **${rel}**：SHA-256 指纹不一致（声明 \`${expectedSha.slice(0, 12)}\`，实测 \`${actualSha.slice(0, 12)}\`）`);
    continue;
  }
  const audit = await reviewResourceBundle(bundle, {requireLlm});
  const icon = audit.verdict === "approved" ? "✅" : "❌";
  reportLines.push(`- ${icon} **${bundle.name || rel}** (\`${bundle.kind}/${bundle.id}\`) · 结论：\`${audit.verdict}\` · 风险：\`${audit.riskLevel}\` · 评分：**${audit.score}** · 模式：\`${audit.reviewerMode}\``);
  reportLines.push(`  - 摘要：${audit.summary}`);
  if (audit.findings.length) {
    for (const f of audit.findings) {
      reportLines.push(`  - [${f.severity.toUpperCase()}] ${f.title}（${f.location}）：${f.detail}`);
    }
  }
  if (audit.verdict !== "approved") failed += 1;
}

const markdown = reportLines.join("\n") + "\n";
console.log(markdown);
if (process.env.GITHUB_STEP_SUMMARY) {
  await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, markdown, "utf8");
}
if (failed > 0) {
  console.error(`AI Security Audit BLOCKED ${failed} resource(s).`);
  process.exit(1);
}
