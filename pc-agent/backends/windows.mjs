// Windows 10/11 原生桌面操控后端（Per-Monitor V2 DPI 感知截屏 / UIA 控件树 / Win32 SendInput 键鼠 / 窗口 / 进程 / 音量 / 电源 / 剪贴板）
// 零第三方依赖：基于系统内置 PowerShell + 内联 .NET/Win32 API（UTF-8 无乱码）
import {spawn} from "node:child_process";

const WIN32_CSHARP = `
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class SolbraidWin32 {
    [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT lpPoint);
    [DllImport("user32.dll", SetLastError=true)] public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
    [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool LockWorkStation();
    [DllImport("user32.dll")] public static extern IntPtr SendMessageW(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }

    [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public InputUnion U; }
    [StructLayout(LayoutKind.Explicit)] public struct InputUnion {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }
    [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT {
        public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo;
    }
    [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT {
        public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo;
    }

    public static void InitDpi() {
        try { SetProcessDpiAwarenessContext(new IntPtr(-4)); }
        catch { try { SetProcessDPIAware(); } catch {} }
    }

    public static void MouseEvent(uint flags, uint data) {
        var inp = new INPUT[1];
        inp[0].type = 0;
        inp[0].U.mi.dwFlags = flags;
        inp[0].U.mi.mouseData = data;
        SendInput(1, inp, Marshal.SizeOf(typeof(INPUT)));
    }

    public static void SendVk(ushort vk, bool keyUp) {
        var inp = new INPUT[1];
        inp[0].type = 1;
        inp[0].U.ki.wVk = vk;
        inp[0].U.ki.dwFlags = keyUp ? 2u : 0u;
        SendInput(1, inp, Marshal.SizeOf(typeof(INPUT)));
    }

    public static void SendUnicodeText(string text) {
        if (string.IsNullOrEmpty(text)) return;
        var list = new List<INPUT>();
        foreach (char c in text) {
            if (c == '\\r') continue;
            if (c == '\\n') {
                list.Add(new INPUT { type = 1, U = new InputUnion { ki = new KEYBDINPUT { wVk = 0x0D, dwFlags = 0 } } });
                list.Add(new INPUT { type = 1, U = new InputUnion { ki = new KEYBDINPUT { wVk = 0x0D, dwFlags = 2 } } });
                continue;
            }
            list.Add(new INPUT { type = 1, U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = 4 } } });
            list.Add(new INPUT { type = 1, U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = 6 } } });
        }
        if (list.Count > 0) SendInput((uint)list.Count, list.ToArray(), Marshal.SizeOf(typeof(INPUT)));
    }

    public static List<object> ListWindows() {
        var res = new List<object>();
        var fg = GetForegroundWindow();
        EnumWindows((hWnd, lParam) => {
            if (!IsWindowVisible(hWnd)) return true;
            int len = GetWindowTextLength(hWnd);
            if (len <= 0) return true;
            var sb = new StringBuilder(len + 1);
            GetWindowText(hWnd, sb, sb.Capacity);
            string title = sb.ToString();
            if (string.IsNullOrWhiteSpace(title)) return true;
            RECT r; GetWindowRect(hWnd, out r);
            if (r.Right - r.Left <= 0 || r.Bottom - r.Top <= 0) return true;
            uint pid = 0; GetWindowThreadProcessId(hWnd, out pid);
            string procName = "";
            try { procName = System.Diagnostics.Process.GetProcessById((int)pid).ProcessName; } catch {}
            res.Add(new {
                hwnd = hWnd.ToInt64().ToString(),
                title = title,
                pid = (int)pid,
                process = procName,
                active = hWnd == fg,
                x = r.Left, y = r.Top,
                width = r.Right - r.Left, height = r.Bottom - r.Top
            });
            return true;
        }, IntPtr.Zero);
        return res;
    }
}
`;

function cleanPsError(raw) {
  const s = String(raw || "").trim();
  if (!s) return "";
  if (s.includes("#< CLIXML")) {
    const matches = [...s.matchAll(/<S S="Error">([\s\S]*?)<\/S>/g)].map(m =>
      m[1].replace(/_x000D__x000A_/g, "\n").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&").trim(),
    ).filter(Boolean);
    if (matches.length) return matches.join("\n").trim();
  }
  return s;
}

function runPsJson(bodyScript, payload = {}, timeoutMs = 25_000) {
  const payloadB64 = Buffer.from(JSON.stringify(payload), "utf8").toString("base64");
  const full = `
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$payload = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${payloadB64}')) | ConvertFrom-Json
${bodyScript}
`;
  const encoded = Buffer.from(full, "utf16le").toString("base64");
  return new Promise((resolve, reject) => {
    let stdout = "";
    let stderr = "";
    let settled = false;
    const child = spawn("powershell.exe", [
      "-NoProfile", "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
    ], {stdio: ["ignore", "pipe", "pipe"], windowsHide: true});
    const finish = (err, val) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (err) reject(err); else resolve(val);
    };
    const timer = setTimeout(() => {
      try { child.kill("SIGKILL"); } catch {}
      finish(new Error(`Windows PowerShell 操作超时 (${Math.round(timeoutMs / 1000)}s)`));
    }, timeoutMs);
    child.on("error", err => finish(err));
    child.stdout.on("data", d => { stdout += d.toString("utf8"); });
    child.stderr.on("data", d => { stderr += d.toString("utf8"); });
    child.on("close", code => {
      if (code !== 0) {
        const cleaned = cleanPsError(stderr) || cleanPsError(stdout) || `PowerShell exit ${code}`;
        if (/CopyFromScreen[\s\S]*handle is invalid|句柄无效/i.test(cleaned)) {
          return finish(new Error("无法截取 Windows 桌面（The handle is invalid）：当前电脑处于锁屏/安全桌面，或 pc-agent 运行在非交互会话中。请在已解锁的交互桌面终端运行 start-windows.ps1。"));
        }
        return finish(new Error(cleaned));
      }
      const trimmed = stdout.trim();
      if (!trimmed) return finish(null, {});
      try {
        const lastJsonIdx = trimmed.lastIndexOf("\n");
        const candidate = lastJsonIdx >= 0 && trimmed.slice(lastJsonIdx + 1).trim().startsWith("{")
          ? trimmed.slice(lastJsonIdx + 1).trim()
          : trimmed;
        finish(null, JSON.parse(candidate));
      } catch (e) {
        finish(new Error(`解析 Windows 输出失败：${e.message} | ${trimmed.slice(0, 200)}`));
      }
    });
  });
}

const WIN32_BOOT = `
Add-Type -TypeDefinition @'
${WIN32_CSHARP}
'@ -Language CSharp
[SolbraidWin32]::InitDpi()
`;

import {collectSystemInfo} from "./common.mjs";

const VK_MAP = {
  backspace: 0x08, tab: 0x09, enter: 0x0D, return: 0x0D, shift: 0x10, ctrl: 0x11, control: 0x11,
  alt: 0x12, menu: 0x12, pause: 0x13, capslock: 0x14, esc: 0x1B, escape: 0x1B, space: 0x20,
  pageup: 0x21, pgup: 0x21, pagedown: 0x22, pgdn: 0x22, end: 0x23, home: 0x24,
  left: 0x25, up: 0x26, right: 0x27, down: 0x28, printscreen: 0x2C, insert: 0x2D, delete: 0x2E, del: 0x2E,
  win: 0x5B, lwin: 0x5B, cmd: 0x5B, super: 0x5B, meta: 0x5B, rwin: 0x5C,
  f1: 0x70, f2: 0x71, f3: 0x72, f4: 0x73, f5: 0x74, f6: 0x75,
  f7: 0x76, f8: 0x77, f9: 0x78, f10: 0x79, f11: 0x7A, f12: 0x7B,
};

function resolveVk(keyName) {
  const k = String(keyName || "").trim().toLowerCase();
  if (k in VK_MAP) return VK_MAP[k];
  if (k.length === 1) {
    const code = k.toUpperCase().charCodeAt(0);
    if ((code >= 0x30 && code <= 0x39) || (code >= 0x41 && code <= 0x5A)) return code;
  }
  throw new Error(`未识别的 Windows 键名：${keyName}`);
}

export async function screenshot(params = {}) {
  const ps = `
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
${WIN32_BOOT}
$screens = [System.Windows.Forms.Screen]::AllScreens
$monList = @()
for ($i = 0; $i -lt $screens.Length; $i++) {
  $b = $screens[$i].Bounds
  $monList += [ordered]@{ index = $i; primary = $screens[$i].Primary; x = $b.X; y = $b.Y; width = $b.Width; height = $b.Height }
}
$rx = 0; $ry = 0; $rw = 0; $rh = 0
if ($payload.region -and $payload.region.width -gt 0 -and $payload.region.height -gt 0) {
  $rx = [int]$payload.region.x; $ry = [int]$payload.region.y; $rw = [int]$payload.region.width; $rh = [int]$payload.region.height
} elseif ($payload.windowTitle) {
  $wins = [SolbraidWin32]::ListWindows()
  $hit = $wins | Where-Object { $_.title -like "*$($payload.windowTitle)*" } | Select-Object -First 1
  if (-not $hit) { throw "未找到匹配标题的窗口：$($payload.windowTitle)" }
  $rx = [int]$hit.x; $ry = [int]$hit.y; $rw = [int]$hit.width; $rh = [int]$hit.height
} elseif ($null -ne $payload.monitor -and [int]$payload.monitor -eq -1) {
  $vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
  $rx = $vs.X; $ry = $vs.Y; $rw = $vs.Width; $rh = $vs.Height
} else {
  $idx = if ($null -ne $payload.monitor) { [int]$payload.monitor } else { 0 }
  if ($idx -lt 0 -or $idx -ge $screens.Length) { $idx = 0 }
  $b = $screens[$idx].Bounds
  $rx = $b.X; $ry = $b.Y; $rw = $b.Width; $rh = $b.Height
}
$bmp = New-Object System.Drawing.Bitmap($rw, $rh)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($rx, $ry, 0, 0, $bmp.Size)
$g.Dispose()

$maxW = if ($payload.maxWidth) { [Math]::Max(320, [Math]::Min(2560, [int]$payload.maxWidth)) } else { 1280 }
$outW = $rw; $outH = $rh
$finalBmp = $bmp
if ($rw -gt $maxW) {
  $outW = $maxW
  $outH = [Math]::Max(1, [int][Math]::Round($rh * ($maxW / [double]$rw)))
  $scaled = New-Object System.Drawing.Bitmap($outW, $outH)
  $sg = [System.Drawing.Graphics]::FromImage($scaled)
  $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $sg.DrawImage($bmp, 0, 0, $outW, $outH)
  $sg.Dispose()
  $bmp.Dispose()
  $finalBmp = $scaled
}
$qual = if ($payload.quality) { [long][Math]::Max(30, [Math]::Min(95, [int]$payload.quality)) } else { 72L }
$codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' } | Select-Object -First 1
$ep = New-Object System.Drawing.Imaging.EncoderParameters(1)
$ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, $qual)
$ms = New-Object System.IO.MemoryStream
$finalBmp.Save($ms, $codec, $ep)
$b64 = [Convert]::ToBase64String($ms.ToArray())
$ms.Dispose(); $finalBmp.Dispose()
[ordered]@{
  ok = $true; originX = $rx; originY = $ry; screenWidth = $rw; screenHeight = $rh;
  width = $outW; height = $outH; monitors = $monList; mimeType = 'image/jpeg'; imageBase64 = $b64
} | ConvertTo-Json -Compress -Depth 4
`;
  return await runPsJson(ps, params, 25_000);
}

export async function uiTree(params = {}) {
  const ps = `
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
${WIN32_BOOT}
$root = $null
if ($payload.windowTitle) {
  $wins = [SolbraidWin32]::ListWindows()
  $hit = $wins | Where-Object { $_.title -like "*$($payload.windowTitle)*" } | Select-Object -First 1
  if ($hit) { $root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]([long]$hit.hwnd)) }
}
if (-not $root) {
  $fg = [SolbraidWin32]::GetForegroundWindow()
  if ($fg -ne [IntPtr]::Zero) { $root = [System.Windows.Automation.AutomationElement]::FromHandle($fg) }
}
if (-not $root) { $root = [System.Windows.Automation.AutomationElement]::RootElement }

$maxDepth = if ($payload.maxDepth) { [Math]::Max(1, [Math]::Min(8, [int]$payload.maxDepth)) } else { 5 }
$maxNodes = if ($payload.maxNodes) { [Math]::Max(10, [Math]::Min(250, [int]$payload.maxNodes)) } else { 100 }
$query = if ($payload.query) { "$($payload.query)".ToLowerInvariant() } else { "" }
$clickName = if ($payload.clickName) { "$($payload.clickName)".ToLowerInvariant() } else { "" }
$nodes = New-Object System.Collections.ArrayList
$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker

function Walk($el, $depth) {
  if ($null -eq $el -or $depth -gt $maxDepth -or $nodes.Count -ge $maxNodes) { return }
  try {
    $name = $el.Current.Name
    $aid = $el.Current.AutomationId
    $ctype = $el.Current.ControlType.ProgrammaticName -replace '^ControlType\\.', ''
    $rect = $el.Current.BoundingRectangle
    if (-not $rect.IsEmpty -and $rect.Width -gt 0 -and $rect.Height -gt 0) {
      $matchQ = ($query -eq "") -or ($name -and $name.ToLowerInvariant().Contains($query)) -or ($aid -and $aid.ToLowerInvariant().Contains($query)) -or ($ctype.ToLowerInvariant().Contains($query))
      if ($matchQ -and ($name -or $aid)) {
        $null = $nodes.Add([ordered]@{
          id = "uia-$($nodes.Count)"
          name = $name
          automationId = $aid
          controlType = $ctype
          enabled = $el.Current.IsEnabled
          x = [int]$rect.X; y = [int]$rect.Y; width = [int]$rect.Width; height = [int]$rect.Height
          cx = [int]($rect.X + $rect.Width / 2); cy = [int]($rect.Y + $rect.Height / 2)
        })
      }
    }
    $child = $walker.GetFirstChild($el)
    $i = 0
    while ($null -ne $child -and $nodes.Count -lt $maxNodes -and $i -lt 50) {
      Walk $child ($depth + 1)
      $child = $walker.GetNextSibling($child)
      $i++
    }
  } catch {}
}
Walk $root 0
$clicked = $null
if ($clickName) {
  $target = $nodes | Where-Object { $_.name -and $_.name.ToLowerInvariant().Contains($clickName) } | Select-Object -First 1
  if ($target) {
    [SolbraidWin32]::SetCursorPos([int]$target.cx, [int]$target.cy) | Out-Null
    [SolbraidWin32]::MouseEvent(2, 0)
    [SolbraidWin32]::MouseEvent(4, 0)
    $clicked = $target
  }
}
[ordered]@{ ok = $true; rootName = $root.Current.Name; count = $nodes.Count; clicked = $clicked; nodes = @($nodes) } | ConvertTo-Json -Compress -Depth 5
`;
  return await runPsJson(ps, params, 25_000);
}

export async function windowAction(params = {}) {
  const ps = `
${WIN32_BOOT}
$act = "$($payload.action)".ToLowerInvariant()
$wins = [SolbraidWin32]::ListWindows()
if ($act -eq 'list' -or $act -eq '') {
  [ordered]@{ ok = $true; count = $wins.Count; windows = @($wins) } | ConvertTo-Json -Compress -Depth 4
  exit 0
}
if ($act -eq 'active') {
  $hit = $wins | Where-Object { $_.active } | Select-Object -First 1
  [ordered]@{ ok = $true; window = $hit } | ConvertTo-Json -Compress -Depth 4
  exit 0
}
$target = $null
if ($payload.hwnd) { $target = $wins | Where-Object { $_.hwnd -eq "$($payload.hwnd)" } | Select-Object -First 1 }
elseif ($payload.pid) { $target = $wins | Where-Object { $_.pid -eq [int]$payload.pid } | Select-Object -First 1 }
elseif ($payload.title) { $target = $wins | Where-Object { $_.title -like "*$($payload.title)*" } | Select-Object -First 1 }
if (-not $target) { throw "未匹配到目标窗口" }
$h = [IntPtr]([long]$target.hwnd)
switch ($act) {
  'focus'    { [SolbraidWin32]::ShowWindow($h, 9) | Out-Null; [SolbraidWin32]::SetForegroundWindow($h) | Out-Null }
  'restore'  { [SolbraidWin32]::ShowWindow($h, 9) | Out-Null }
  'minimize' { [SolbraidWin32]::ShowWindow($h, 6) | Out-Null }
  'maximize' { [SolbraidWin32]::ShowWindow($h, 3) | Out-Null }
  'move'     {
    $nx = if ($null -ne $payload.x) { [int]$payload.x } else { [int]$target.x }
    $ny = if ($null -ne $payload.y) { [int]$payload.y } else { [int]$target.y }
    $nw = if ($null -ne $payload.width) { [int]$payload.width } else { [int]$target.width }
    $nh = if ($null -ne $payload.height) { [int]$payload.height } else { [int]$target.height }
    [SolbraidWin32]::MoveWindow($h, $nx, $ny, $nw, $nh, $true) | Out-Null
  }
  'close'    { [SolbraidWin32]::PostMessage($h, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null }
  default    { throw "未知窗口动作：$act" }
}
[ordered]@{ ok = $true; action = $act; window = $target } | ConvertTo-Json -Compress -Depth 4
`;
  return await runPsJson(ps, params, 15_000);
}

export async function mouseAction(params = {}) {
  const ps = `
Add-Type -AssemblyName System.Windows.Forms
${WIN32_BOOT}
$pb = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$x = if ($null -ne $payload.x) { [double]$payload.x } else { 0.0 }
$y = if ($null -ne $payload.y) { [double]$payload.y } else { 0.0 }
$tx = if ($null -ne $payload.toX) { [double]$payload.toX } else { $x }
$ty = if ($null -ne $payload.toY) { [double]$payload.toY } else { $y }
if ($payload.normalized -eq $true) {
  $x = $pb.X + ($x / 1000.0) * $pb.Width
  $y = $pb.Y + ($y / 1000.0) * $pb.Height
  $tx = $pb.X + ($tx / 1000.0) * $pb.Width
  $ty = $pb.Y + ($ty / 1000.0) * $pb.Height
}
$ix = [int][Math]::Round($x); $iy = [int][Math]::Round($y)
$itx = [int][Math]::Round($tx); $ity = [int][Math]::Round($ty)
$act = "$($payload.action)".ToLowerInvariant()
if ($null -ne $payload.x -and $null -ne $payload.y) { [SolbraidWin32]::SetCursorPos($ix, $iy) | Out-Null }
switch ($act) {
  'move' {}
  'click' { [SolbraidWin32]::MouseEvent(2, 0); [SolbraidWin32]::MouseEvent(4, 0) }
  'double_click' {
    [SolbraidWin32]::MouseEvent(2, 0); [SolbraidWin32]::MouseEvent(4, 0)
    Start-Sleep -Milliseconds 60
    [SolbraidWin32]::MouseEvent(2, 0); [SolbraidWin32]::MouseEvent(4, 0)
  }
  'right_click' { [SolbraidWin32]::MouseEvent(8, 0); [SolbraidWin32]::MouseEvent(16, 0) }
  'middle_click' { [SolbraidWin32]::MouseEvent(32, 0); [SolbraidWin32]::MouseEvent(64, 0) }
  'down' { $f = if ($payload.button -eq 'right') { 8 } elseif ($payload.button -eq 'middle') { 32 } else { 2 }; [SolbraidWin32]::MouseEvent($f, 0) }
  'up' { $f = if ($payload.button -eq 'right') { 16 } elseif ($payload.button -eq 'middle') { 64 } else { 4 }; [SolbraidWin32]::MouseEvent($f, 0) }
  'drag' {
    [SolbraidWin32]::MouseEvent(2, 0)
    $steps = 12
    $dur = if ($payload.durationMs) { [Math]::Max(80, [Math]::Min(2000, [int]$payload.durationMs)) } else { 240 }
    for ($i = 1; $i -le $steps; $i++) {
      $cx = [int][Math]::Round($ix + ($itx - $ix) * ($i / [double]$steps))
      $cy = [int][Math]::Round($iy + ($ity - $iy) * ($i / [double]$steps))
      [SolbraidWin32]::SetCursorPos($cx, $cy) | Out-Null
      Start-Sleep -Milliseconds ([int]($dur / $steps))
    }
    [SolbraidWin32]::MouseEvent(4, 0)
  }
  'scroll' {
    $dy = if ($null -ne $payload.deltaY) { [int]$payload.deltaY } else { -240 }
    [SolbraidWin32]::MouseEvent(0x0800, [BitConverter]::ToUInt32([BitConverter]::GetBytes($dy), 0))
  }
  default { throw "未知鼠标动作：$act" }
}
[ordered]@{ ok = $true; action = $act; x = $ix; y = $iy; screenWidth = $pb.Width; screenHeight = $pb.Height } | ConvertTo-Json -Compress
`;
  return await runPsJson(ps, params, 15_000);
}

export async function keyboardAction(params = {}) {
  const act = String(params.action || "type").toLowerCase();
  let vks = [];
  if (act === "key") vks = [resolveVk(params.key)];
  else if (act === "hotkey") {
    const keys = Array.isArray(params.keys) ? params.keys : [];
    if (!keys.length) throw new Error("hotkey 需要提供 keys 数组");
    vks = keys.map(resolveVk);
  }
  const ps = `
${WIN32_BOOT}
$act = "$($payload.action)".ToLowerInvariant()
if ($act -eq 'type') {
  [SolbraidWin32]::SendUnicodeText("$($payload.text)")
  if ($payload.pressEnter -eq $true) {
    [SolbraidWin32]::SendVk(0x0D, $false); [SolbraidWin32]::SendVk(0x0D, $true)
  }
} elseif ($act -eq 'key') {
  $vk = [uint16]$payload.vks[0]
  [SolbraidWin32]::SendVk($vk, $false); [SolbraidWin32]::SendVk($vk, $true)
} elseif ($act -eq 'hotkey') {
  foreach ($vk in $payload.vks) { [SolbraidWin32]::SendVk([uint16]$vk, $false); Start-Sleep -Milliseconds 15 }
  for ($i = $payload.vks.Count - 1; $i -ge 0; $i--) { [SolbraidWin32]::SendVk([uint16]$payload.vks[$i], $true); Start-Sleep -Milliseconds 15 }
} else { throw "未知键盘动作：$act" }
[ordered]@{ ok = $true; action = $act } | ConvertTo-Json -Compress
`;
  return await runPsJson(ps, {...params, vks}, 15_000);
}

export async function processAction(params = {}) {
  const ps = `
$act = "$($payload.action)".ToLowerInvariant()
if ($act -eq 'list' -or $act -eq '') {
  $q = if ($payload.query) { "$($payload.query)".ToLowerInvariant() } else { "" }
  $lim = if ($payload.limit) { [Math]::Max(5, [Math]::Min(100, [int]$payload.limit)) } else { 40 }
  $procs = Get-Process | Where-Object { $q -eq "" -or $_.ProcessName.ToLowerInvariant().Contains($q) -or ($_.MainWindowTitle -and $_.MainWindowTitle.ToLowerInvariant().Contains($q)) } |
    Sort-Object WorkingSet64 -Descending | Select-Object -First $lim | ForEach-Object {
      [ordered]@{ pid = $_.Id; name = $_.ProcessName; memMB = [int]($_.WorkingSet64 / 1MB); title = $_.MainWindowTitle }
    }
  [ordered]@{ ok = $true; count = @($procs).Count; processes = @($procs) } | ConvertTo-Json -Compress -Depth 4
} elseif ($act -eq 'launch') {
  $target = "$($payload.target)"
  if (-not $target) { throw "launch 需要 target" }
  $p = if ($payload.args -and $payload.args.Count -gt 0) { Start-Process -FilePath $target -ArgumentList @($payload.args) -PassThru } else { Start-Process -FilePath $target -PassThru }
  [ordered]@{ ok = $true; launched = $target; pid = if ($p) { $p.Id } else { 0 } } | ConvertTo-Json -Compress
} elseif ($act -eq 'kill') {
  if ($payload.pid) { Stop-Process -Id ([int]$payload.pid) -Force }
  elseif ($payload.name) { Stop-Process -Name "$($payload.name)" -Force }
  else { throw "kill 需要提供 pid 或 name" }
  [ordered]@{ ok = $true; killed = if ($payload.pid) { $payload.pid } else { $payload.name } } | ConvertTo-Json -Compress
} else { throw "未知进程动作：$act" }
`;
  return await runPsJson(ps, params, 15_000);
}

export async function systemAction(params = {}) {
  const act = String(params.action || "info").toLowerCase();
  if (act === "info") {
    const ps = `
Add-Type -AssemblyName System.Windows.Forms
${WIN32_BOOT}
$mons = @()
$sc = [System.Windows.Forms.Screen]::AllScreens
for ($i = 0; $i -lt $sc.Length; $i++) { $b = $sc[$i].Bounds; $mons += [ordered]@{ index = $i; primary = $sc[$i].Primary; x = $b.X; y = $b.Y; width = $b.Width; height = $b.Height } }
$disks = Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Used -ne $null } | ForEach-Object {
  [ordered]@{ name = $_.Name; freeGB = [Math]::Round($_.Free / 1GB, 1); totalGB = [Math]::Round(($_.Used + $_.Free) / 1GB, 1) }
}
[ordered]@{ monitors = $mons; disks = @($disks) } | ConvertTo-Json -Compress -Depth 4
`;
    const extra = await runPsJson(ps, {}, 12_000).catch(() => ({}));
    return {ok: true, ...collectSystemInfo(), ...extra};
  }
  if (act === "monitors") {
    const info = await systemAction({action: "info"});
    return {ok: true, monitors: info.monitors || []};
  }
  const ps = `
${WIN32_BOOT}
$act = "$($payload.action)".ToLowerInvariant()
if ($act -eq 'mute') {
  [SolbraidWin32]::SendVk(0xAD, $false); [SolbraidWin32]::SendVk(0xAD, $true)
  [ordered]@{ ok = $true; action = 'mute_toggled' } | ConvertTo-Json -Compress
} elseif ($act -eq 'volume') {
  $lvl = [Math]::Max(0, [Math]::Min(100, [int]$payload.level))
  for ($i = 0; $i -lt 50; $i++) { [SolbraidWin32]::SendVk(0xAE, $false); [SolbraidWin32]::SendVk(0xAE, $true) }
  $steps = [int][Math]::Round($lvl / 2)
  for ($i = 0; $i -lt $steps; $i++) { [SolbraidWin32]::SendVk(0xAF, $false); [SolbraidWin32]::SendVk(0xAF, $true) }
  [ordered]@{ ok = $true; level = $lvl } | ConvertTo-Json -Compress
} elseif ($act -eq 'lock') {
  [SolbraidWin32]::LockWorkStation() | Out-Null
  [ordered]@{ ok = $true; action = 'lock' } | ConvertTo-Json -Compress
} elseif ($act -eq 'sleep') {
  Add-Type -AssemblyName System.Windows.Forms
  [System.Windows.Forms.Application]::SetSuspendState('Suspend', $false, $false) | Out-Null
  [ordered]@{ ok = $true; action = 'sleep' } | ConvertTo-Json -Compress
} elseif ($act -eq 'restart') {
  shutdown.exe /r /t 2 | Out-Null
  [ordered]@{ ok = $true; action = 'restart' } | ConvertTo-Json -Compress
} elseif ($act -eq 'shutdown') {
  shutdown.exe /s /t 2 | Out-Null
  [ordered]@{ ok = $true; action = 'shutdown' } | ConvertTo-Json -Compress
} else { throw "未知系统动作：$act" }
`;
  return await runPsJson(ps, params, 15_000);
}

export async function clipboardAction(params = {}) {
  const ps = `
$act = "$($payload.action)".ToLowerInvariant()
if ($act -eq 'get' -or $act -eq '') {
  $txt = Get-Clipboard -Raw -ErrorAction SilentlyContinue
  [ordered]@{ ok = $true; text = if ($null -eq $txt) { "" } else { "$txt" } } | ConvertTo-Json -Compress
} elseif ($act -eq 'set') {
  Set-Clipboard -Value "$($payload.text)"
  [ordered]@{ ok = $true; length = "$($payload.text)".Length } | ConvertTo-Json -Compress
} else { throw "未知剪贴板动作：$act" }
`;
  return await runPsJson(ps, params, 10_000);
}
