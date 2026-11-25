// Solbraid Mobile Super-Agent 工单隔离与 Git Worktree 管理器（零第三方依赖）。
// 为并行工单提供独立 git worktree 隔离、增量 Diff 统计、一键合并或安全丢弃，以及工单状态持久化。
import fs from "node:fs/promises";
import path from "node:path";
import {execFile} from "node:child_process";
import {promisify} from "node:util";
import {createHash} from "node:crypto";

const execFileAsync = promisify(execFile);
const HOME = process.env.HOME || "/home/agent";
const WORKTREES_ROOT = path.join(HOME, ".galiais", "worktrees");
const TICKETS_ROOT = path.join(HOME, ".galiais", "tickets");
const SAFE_TICKET = /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}$/;
const busyRepositories = new Set();

function cleanTicketId(raw) {
  const id = String(raw || "").trim().replace(/[^a-zA-Z0-9_-]+/g, "-").slice(0, 44);
  if (!id || !SAFE_TICKET.test(id)) throw new Error(`无效的工单标识：${raw}`);
  return id;
}

async function git(args, cwd, timeout = 30_000) {
  try {
    const res = await execFileAsync("git", args, {
      cwd,
      timeout,
      maxBuffer: 8 * 1024 * 1024,
      env: {...process.env, GIT_TERMINAL_PROMPT: "0"},
    });
    return {ok: true, stdout: res.stdout || "", stderr: res.stderr || ""};
  } catch (err) {
    return {
      ok: false,
      stdout: err.stdout || "",
      stderr: err.stderr || err.message || String(err),
      code: err.code ?? 1,
    };
  }
}

async function ensureGitIdentity(cwd) {
  const check = await git(["config", "user.email"], cwd, 8_000);
  if (!check.ok || !check.stdout.trim()) {
    await checkedGit(["config", "user.name", "Solbraid Agent"], cwd, 8_000);
    await checkedGit(["config", "user.email", "agent@solbraid.local"], cwd, 8_000);
  }
}

async function checkedGit(args, cwd, timeout = 8_000) {
  const result = await git(args, cwd, timeout);
  if (!result.ok) throw new Error(result.stderr.trim() || `Git ${args[0]} 执行失败`);
  return result.stdout;
}

async function exists(file) {
  try {
    await fs.lstat(file);
    return true;
  } catch (err) {
    if (err.code === "ENOENT") return false;
    throw err;
  }
}

async function commonGitDir(cwd) {
  const dir = (await checkedGit(["rev-parse", "--git-common-dir"], cwd)).trim();
  return fs.realpath(path.resolve(cwd, dir));
}

async function withRepositoryLock(repoRoot, action) {
  const key = await commonGitDir(repoRoot);
  // 忙时拒绝，避免请求已超时后排队任务仍修改仓库。
  if (busyRepositories.has(key)) throw new Error("该仓库正在处理其他工单，请稍后重试");
  busyRepositories.add(key);
  try {
    return await action();
  } finally {
    busyRepositories.delete(key);
  }
}

function managedPath(repoRoot, id) {
  const hash = createHash("sha1").update(repoRoot).digest("hex").slice(0, 10);
  return path.join(WORKTREES_ROOT, hash, id);
}

async function verifyManagedWorktree({repoRoot, worktreePath, branch}, allowMissing = false) {
  if (!repoRoot || !worktreePath || !branch) throw new Error("缺少工单隔离目录信息");
  const root = await fs.realpath(repoRoot);
  const top = await fs.realpath((await checkedGit(["rev-parse", "--show-toplevel"], root)).trim());
  const id = path.basename(worktreePath);
  if (root !== top || !SAFE_TICKET.test(id) || branch !== `solbraid/ticket-${id}` || path.resolve(worktreePath) !== managedPath(root, id)) {
    throw new Error("工单目录或分支不属于该仓库，已停止操作");
  }
  if (!(await exists(worktreePath))) {
    if (allowMissing) return;
    throw new Error("工单隔离目录不存在，请检查后重试");
  }
  const managedRoot = await fs.realpath(WORKTREES_ROOT);
  const actual = await fs.realpath(worktreePath);
  if (actual !== path.join(managedRoot, path.basename(path.dirname(worktreePath)), id)) {
    throw new Error("工单目录指向托管范围外，已停止操作");
  }
  const worktreeTop = await fs.realpath((await checkedGit(["rev-parse", "--show-toplevel"], actual)).trim());
  const actualBranch = (await checkedGit(["symbolic-ref", "--short", "HEAD"], actual)).trim();
  if (worktreeTop !== actual || actualBranch !== branch || await commonGitDir(actual) !== await commonGitDir(root)) {
    throw new Error("工单目录的仓库或分支已改变，已停止操作");
  }
}

async function ensureNoGitOperation(cwd) {
  const gitDir = (await checkedGit(["rev-parse", "--absolute-git-dir"], cwd)).trim();
  for (const name of ["MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply", "sequencer", "BISECT_LOG"]) {
    if (await exists(path.join(gitDir, name))) throw new Error("仓库有未完成的 Git 操作，请先处理后重试");
  }
}

async function ensureMergeTarget(repoRoot, baseBranch, baseCommit) {
  await ensureNoGitOperation(repoRoot);
  const currentBranch = (await checkedGit(["rev-parse", "--abbrev-ref", "HEAD"], repoRoot)).trim();
  if (!baseBranch || currentBranch !== baseBranch) throw new Error("目标分支与派发时不一致，请切回原分支后合并");
  if (baseBranch === "HEAD" && (await checkedGit(["rev-parse", "HEAD"], repoRoot)).trim() !== baseCommit) {
    throw new Error("目标提交与派发时不一致，请恢复原提交后合并");
  }
  const status = await checkedGit(["status", "--porcelain", "--untracked-files=all"], repoRoot, 10_000);
  if (status.trim()) throw new Error("主工作区有未提交的改动，请先保存或处理后合并");
}

/** 隔离创建失败时停止工单，保留已有目录与分支。 */
export async function prepareWorktree({repoCwd, ticketId}) {
  const id = cleanTicketId(ticketId);
  const absCwd = await fs.realpath(repoCwd || path.join(HOME, "workspace"));
  const top = await git(["rev-parse", "--show-toplevel"], absCwd, 8_000);
  if (!top.ok || !top.stdout.trim()) {
    throw new Error("该目录不是 Git 仓库，无法创建隔离工单");
  }
  const repoRoot = await fs.realpath(top.stdout.trim());
  const head = await git(["rev-parse", "HEAD"], repoRoot, 8_000);
  if (!head.ok || !head.stdout.trim()) {
    throw new Error("仓库尚无初始提交，无法创建隔离工单");
  }
  const baseCommit = head.stdout.trim();
  const curBranch = (await checkedGit(["rev-parse", "--abbrev-ref", "HEAD"], repoRoot)).trim();
  const worktreePath = managedPath(repoRoot, id);
  const branch = `solbraid/ticket-${id}`;
  await fs.mkdir(path.dirname(worktreePath), {recursive: true});
  const managedRoot = await fs.realpath(WORKTREES_ROOT);
  if (await fs.realpath(path.dirname(worktreePath)) !== path.join(managedRoot, path.basename(path.dirname(worktreePath)))) {
    throw new Error("隔离目录指向托管范围外，已停止创建");
  }

  if (await exists(worktreePath)) throw new Error("该工单已有隔离目录，请检查后重新派发");
  const branchExists = await git(["show-ref", "--verify", "--quiet", `refs/heads/${branch}`], repoRoot, 8_000);
  if (branchExists.ok) throw new Error("该工单已有分支，请检查后重新派发");
  if (branchExists.code !== 1) throw new Error(branchExists.stderr.trim() || "无法检查工单分支");

  const added = await git(["worktree", "add", "-b", branch, worktreePath, baseCommit], repoRoot, 45_000);
  if (!added.ok) {
    throw new Error(`创建隔离目录失败：${added.stderr.trim()}`);
  }
  const rollbackWorktree = async () => {
    await git(["worktree", "remove", "--force", worktreePath], repoRoot, 15_000).catch(() => {});
    await git(["branch", "-D", branch], repoRoot, 8_000).catch(() => {});
  };
  try {
    const relSub = path.relative(repoRoot, absCwd);
    const targetCwd = relSub ? path.join(worktreePath, relSub) : worktreePath;
    if (!(await fs.stat(targetCwd).catch(() => null))?.isDirectory()) {
      throw new Error("项目子目录不在基线提交中，请先提交该目录后再派发隔离工单");
    }
    const targetRelative = path.relative(await fs.realpath(worktreePath), await fs.realpath(targetCwd));
    if (targetRelative === ".." || targetRelative.startsWith(`..${path.sep}`) || path.isAbsolute(targetRelative)) {
      throw new Error("项目子目录指向隔离范围外，工单已停止");
    }
    return {isolated: true, repoRoot, worktreePath, targetCwd, branch, baseBranch: curBranch, baseCommit};
  } catch (err) {
    await rollbackWorktree();
    throw err;
  }
}

/** 返回完整文件清单；超出预览上限或无法读取的内容必须明确报告。 */
export async function diffWorktree({worktreePath, baseCommit}) {
  if (!worktreePath) throw new Error("缺少工单工作目录");
  const ref = baseCommit || "HEAD";
  const [tracked, trackedNames, untrackedNames] = await Promise.all([
    checkedGit(["diff", "--no-color", "--no-ext-diff", ref, "--"], worktreePath, 20_000),
    checkedGit(["diff", "--name-only", "-z", ref, "--"], worktreePath, 10_000),
    checkedGit(["ls-files", "--others", "--exclude-standard", "-z"], worktreePath, 10_000),
  ]);
  let diffText = tracked;
  let incomplete = false;
  const files = new Set(trackedNames.split("\0").filter(Boolean));
  if (untrackedNames) {
    for (const rel of untrackedNames.split("\0").filter(Boolean)) {
      files.add(rel);
      if (diffText.length >= 400_000) { incomplete = true; continue; }
      const full = path.join(worktreePath, rel);
      try {
        const st = await fs.lstat(full);
        if (!st.isFile() || st.size > 256 * 1024) { incomplete = true; continue; }
        const buf = await fs.readFile(full);
        if (buf.includes(0)) { incomplete = true; continue; }
        const lines = buf.toString("utf8").split(/\r?\n/);
        if (lines.at(-1) === "") lines.pop();
        diffText += `diff --git a/${rel} b/${rel}\nnew file mode 100644\n--- /dev/null\n+++ b/${rel}\n@@ -0,0 +1,${lines.length} @@\n`;
        diffText += lines.map(l => `+${l}`).join("\n") + "\n";
      } catch (error) { throw new Error(`无法读取工单文件：${rel}`, {cause: error}); }
    }
  }
  let added = 0;
  let removed = 0;
  let inHunk = false;
  for (const line of diffText.split(/\r?\n/)) {
    if (line.startsWith("diff --git ")) inHunk = false;
    else if (line.startsWith("@@")) inHunk = true;
    else if (inHunk && line.startsWith("+")) added++;
    else if (inHunk && line.startsWith("-")) removed++;
  }
  return {diff: diffText.slice(0, 400_000), files: [...files], added, removed,
    warning: incomplete || diffText.length > 400_000 ? "文件清单完整；部分内容无法预览，Diff 与行数统计可能不完整。请在工作区核对。" : null};
}

/** 合并前保护主工作区；失败保留现场，成功后清理托管目录。 */
export async function mergeWorktree({repoRoot, worktreePath, branch, baseBranch, baseCommit, commitMessage, squash = true}) {
  if (!repoRoot || !worktreePath || !branch) throw new Error("该工单未使用独立 Git Worktree，无需合并");
  return withRepositoryLock(repoRoot, async () => {
    await verifyManagedWorktree({repoRoot, worktreePath, branch});
    await ensureMergeTarget(repoRoot, baseBranch, baseCommit);
    await ensureNoGitOperation(worktreePath);
    await ensureGitIdentity(worktreePath);
    await checkedGit(["add", "-A"], worktreePath, 20_000);
    const status = await checkedGit(["status", "--porcelain"], worktreePath, 10_000);
    if (status.trim()) {
      const msg = String(commitMessage || `Solbraid ticket: ${branch}`).slice(0, 120);
      const committed = await git(["commit", "-m", msg], worktreePath, 25_000);
      if (!committed.ok) throw new Error(`工单分支暂存提交失败：${committed.stderr.trim()}`);
    }

    const sourceHead = (await checkedGit(["rev-parse", "HEAD"], worktreePath)).trim();
    await ensureMergeTarget(repoRoot, baseBranch, baseCommit);
    await ensureGitIdentity(repoRoot);
    const mergeArgs = squash ? ["merge", "--squash", branch] : ["merge", "--no-ff", branch, "-m", commitMessage || `Merge ${branch}`];
    const merged = await git(mergeArgs, repoRoot, 30_000);
    if (!merged.ok) {
      const conflicts = (await git(["diff", "--name-only", "--diff-filter=U"], repoRoot, 8_000)).stdout
        .trim().split(/\r?\n/).filter(Boolean);
      // 自动回滚冲突暂存状态，避免主仓库残留脏索引/MERGE_HEAD 阻塞后续所有工单
      if (squash) {
        await git(["reset", "--merge"], repoRoot, 15_000);
      } else {
        await git(["merge", "--abort"], repoRoot, 15_000);
      }
      return {ok: false, conflicts, message: `合并存在冲突，已自动还原主工作区并保留工单分支（${branch}），请解决冲突后重试。${merged.stderr.trim()}`};
    }
    if (squash) {
      const msg = String(commitMessage || `Solbraid: ${branch}`).slice(0, 120);
      const hasStaged = (await checkedGit(["diff", "--cached", "--name-only"], repoRoot)).trim();
      if (hasStaged) {
        const c = await git(["commit", "-m", msg], repoRoot, 20_000);
        if (!c.ok) return {ok: false, conflicts: [], message: `合并改动尚未提交，已保留暂存区与工单分支：${c.stderr.trim()}`};
      }
    }
    try {
      const remaining = await checkedGit(["status", "--porcelain", "--untracked-files=all"], worktreePath, 10_000);
      const currentHead = (await checkedGit(["rev-parse", "HEAD"], worktreePath)).trim();
      if (remaining.trim() || currentHead !== sourceHead) throw new Error("工单目录在合并期间发生变化，已保留目录和分支");
      await discardManagedWorktree({repoRoot, worktreePath, branch});
    } catch (err) {
      return {ok: true, cleanupPending: true, conflicts: [], message: `已合并，但隔离目录或分支清理失败：${err.message}`};
    }
    return {ok: true, cleanupPending: false, conflicts: [], message: "已合并到当前分支并清理隔离工作区"};
  });
}

/** 丢弃工单 worktree 并删除临时分支。 */
export async function discardWorktree({repoRoot, worktreePath, branch}) {
  if (!repoRoot) throw new Error("缺少工单仓库信息");
  return withRepositoryLock(repoRoot, () => discardManagedWorktree({repoRoot, worktreePath, branch}));
}

async function discardManagedWorktree({repoRoot, worktreePath, branch}) {
  await verifyManagedWorktree({repoRoot, worktreePath, branch}, true);
  if (await exists(worktreePath)) {
    await checkedGit(["worktree", "remove", "--force", worktreePath], repoRoot, 20_000);
  }
  const found = await git(["show-ref", "--verify", "--quiet", `refs/heads/${branch}`], repoRoot, 8_000);
  if (found.ok) {
    await checkedGit(["branch", "-D", branch], repoRoot, 10_000);
  } else if (found.code !== 1) {
    throw new Error(found.stderr.trim() || "无法检查工单分支");
  }
  return {ok: true};
}

export async function saveTicketRecord(ticket) {
  const id = cleanTicketId(ticket?.id);
  await fs.mkdir(TICKETS_ROOT, {recursive: true});
  const file = path.join(TICKETS_ROOT, `${id}.json`);
  const tmp = `${file}.${Date.now()}.tmp`;
  await fs.writeFile(tmp, JSON.stringify(ticket, null, 2) + "\n", {mode: 0o600});
  await fs.rename(tmp, file);
  return {ok: true};
}

export async function listTicketRecords() {
  try {
    const entries = await fs.readdir(TICKETS_ROOT);
    const out = [];
    for (const name of entries) {
      if (!name.endsWith(".json")) continue;
      try {
        out.push(JSON.parse(await fs.readFile(path.join(TICKETS_ROOT, name), "utf8")));
      } catch {}
    }
    return out.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  } catch {
    return [];
  }
}

export async function deleteTicketRecord(ticketId) {
  const id = cleanTicketId(ticketId);
  await fs.rm(path.join(TICKETS_ROOT, `${id}.json`), {force: true}).catch(() => {});
  return {ok: true};
}
