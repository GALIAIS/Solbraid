// Solbraid 插件自进化运维 MCP 服务器（stdio，零第三方依赖）。
// 向 Codex / Claude Code / Antigravity 暴露 10 个标准化工具，通过本地受保护 Unix Socket (~/.galiais/run/plugin-ctl.sock)
// 驱动 PluginKernel 完成：查询、读取、脚手架创建、语法与安全预检、自动快照写入、单插件增量热载、烟测与源码回滚。
import fs from "node:fs";
import net from "node:net";
import path from "node:path";

const HOME = process.env.HOME || "/home/agent";
const DEFAULT_SOCK = path.join(HOME, ".galiais", "run", "plugin-ctl.sock");
const DEFAULT_TOKEN_FILE = path.join(HOME, ".galiais", "run", "plugin-ctl.token");

function resolveBridgeConfig() {
  const socketPath = process.env.SOLBRAID_PLUGIN_CTL_SOCKET || DEFAULT_SOCK;
  let token = process.env.SOLBRAID_PLUGIN_CTL_TOKEN || "";
  if (!token) {
    try {
      token = fs.readFileSync(DEFAULT_TOKEN_FILE, "utf8").trim();
    } catch {}
  }
  return {socketPath, token};
}

const TOOLS = [
  {
    name: "solbraid_plugin_list",
    description: "列出 Solbraid 当前全部 Host 插件（核心/内置/本地/社区）、运行代际、状态、能力授权、命令、面板、输入栏动作槽位、覆写标记与快照数量。",
    inputSchema: {type: "object", properties: {}, additionalProperties: false},
  },
  {
    name: "solbraid_plugin_read",
    description: "读取指定本地/社区/覆写插件的 solbraid-plugin.json 清单与全部源码文件；核心内核插件禁止覆写。",
    inputSchema: {
      type: "object",
      properties: {pluginId: {type: "string", description: "目标插件 ID，例如 local.hello.kit 或 solbraid.builtin.workspace-inspector"}},
      required: ["pluginId"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_scaffold",
    description: "一键创建一个新的 Solbraid 本地运行时插件脚手架并立即热激活。",
    inputSchema: {
      type: "object",
      properties: {
        id: {type: "string", description: "插件 ID（小写字母、数字、点、横线，如 local.git.summary）"},
        name: {type: "string", description: "插件展示名称"},
        description: {type: "string", description: "插件功能简介"},
      },
      required: ["id", "name"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_validate",
    description: "预检候选插件清单与源码（不写入磁盘）：校验非核心红线、Manifest 契约、路径防穿越、14 条静态安全审计与真实 node --check 语法校验。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID"},
        manifest: {type: "object", description: "完整 solbraid-plugin.json 对象"},
        files: {type: "object", description: "相对路径到文本源码的映射，如 {\"index.mjs\": \"export async function activate(ctx){...}\"}"},
      },
      required: ["pluginId", "manifest", "files"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_apply",
    description: "安全热升级插件（原子流水线）：先执行语法与静态安全预检 → 自动归档旧源码快照 → 原子写入 → 单插件增量热重载；若激活失败自动回滚到升级前快照并返回报错堆栈。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID（支持本地/社区插件及非 core 内置插件覆写）"},
        manifest: {type: "object", description: "完整 solbraid-plugin.json 对象"},
        files: {type: "object", description: "相对路径到文本源码的映射（至少包含 entrypoint 声明的入口文件）"},
        note: {type: "string", description: "本次修改/升级说明（写入源码快照元数据）"},
        dryRun: {type: "boolean", description: "为 true 时仅执行预检不落盘"},
      },
      required: ["pluginId", "manifest", "files"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_reload",
    description: "触发单插件增量热重载（传 pluginId）或整代重载（不传 pluginId），免重启 App 立即生效。",
    inputSchema: {
      type: "object",
      properties: {pluginId: {type: "string", description: "可选；指定时仅增量重载该插件"}},
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_test",
    description: "现场烟测已激活插件的命令（command）、交互面板渲染（panel）或自定义 RPC（rpc），返回耗时、实测输出与最近日志。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID"},
        kind: {type: "string", enum: ["command", "panel", "rpc"], description: "测试目标类型"},
        target: {type: "string", description: "命令名、面板 ID 或 RPC 方法名"},
        args: {type: "string", description: "command 模式的文本参数"},
        params: {type: "object", description: "rpc 模式的参数对象"},
        cwd: {type: "string", description: "可选工作区目录"},
      },
      required: ["pluginId", "kind"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_logs",
    description: "获取指定插件最近运行日志、当前状态、激活错误与授权能力明细。",
    inputSchema: {
      type: "object",
      properties: {pluginId: {type: "string", description: "目标插件 ID"}},
      required: ["pluginId"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_snapshots",
    description: "列出指定插件的历史源码快照（按时间倒序），可用于选择回滚目标。",
    inputSchema: {
      type: "object",
      properties: {pluginId: {type: "string", description: "目标插件 ID"}},
      required: ["pluginId"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_rollback",
    description: "将插件一键回滚至指定源码快照（或最近一次快照），或将已覆写的内置插件恢复为 APK 原版。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID"},
        snapshotId: {type: "string", description: "可选；指定历史快照 ID，留空则回滚至最新快照"},
        restoreBuiltin: {type: "boolean", description: "为 true 时移除内置插件的磁盘覆写并恢复原版"},
      },
      required: ["pluginId"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_install_deps",
    description: "为指定磁盘插件受控安装第三方 npm 依赖（强制 --ignore-scripts 防安装期恶意脚本，遵循 Solbraid 镜像与代理配置；需插件声明 deps.npm 能力）。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID"},
        packages: {type: "array", items: {type: "string"}, description: "待安装的 npm 包列表，如 [\"zod@3\", \"marked\"]"},
        dryRun: {type: "boolean", description: "为 true 时仅校验包名与权限，不执行安装"},
      },
      required: ["pluginId", "packages"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_test_sdui",
    description: "现场渲染并校验插件的声明式原生 Jetpack Compose SDUI 页面（ctx.ui.registerSurface），返回节点数、AST 校验结果与完整结构。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID"},
        surfaceId: {type: "string", description: "可选；目标 SDUI 页面 ID，留空则自动测试首个页面"},
        cwd: {type: "string", description: "可选工作区目录"},
        params: {type: "object", description: "可选页面渲染参数"},
      },
      required: ["pluginId"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_studio_override",
    description: "安全管理 Artifact Studio 离线渲染器热覆写（~/.galiais/studio-overrides/）：白名单文件列表、读取、语法校验后自动快照覆写、回滚或重置为 APK 原版（安全模式下自动禁用覆写）。",
    inputSchema: {
      type: "object",
      properties: {
        action: {type: "string", enum: ["list", "read", "apply", "rollback", "reset"], description: "操作类型"},
        fileName: {type: "string", enum: ["artifact-viewer.html", "artifact-renderer.js", "artifact-latex.js", "artifact-mermaid.js"], description: "目标渲染器文件名"},
        content: {type: "string", description: "action=apply 时的完整文件内容"},
        snapshotId: {type: "string", description: "action=rollback 时可选指定历史快照 ID"},
      },
      required: ["action"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_test_driver",
    description: "现场烟测插件注册的可执行动态 Agent 驱动（kind=driver）或热插拔 LLM 协议转换器（kind=translator），返回流式输出或翻译后的协议载荷。",
    inputSchema: {
      type: "object",
      properties: {
        kind: {type: "string", enum: ["driver", "translator"], description: "测试目标类型"},
        id: {type: "string", description: "驱动 ID 或协议转换器 ID"},
        prompt: {type: "string", description: "kind=driver 时的测试提示词"},
        request: {type: "object", description: "kind=translator 时的模拟入站请求对象"},
        response: {type: "object", description: "kind=translator 时可选的模拟上游响应对象"},
        cwd: {type: "string", description: "可选工作区目录"},
      },
      required: ["kind", "id"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_self_build",
    description: "本机纯本地 ARM64 APK 自迭代流水线（默认关闭，须用户在「设置 → 本机 APK 自迭代」启用并放行对应 Agent 权限）：检测工具链与启动心跳（action=status）、执行本地编译（action=build）、或经用户确认后触发看门狗安装（action=apply, confirmInstall=true）。",
    inputSchema: {
      type: "object",
      properties: {
        action: {type: "string", enum: ["status", "build", "apply"], description: "操作类型"},
        repoDir: {type: "string", description: "可选；覆盖配置中的 Solbraid 源码仓库目录"},
        variant: {type: "string", enum: ["debug", "release"], description: "可选；覆盖配置中的构建变体（debug 或 release）"},
        apkPath: {type: "string", description: "action=apply 时的候选 APK 路径"},
        confirmInstall: {type: "boolean", description: "action=apply 必须显式为 true（安装将重启 App，须用户明确授权）"},
      },
      required: ["action"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_file_read",
    description: "Solbraid 增强安全文件读取：自动识别 UTF-8/BOM/GB18030(GBK)/UTF-16 编码与 CRLF/LF 换行，8KB 头部拦截二进制/ELF/SQLite，流式按行分页（>20MB 零 OOM），并返回 sha256 修订锁（供 solbraid_file_edit 防并发覆盖）。优先用于非 UTF-8 编码、超大日志或需要 sha256 修订锁的场景。",
    inputSchema: {
      type: "object",
      properties: {
        path: {type: "string", description: "目标文件路径（绝对路径或相对 cwd 的路径，须在用户目录内）"},
        cwd: {type: "string", description: "可选工作目录，解析相对路径时使用"},
        startLine: {type: "integer", description: "起始行号（1-based，默认 1）"},
        endLine: {type: "integer", description: "可选结束行号（1-based，包含该行）"},
        limit: {type: "integer", description: "可选最大返回行数（默认最多 4000 行或 256 KB）"},
        encoding: {type: "string", description: "可选强制编码（utf-8 / utf-8-bom / gb18030 / utf-16le / utf-16be）"},
      },
      required: ["path"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_file_edit",
    description: "Solbraid 增强精准局部编辑：支持单段/多段原子替换（edits[] 或 oldText+newText）与超大文件零 OOM 行区间流式补丁（startLine..endLine），支持 expectedSha256 乐观锁防覆盖并发编辑，完整保留原编码/BOM/CRLF/POSIX 权限（+x）/软链，写前自动留存可逆检查点并返回紧凑 Myers Diff。",
    inputSchema: {
      type: "object",
      properties: {
        path: {type: "string", description: "目标文件路径"},
        cwd: {type: "string", description: "可选工作目录"},
        oldText: {type: "string", description: "单段替换时的原精确文本片段"},
        newText: {type: "string", description: "单段替换或行区间替换的新文本"},
        replaceAll: {type: "boolean", description: "是否替换全部匹配项（默认 false 要求唯一匹配）"},
        edits: {
          type: "array",
          description: "原子多段替换列表，任一匹配失败整体不落盘",
          items: {
            type: "object",
            properties: {
              oldText: {type: "string"},
              newText: {type: "string"},
              replaceAll: {type: "boolean"},
            },
            required: ["oldText", "newText"],
            additionalProperties: false,
          },
        },
        startLine: {type: "integer", description: "行区间补丁起始行号（1-based，与 endLine+newText 搭配，支持超大文件零 OOM 流式替换）"},
        endLine: {type: "integer", description: "行区间补丁结束行号（1-based，包含该行）"},
        expectedSha256: {type: "string", description: "可选乐观锁修订号（取自 solbraid_file_read 返回的 sha256），不匹配时中止防踩踏"},
        sessionId: {type: "string", description: "可选会话 ID，用于归档写前检查点"},
      },
      required: ["path"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_file_create",
    description: "Solbraid 增强安全新建文件：默认 O_EXCL 原子独占创建绝不静默清空已有文件，自动级联创建父目录，.sh/Shebang 脚本自动赋权 0755，支持编码/换行预设与写前检查点（撤回可干净删除）。",
    inputSchema: {
      type: "object",
      properties: {
        path: {type: "string", description: "新建文件路径（支持多级相对路径如 src/utils/cache.kt）"},
        cwd: {type: "string", description: "可选工作目录"},
        content: {type: "string", description: "文件初始文本内容（默认空串）"},
        conflictPolicy: {type: "string", enum: ["fail_if_exists", "auto_rename", "overwrite_with_backup"], description: "重名策略（默认 fail_if_exists 阻止覆写）"},
        encoding: {type: "string", description: "可选编码（utf-8 / utf-8-bom / gb18030 / utf-16le）"},
        lineEnding: {type: "string", enum: ["LF", "CRLF"], description: "换行符风格（默认 LF）"},
        mode: {type: "string", description: "可选八进制权限（如 644、755；.sh 默认 755）"},
        sessionId: {type: "string", description: "可选会话 ID"},
      },
      required: ["path"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_file_search",
    description: "Solbraid 超大文件零 OOM 流式单文件检索：支持子串或正则、自动识别 GBK/UTF-16 与二进制拦截，返回命中行号与预览片段。",
    inputSchema: {
      type: "object",
      properties: {
        path: {type: "string", description: "目标文件路径"},
        cwd: {type: "string", description: "可选工作目录"},
        query: {type: "string", description: "检索关键词或正则表达式"},
        useRegex: {type: "boolean", description: "是否按正则匹配（默认 false）"},
        caseSensitive: {type: "boolean", description: "是否区分大小写（默认 false）"},
        maxHits: {type: "integer", description: "最大命中数（默认 80，上限 200）"},
      },
      required: ["path", "query"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_file_revert",
    description: "Solbraid 单文件检查点一键可逆回滚：将指定文件精确还原到最近一次修改前快照（回滚前会自动保存当前态支持反悔；新建文件则干净删除）。",
    inputSchema: {
      type: "object",
      properties: {
        path: {type: "string", description: "目标文件路径"},
        cwd: {type: "string", description: "可选工作目录"},
        checkpointId: {type: "string", description: "可选指定检查点 ID，留空默认回滚最近一次修改"},
        sessionId: {type: "string", description: "可选会话 ID"},
      },
      required: ["path"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_status",
    description: "查询本机 Mobile-Use（AI 手机操控）状态：当前运行模式（Root chroot / 免 Root PRoot）、三级驱动（Root / Shizuku / 无障碍）、通知监听状态、宿主 Shell 开关、屏幕分辨率与急停状态。",
    inputSchema: {type: "object", properties: {}, additionalProperties: false},
  },
  {
    name: "solbraid_mobile_screenshot",
    description: "捕获当前手机屏幕截图（Root/Shizuku 使用 screencap，免 Root 无障碍使用 Android 11+ 原生截屏），支持 Set-of-Mark 编号标注叠加（annotate=true，配合 tap nodeIndex 精准点击）、局部裁剪（crop）、Root 亮屏与短录屏（recordSec），默认以多模态图片块返回并落盘到 ~/.galiais/mobile-shots/。",
    inputSchema: {
      type: "object",
      properties: {
        maxWidth: {type: "integer", description: "可选最大宽度像素（360–1440，默认按设置 720）"},
        quality: {type: "integer", description: "可选 JPEG 质量（40–95，默认 78）"},
        includeImage: {type: "boolean", description: "是否在 MCP 返回中内嵌多模态图片内容块（默认 true）"},
        annotate: {type: "boolean", description: "为 true 时在截屏上叠加 Set-of-Mark 编号框（#0/#1/...）并返回 marks 索引映射"},
        crop: {type: "array", items: {type: "integer"}, description: "可选局部裁剪区域 [x, y, width, height]（原图像素坐标）"},
        wakeScreen: {type: "boolean", description: "Root/Shizuku 下先点亮屏幕再截屏"},
        recordSec: {type: "integer", description: "可选短录屏时长（1–30 秒，仅 Root/Shizuku），输出 MP4 到容器"},
      },
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_ui_tree",
    description: "读取当前手机前台窗口（或全部交互窗口含输入法/弹窗/浮窗）的结构化 UI 节点树，支持元素等待断言（waitFor / waitGone），并缓存索引供 solbraid_mobile_tap(nodeIndex) 一跳直点。",
    inputSchema: {
      type: "object",
      properties: {
        maxNodes: {type: "integer", description: "可选最大节点数（10–240，默认 140）"},
        query: {type: "string", description: "可选过滤关键词，仅返回文本/描述/resourceId 匹配的节点"},
        allWindows: {type: "boolean", description: "为 true 时遍历全部交互窗口（含软键盘、弹窗、画中画与分屏，需无障碍服务）"},
        waitFor: {type: "string", description: "等待指定文本或 resourceId 出现在界面中"},
        waitGone: {type: "string", description: "等待指定文本或 resourceId 从界面消失"},
        timeoutMs: {type: "integer", description: "waitFor / waitGone 最大等待毫秒（500–30000，默认 10000）"},
      },
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_tap",
    description: "在手机屏幕上执行单击、双击或长按：支持精确像素坐标 (x, y)、UI 树/SoM 编号 nodeIndex、或 text / contentDesc / resourceId 自动定位；支持无障碍语义节点动作（useNodeAction）。",
    inputSchema: {
      type: "object",
      properties: {
        x: {type: "integer", description: "点击 X 坐标（像素）"},
        y: {type: "integer", description: "点击 Y 坐标（像素）"},
        nodeIndex: {type: "integer", description: "按最近一次 ui_tree 或 screenshot(annotate=true) 返回的节点索引直点"},
        text: {type: "string", description: "按控件显示文本自动定位点击"},
        contentDesc: {type: "string", description: "按无障碍描述自动定位点击"},
        resourceId: {type: "string", description: "按 View resourceId 自动定位点击"},
        className: {type: "string", description: "可选控件类名过滤（如 Button、EditText）"},
        matchIndex: {type: "integer", description: "多匹配项时的索引（默认 0）"},
        longPress: {type: "boolean", description: "是否为长按（默认 false）"},
        doubleTap: {type: "boolean", description: "是否为双击（默认 false）"},
        useNodeAction: {type: "boolean", description: "无障碍模式下使用语义 ACTION_CLICK/ACTION_LONG_CLICK"},
        durationMs: {type: "integer", description: "按住时长毫秒（点击默认 80，长按默认 650）"},
      },
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_swipe",
    description: "执行滑动、翻页、长按拖拽（drag=true）、真双指缩放（scale/pinch）或自动滚找目标文本（scrollUntilText）。",
    inputSchema: {
      type: "object",
      properties: {
        direction: {type: "string", enum: ["up", "down", "left", "right"], description: "手指滑动方向（如 up=向上滑以向下浏览列表）"},
        distanceRatio: {type: "number", description: "语义方向滑动占屏幕比例（0.15–0.85，默认 0.48）"},
        x1: {type: "integer", description: "起点 X"},
        y1: {type: "integer", description: "起点 Y"},
        x2: {type: "integer", description: "终点 X"},
        y2: {type: "integer", description: "终点 Y"},
        durationMs: {type: "integer", description: "滑动耗时毫秒（默认 320）"},
        drag: {type: "boolean", description: "为 true 时执行长按后拖拽（drag and drop）"},
        pinch: {type: "boolean", description: "为 true 时执行双指缩放（需无障碍服务）"},
        scale: {type: "number", description: "双指缩放倍率（>1 放大，<1 缩小，如 1.8 / 0.5）"},
        cx: {type: "integer", description: "双指缩放中心 X"},
        cy: {type: "integer", description: "双指缩放中心 Y"},
        scrollUntilText: {type: "string", description: "自动按 direction 循环滚动直到出现该文本"},
        maxScrolls: {type: "integer", description: "scrollUntilText 最大滚动次数（默认 8，上限 20）"},
      },
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_type",
    description: "向当前聚焦输入框输入文本（完整支持中文、Emoji、标点与英文，无需安装第三方输入法；支持清空重写与自动提交）。",
    inputSchema: {
      type: "object",
      properties: {
        text: {type: "string", description: "要输入的文本内容（支持中文与 Emoji）"},
        clearFirst: {type: "boolean", description: "输入前是否清空当前输入框内容（默认 false）"},
        submit: {type: "boolean", description: "输入完成后是否触发回车/搜索提交（默认 false）"},
      },
      required: ["text"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_key",
    description: "发送系统导航、媒体或硬件按键事件（back、home、recents、notifications、quick_settings、enter、delete、volume_up、volume_down、power、wakeup、sleep、media_play_pause、screenshot 等）。",
    inputSchema: {
      type: "object",
      properties: {
        key: {type: "string", description: "按键名称或 Android KeyCode 数字"},
      },
      required: ["key"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_app",
    description: "完整应用包与进程生命周期管理：list(列表)、foreground(前台)、launch(启动)、info(版本与权限详情)、open_settings(应用详情页)、extract_apk(提取 APK 到容器)、install(Root/Shizuku 静默装或 PRoot 系统安装器)、uninstall(卸载)、stop(强杀)、clear_data(清数据)、freeze/unfreeze(冻结/解冻)、grant/revoke(权限授撤)。",
    inputSchema: {
      type: "object",
      properties: {
        operation: {
          type: "string",
          enum: ["list", "launch", "foreground", "info", "open_settings", "extract_apk", "install", "uninstall", "stop", "clear_data", "freeze", "unfreeze", "grant", "revoke"],
          description: "操作类型（默认 list）",
        },
        packageName: {type: "string", description: "目标应用包名"},
        name: {type: "string", description: "launch 模式可选按应用名称模糊匹配启动"},
        query: {type: "string", description: "list 模式的名称或包名过滤关键词"},
        limit: {type: "integer", description: "list 模式最大返回条数（默认 60）"},
        apkPath: {type: "string", description: "install 模式 APK 路径（容器路径或 /sdcard 路径）"},
        destPath: {type: "string", description: "extract_apk 模式容器保存路径"},
        permission: {type: "string", description: "grant/revoke 模式 Android 权限名（如 android.permission.CAMERA）"},
        silent: {type: "boolean", description: "Root/Shizuku 下 install/uninstall 是否静默执行（默认 true）"},
      },
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_clipboard",
    description: "读取或写入 Android 手机系统剪贴板（Root chroot 与免 Root PRoot 模式完全一致）。",
    inputSchema: {
      type: "object",
      properties: {
        operation: {type: "string", enum: ["read", "write"], description: "read 读取剪贴板 / write 写入剪贴板"},
        text: {type: "string", description: "operation=write 时要写入剪贴板的文本"},
      },
      required: ["operation"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_device",
    description: "手机硬件与系统开关控制：双模式支持 telemetry(电量/温度/网络/内存/存储/音量)、volume(各通道音量/静音)、torch(手电筒)、vibrate(震动)、media(播放/暂停/切歌)、brightness/auto_rotate/screen_timeout(Root/Shizuku 或 WRITE_SETTINGS)；Root/Shizuku 额外支持 wifi/data/bluetooth/airplane/nfc/dark_mode/dnd/wake/sleep/wm_size/wm_density（PRoot 免 Root 自动弹出对应系统设置面板）。",
    inputSchema: {
      type: "object",
      properties: {
        operation: {
          type: "string",
          enum: ["telemetry", "volume", "torch", "vibrate", "media", "brightness", "auto_rotate", "screen_timeout", "wifi", "data", "bluetooth", "airplane", "nfc", "dark_mode", "dnd", "wake", "sleep", "wm_size", "wm_density"],
          description: "操作类型（默认 telemetry）",
        },
        enabled: {type: "boolean", description: "开关状态（用于 torch/wifi/data/bluetooth/airplane/nfc/dark_mode/dnd/auto_rotate）"},
        stream: {type: "string", enum: ["music", "ring", "alarm", "notification", "call"], description: "volume 通道"},
        level: {type: "integer", description: "volume 绝对挡位"},
        percent: {type: "integer", description: "volume 或 brightness 百分比（0–100）"},
        delta: {type: "integer", description: "volume 相对增减"},
        mute: {type: "boolean", description: "volume 静音开关"},
        showUi: {type: "boolean", description: "调音量时是否显示系统音量条"},
        durationMs: {type: "integer", description: "vibrate 震动毫秒（30–2000）"},
        command: {type: "string", enum: ["play_pause", "play", "pause", "next", "previous", "stop"], description: "media 控制指令"},
        value: {type: "string", description: "brightness(1-255) / wm_size(WxH 或 reset) / wm_density(数字或 reset)"},
        auto: {type: "boolean", description: "brightness 自动亮度开关"},
        seconds: {type: "integer", description: "screen_timeout 息屏秒数（15–3600）"},
        openPanelFallback: {type: "boolean", description: "免 Root 无法静默开关时是否弹出系统设置面板（默认 true）"},
      },
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_intent",
    description: "跨应用直达与系统动作（免 Root PRoot 与 Root chroot 均高效可用）：打开 URL/Deep Link、直达任意系统设置子页/面板、打开拨号/短信/邮件草稿、设置系统闹钟/倒计时、用系统分享/打开容器文件、以及启动组件/广播。",
    inputSchema: {
      type: "object",
      properties: {
        operation: {
          type: "string",
          enum: ["open_url", "open_settings", "dial", "sms_draft", "email_draft", "set_alarm", "set_timer", "share_file", "start_activity", "broadcast"],
          description: "操作类型",
        },
        url: {type: "string", description: "open_url 的网页链接或 Deep Link"},
        page: {type: "string", description: "open_settings 目标页（main/wifi/bluetooth/apps/app_details/battery/location/accessibility/notifications/overlay/write_settings/display/sound/storage/developer/panel_internet/panel_volume/panel_nfc）"},
        packageName: {type: "string", description: "可选指定接收应用的包名"},
        phone: {type: "string", description: "dial / sms_draft 的手机号"},
        to: {type: "string", description: "email_draft 的收件邮箱"},
        subject: {type: "string", description: "email_draft 主题"},
        body: {type: "string", description: "sms_draft / email_draft 正文"},
        hour: {type: "integer", description: "set_alarm 小时（0–23）"},
        minute: {type: "integer", description: "set_alarm 分钟（0–59）"},
        seconds: {type: "integer", description: "set_timer 秒数"},
        message: {type: "string", description: "闹钟或倒计时备注"},
        skipUi: {type: "boolean", description: "设闹钟/倒计时是否跳过确认界面（默认 true）"},
        path: {type: "string", description: "share_file 的容器或 /sdcard 文件路径"},
        mimeType: {type: "string", description: "可选 MIME 类型"},
        open: {type: "boolean", description: "share_file 为 true 时使用 ACTION_VIEW 打开而非分享"},
        title: {type: "string", description: "分享选择器标题"},
        action: {type: "string", description: "start_activity / broadcast 的 Intent Action"},
        component: {type: "string", description: "start_activity 的组件名（pkg/cls）"},
        data: {type: "string", description: "start_activity 的 Data URI"},
        extras: {type: "object", description: "Intent 附加键值 extras"},
      },
      required: ["operation"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_notification",
    description: "手机通知中心交互（chroot 与 PRoot 免 Root 均可用）：读取全部状态栏通知（list）、自动提取近期验证码（otp）、免切屏直接内联回复聊天消息（reply）、点击通知按钮（action）、清除通知（dismiss），Root/Shizuku 下支持自动授权通知监听（auto_grant）。",
    inputSchema: {
      type: "object",
      properties: {
        operation: {type: "string", enum: ["status", "list", "otp", "reply", "action", "dismiss", "auto_grant"], description: "操作类型（默认 list）"},
        query: {type: "string", description: "list 过滤关键词"},
        packageName: {type: "string", description: "list 按包名过滤"},
        limit: {type: "integer", description: "list 最大条数（默认 40）"},
        maxAgeSec: {type: "integer", description: "otp 查找最近多少秒内的验证码（默认 600）"},
        key: {type: "string", description: "目标通知 key（取自 list 返回）"},
        text: {type: "string", description: "reply 回复文本"},
        actionIndex: {type: "integer", description: "action 按钮索引（默认 0）"},
        clearAll: {type: "boolean", description: "dismiss 为 true 时清空全部可清除通知"},
      },
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_files",
    description: "手机宿主存储与当前 Linux 容器工作区双向文件传输：支持列举手机目录（list）、拉取手机文件到容器（pull）、推送容器文件到手机相册/下载并自动刷新媒体库（push）、删除（delete）与媒体库扫描（media_scan）。免 Root PRoot 覆盖 /sdcard 全部共享存储；Root chroot 覆盖任意宿主路径。",
    inputSchema: {
      type: "object",
      properties: {
        operation: {type: "string", enum: ["list", "pull", "push", "delete", "media_scan"], description: "操作类型（默认 list）"},
        hostPath: {type: "string", description: "手机侧路径（如 /sdcard/Download/a.pdf 或 Root 任意路径）"},
        guestPath: {type: "string", description: "Linux 容器侧绝对路径（如 /home/agent/workspace/a.pdf）"},
        limit: {type: "integer", description: "list 最大条目数（默认 100）"},
      },
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_mobile_host",
    description: "Android 宿主底层特权控制（需 Root chroot 或 Shizuku 免 Root 进阶驱动，且须用户在「设置 → 手机操控」开启高危开关「允许 AI 执行宿主底层命令」）：支持受控执行宿主 Shell（shell）、抓取系统与 App 日志（logcat）、读写系统设置三表（settings_get/settings_put）、属性读写（getprop/setprop）、系统服务诊断（dumpsys）与全机进程查看（ps）。",
    inputSchema: {
      type: "object",
      properties: {
        operation: {type: "string", enum: ["shell", "logcat", "settings_get", "settings_put", "getprop", "setprop", "dumpsys", "ps"], description: "操作类型（默认 shell）"},
        command: {type: "string", description: "operation=shell 时的 Android 宿主命令"},
        timeoutSec: {type: "integer", description: "超时秒数（1–90，默认 20）"},
        lines: {type: "integer", description: "logcat 行数（10–600，默认 120）"},
        tag: {type: "string", description: "logcat 过滤 tag"},
        level: {type: "string", enum: ["V", "D", "I", "W", "E"], description: "logcat 最低级别（默认 D）"},
        packageName: {type: "string", description: "logcat 按包名过滤"},
        clear: {type: "boolean", description: "logcat 为 true 时清空日志缓冲区"},
        namespace: {type: "string", enum: ["system", "secure", "global"], description: "settings_get/put 命名空间"},
        key: {type: "string", description: "settings 或 prop 键名"},
        value: {type: "string", description: "settings_put 或 setprop 写入值"},
        service: {type: "string", description: "dumpsys 目标服务名（如 battery/window/meminfo/package）"},
        args: {type: "string", description: "dumpsys 附加参数"},
        query: {type: "string", description: "ps 进程名过滤关键词"},
      },
      additionalProperties: false,
    },
  },
];

function send(packet) {
  process.stdout.write(JSON.stringify(packet) + "\n");
}

const ACTION_TIMEOUT_MS = Object.freeze({
  installDeps: 75_000,
  testDriver: 130_000,
  selfBuild: 920_000,
  selfBuildRequestRollback: 180_000,
  mobileScreenshot: 60_000,
  mobileUiTree: 45_000,
  mobileTap: 180_000,
  mobileSwipe: 180_000,
  mobileType: 180_000,
  mobileKey: 180_000,
  mobileApp: 180_000,
  mobileClipboard: 180_000,
  mobileDevice: 180_000,
  mobileIntent: 180_000,
  mobileNotification: 180_000,
  mobileFiles: 90_000,
  mobileHost: 120_000,
});

function callPluginCtl(action, params = {}) {
  const {socketPath, token} = resolveBridgeConfig();
  if (!token) return Promise.resolve({ok: false, error: "Solbraid 插件控制通道令牌未就绪，请确认 agent-hub 正在运行"});
  const timeoutMs = ACTION_TIMEOUT_MS[action] || 30_000;
  return new Promise(resolve => {
    const conn = net.createConnection(socketPath);
    let buf = "";
    let settled = false;
    const timer = setTimeout(() => finish({ok: false, error: `插件控制通道请求超时（>${Math.round(timeoutMs / 1000)}s）`}), timeoutMs);
    const finish = val => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      conn.destroy();
      resolve(val);
    };
    conn.setEncoding("utf8");
    conn.once("connect", () => conn.write(JSON.stringify({token, action, params}) + "\n"));
    conn.on("data", chunk => {
      buf += chunk;
      if (buf.length > 8 * 1024 * 1024) { finish({ok: false, error: "响应超过大小限制"}); return; }
      const nl = buf.indexOf("\n");
      if (nl < 0) return;
      try {
        const reply = JSON.parse(buf.slice(0, nl));
        finish(reply);
      } catch {
        finish({ok: false, error: "插件控制通道返回格式无效"});
      }
    });
    conn.once("error", err => finish({ok: false, error: `无法连接插件控制通道 (${socketPath})：${err.message}`}));
    conn.once("close", () => finish({ok: false, error: "插件控制通道已关闭"}));
  });
}

const TOOL_ACTION_MAP = Object.freeze({
  solbraid_plugin_list: "list",
  solbraid_plugin_read: "read",
  solbraid_plugin_scaffold: "scaffold",
  solbraid_plugin_validate: "validate",
  solbraid_plugin_apply: "apply",
  solbraid_plugin_reload: "reload",
  solbraid_plugin_test: "test",
  solbraid_plugin_logs: "logs",
  solbraid_plugin_snapshots: "snapshots",
  solbraid_plugin_rollback: "rollback",
  solbraid_plugin_install_deps: "installDeps",
  solbraid_plugin_test_sdui: "testSdui",
  solbraid_studio_override: "studioOverride",
  solbraid_plugin_test_driver: "testDriver",
  solbraid_self_build: "selfBuild",
  solbraid_file_read: "fileRead",
  solbraid_file_edit: "fileEdit",
  solbraid_file_create: "fileCreate",
  solbraid_file_search: "fileSearch",
  solbraid_file_revert: "fileRevert",
  solbraid_mobile_status: "mobileStatus",
  solbraid_mobile_screenshot: "mobileScreenshot",
  solbraid_mobile_ui_tree: "mobileUiTree",
  solbraid_mobile_tap: "mobileTap",
  solbraid_mobile_swipe: "mobileSwipe",
  solbraid_mobile_type: "mobileType",
  solbraid_mobile_key: "mobileKey",
  solbraid_mobile_app: "mobileApp",
  solbraid_mobile_clipboard: "mobileClipboard",
  solbraid_mobile_device: "mobileDevice",
  solbraid_mobile_intent: "mobileIntent",
  solbraid_mobile_notification: "mobileNotification",
  solbraid_mobile_files: "mobileFiles",
  solbraid_mobile_host: "mobileHost",
});

async function handle(message) {
  const {id, method, params} = message;
  if (id === undefined) return;
  try {
    if (method === "initialize") {
      send({
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion: params?.protocolVersion ?? "2025-06-18",
          capabilities: {tools: {}},
          serverInfo: {name: "solbraid-plugin-ctl", version: "1.0.0"},
        },
      });
    } else if (method === "ping") {
      send({jsonrpc: "2.0", id, result: {}});
    } else if (method === "tools/list") {
      send({jsonrpc: "2.0", id, result: {tools: TOOLS}});
    } else if (method === "tools/call") {
      const name = String(params?.name || "");
      const action = TOOL_ACTION_MAP[name];
      if (!action) throw Object.assign(new Error(`未知插件运维工具：${name}`), {code: -32602});
      const reply = await callPluginCtl(action, params?.arguments || {});
      const isErr = reply?.ok !== true;
      const content = [];
      let payload = reply?.ok ? reply.data : {error: reply?.error || "操作失败", details: reply?.data};
      if (!isErr && payload && typeof payload === "object" && typeof payload._mcpImageBase64 === "string" && payload._mcpImageBase64.length > 0) {
        const {_mcpImageBase64, _mcpMimeType, ...rest} = payload;
        content.push({type: "image", data: _mcpImageBase64, mimeType: _mcpMimeType || "image/jpeg"});
        payload = rest;
      }
      content.push({type: "text", text: JSON.stringify(payload, null, 2)});
      send({jsonrpc: "2.0", id, result: {content, isError: isErr}});
    } else {
      throw Object.assign(new Error(`Method not found: ${method}`), {code: -32601});
    }
  } catch (error) {
    send({jsonrpc: "2.0", id, error: {code: error.code ?? -32603, message: error.message}});
  }
}

if (["--self-build-apply", "--self-build-rollback"].includes(process.argv[2])) {
  const reply = process.argv[2] === "--self-build-rollback"
    ? await callPluginCtl("selfBuildRequestRollback")
    : await callPluginCtl("selfBuild", {action: "apply", apkPath: process.argv[3] || "", confirmInstall: true});
  process.stdout.write(JSON.stringify(reply.ok ? reply.data : {ok: false, error: reply.error}) + "\n");
  process.exit(reply.ok ? 0 : 1);
}

let input = "";
let inFlight = 0;
let stdinEnded = false;
function maybeExit() {
  if (stdinEnded && inFlight === 0) process.exit(0);
}
process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => {
  input += chunk;
  if (input.length > 8 * 1024 * 1024) process.exit(1);
  let end;
  while ((end = input.indexOf("\n")) >= 0) {
    const line = input.slice(0, end).trim();
    input = input.slice(end + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { continue; }
    inFlight += 1;
    void handle(msg).finally(() => {
      inFlight -= 1;
      maybeExit();
    });
  }
});
process.stdin.once("end", () => {
  stdinEnded = true;
  maybeExit();
});
