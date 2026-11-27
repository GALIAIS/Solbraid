//go:build windows

package main

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

var (
	enumWinMu     sync.Mutex
	enumWinOnce   sync.Once
	enumWinCB     uintptr
	enumWinFG     uintptr
	enumWinResult []WindowItem
)

func initEnumWindowsCB() {
	enumWinCB = syscall.NewCallback(func(hwnd uintptr, lParam uintptr) uintptr {
		vis, _, _ := pIsWindowVisible.Call(hwnd)
		if vis == 0 {
			return 1
		}
		ln, _, _ := pGetWindowTextLengthW.Call(hwnd)
		if ln == 0 {
			return 1
		}
		buf := make([]uint16, ln+1)
		_, _, _ = pGetWindowTextW.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
		title := strings.TrimSpace(syscall.UTF16ToString(buf))
		if title == "" {
			return 1
		}
		var rc winRect
		_, _, _ = pGetWindowRect.Call(hwnd, uintptr(unsafe.Pointer(&rc)))
		w, h := int(rc.Right-rc.Left), int(rc.Bottom-rc.Top)
		if w <= 0 || h <= 0 {
			return 1
		}
		var pid uint32
		_, _, _ = pGetWindowThreadProcessId.Call(hwnd, uintptr(unsafe.Pointer(&pid)))
		procName := queryProcName(pid)
		enumWinResult = append(enumWinResult, WindowItem{
			Hwnd:    strconv.FormatUint(uint64(hwnd), 10),
			Title:   title,
			PID:     int(pid),
			Process: procName,
			Active:  hwnd == enumWinFG,
			X:       int(rc.Left),
			Y:       int(rc.Top),
			Width:   w,
			Height:  h,
		})
		return 1
	})
}

type processEntry32W struct {
	dwSize              uint32
	cntUsage            uint32
	th32ProcessID       uint32
	th32DefaultHeapID   uintptr
	th32ModuleID        uint32
	cntThreads          uint32
	th32ParentProcessID uint32
	pcPriClassBase      int32
	dwFlags             uint32
	szExeFile           [260]uint16
}

func (b *windowsBackend) listWindows() []WindowItem {
	enumWinOnce.Do(initEnumWindowsCB)
	enumWinMu.Lock()
	enumWinFG, _, _ = pGetForegroundWindow.Call()
	enumWinResult = nil
	_, _, _ = pEnumWindows.Call(enumWinCB, 0)
	items := append([]WindowItem(nil), enumWinResult...)
	enumWinResult = nil
	enumWinMu.Unlock()
	return items
}

func queryProcName(pid uint32) string {
	if pid == 0 {
		return ""
	}
	h, _, _ := pOpenProcess.Call(0x1000, 0, uintptr(pid)) // PROCESS_QUERY_LIMITED_INFORMATION
	if h == 0 {
		return ""
	}
	defer pCloseHandle.Call(h)
	buf := make([]uint16, 260)
	sz := uint32(len(buf))
	ret, _, _ := pQueryFullProcessImageNameW.Call(h, 0, uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&sz)))
	if ret == 0 {
		return ""
	}
	return strings.TrimSuffix(filepath.Base(syscall.UTF16ToString(buf[:sz])), ".exe")
}

func (b *windowsBackend) WindowAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "list")))
	wins := b.listWindows()
	if act == "list" {
		return map[string]any{"ok": true, "windows": wins}, nil
	}
	if act == "active" {
		for _, w := range wins {
			if w.Active {
				return map[string]any{"ok": true, "window": w}, nil
			}
		}
		return map[string]any{"ok": true, "window": nil}, nil
	}
	var target *WindowItem
	hwndStr := strings.TrimSpace(paramStr(params, "hwnd", ""))
	titleQ := strings.ToLower(strings.TrimSpace(paramStr(params, "title", "")))
	for i := range wins {
		if hwndStr != "" && wins[i].Hwnd == hwndStr {
			target = &wins[i]
			break
		}
		if titleQ != "" && strings.Contains(strings.ToLower(wins[i].Title), titleQ) {
			target = &wins[i]
			break
		}
	}
	if target == nil {
		return nil, fmt.Errorf("未找到匹配窗口 (hwnd=%s, title=%s)", hwndStr, titleQ)
	}
	hwndVal, _ := strconv.ParseUint(target.Hwnd, 10, 64)
	hwnd := uintptr(hwndVal)
	switch act {
	case "focus":
		_, _, _ = pShowWindow.Call(hwnd, 9) // SW_RESTORE
		_, _, _ = pSetForegroundWindow.Call(hwnd)
	case "minimize":
		_, _, _ = pShowWindow.Call(hwnd, 6)
	case "maximize":
		_, _, _ = pShowWindow.Call(hwnd, 3)
	case "restore":
		_, _, _ = pShowWindow.Call(hwnd, 9)
	case "close":
		_, _, _ = pPostMessageW.Call(hwnd, 0x0010, 0, 0) // WM_CLOSE
	case "move":
		nx := paramInt(params, "x", target.X)
		ny := paramInt(params, "y", target.Y)
		nw := paramInt(params, "width", target.Width)
		nh := paramInt(params, "height", target.Height)
		_, _, _ = pMoveWindow.Call(hwnd, uintptr(nx), uintptr(ny), uintptr(nw), uintptr(nh), 1)
	default:
		return nil, fmt.Errorf("未知窗口动作: %s", act)
	}
	return map[string]any{"ok": true, "action": act, "window": target}, nil
}

func (b *windowsBackend) ClipboardAction(params map[string]any) (map[string]any, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "get")))
	if ret, _, _ := pOpenClipboard.Call(0); ret == 0 {
		return nil, fmt.Errorf("无法打开 Windows 剪贴板（可能正被其他进程占用）")
	}
	defer pCloseClipboard.Call()

	if act == "set" {
		text := paramStr(params, "text", "")
		units := utf16.Encode([]rune(text))
		units = append(units, 0)
		byteLen := len(units) * 2
		hMem, _, _ := pGlobalAlloc.Call(0x0002, uintptr(byteLen)) // GMEM_MOVEABLE
		if hMem == 0 {
			return nil, fmt.Errorf("GlobalAlloc 失败")
		}
		ptr, _, _ := pGlobalLock.Call(hMem)
		if ptr == 0 {
			_, _, _ = pGlobalFree.Call(hMem)
			return nil, fmt.Errorf("GlobalLock 失败")
		}
		dst := unsafe.Slice((*uint16)(unsafe.Pointer(ptr)), len(units))
		copy(dst, units)
		_, _, _ = pGlobalUnlock.Call(hMem)
		_, _, _ = pEmptyClipboard.Call()
		if setRet, _, setErr := pSetClipboardData.Call(13, hMem); setRet == 0 {
			_, _, _ = pGlobalFree.Call(hMem)
			return nil, fmt.Errorf("SetClipboardData 失败: %v", setErr)
		}
		return map[string]any{"ok": true, "action": "set", "length": len([]rune(text))}, nil
	}

	hData, _, _ := pGetClipboardData.Call(13) // CF_UNICODETEXT
	if hData == 0 {
		return map[string]any{"ok": true, "text": ""}, nil
	}
	ptr, _, _ := pGlobalLock.Call(hData)
	if ptr == 0 {
		return map[string]any{"ok": true, "text": ""}, nil
	}
	defer pGlobalUnlock.Call(hData)
	// Read null-terminated UTF-16 string safely (cap at 2M uint16).
	var out []uint16
	base := (*uint16)(unsafe.Pointer(ptr))
	for i := 0; i < 2_000_000; i++ {
		c := *(*uint16)(unsafe.Add(unsafe.Pointer(base), uintptr(i)*2))
		if c == 0 {
			break
		}
		out = append(out, c)
	}
	return map[string]any{"ok": true, "text": syscall.UTF16ToString(out)}, nil
}

func (b *windowsBackend) ProcessAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "list")))
	switch act {
	case "list":
		snap, _, _ := pCreateToolhelp32Snapshot.Call(0x00000002, 0) // TH32CS_SNAPPROCESS
		if snap == 0 || snap == ^uintptr(0) {
			return nil, fmt.Errorf("CreateToolhelp32Snapshot 失败")
		}
		defer pCloseHandle.Call(snap)
		var pe processEntry32W
		pe.dwSize = uint32(unsafe.Sizeof(pe))
		q := strings.ToLower(strings.TrimSpace(paramStr(params, "query", "")))
		limit := paramInt(params, "limit", 60)
		if limit < 5 {
			limit = 5
		}
		if limit > 200 {
			limit = 200
		}
		var list []ProcessItem
		ret, _, _ := pProcess32FirstW.Call(snap, uintptr(unsafe.Pointer(&pe)))
		for ret != 0 {
			name := syscall.UTF16ToString(pe.szExeFile[:])
			if q == "" || strings.Contains(strings.ToLower(name), q) {
				list = append(list, ProcessItem{PID: int(pe.th32ProcessID), Name: name})
				if len(list) >= limit {
					break
				}
			}
			ret, _, _ = pProcess32NextW.Call(snap, uintptr(unsafe.Pointer(&pe)))
		}
		return map[string]any{"ok": true, "processes": list}, nil

	case "launch":
		target := strings.TrimSpace(paramStr(params, "target", ""))
		if target == "" {
			return nil, fmt.Errorf("launch 需提供 target")
		}
		cmd := exec.Command(target, paramStrSlice(params, "args")...)
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		go func() { _ = cmd.Wait() }()
		return map[string]any{"ok": true, "launched": target, "pid": cmd.Process.Pid}, nil

	case "kill":
		pid := paramInt(params, "pid", 0)
		name := strings.TrimSpace(paramStr(params, "name", ""))
		if pid > 0 {
			h, _, _ := pOpenProcess.Call(0x0001, 0, uintptr(pid)) // PROCESS_TERMINATE
			if h == 0 {
				return nil, fmt.Errorf("无法打开进程 PID=%d（权限不足或进程已退出）", pid)
			}
			defer pCloseHandle.Call(h)
			if ok, _, err := pTerminateProcess.Call(h, 1); ok == 0 {
				return nil, fmt.Errorf("结束进程失败: %v", err)
			}
			return map[string]any{"ok": true, "killedPid": pid}, nil
		}
		if name != "" {
			if err := exec.Command("taskkill.exe", "/F", "/IM", name).Run(); err != nil {
				return nil, err
			}
			return map[string]any{"ok": true, "killedName": name}, nil
		}
		return nil, fmt.Errorf("kill 需提供 pid 或 name")

	default:
		return nil, fmt.Errorf("未知进程动作: %s", act)
	}
}

func (b *windowsBackend) SystemAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "info")))
	switch act {
	case "info":
		info := collectSystemInfo()
		info["ok"] = true
		info["monitors"] = b.enumMonitors()
		return info, nil
	case "monitors":
		return map[string]any{"ok": true, "monitors": b.enumMonitors()}, nil
	case "volume":
		lvl := paramInt(params, "level", 50)
		if lvl < 0 {
			lvl = 0
		}
		if lvl > 100 {
			lvl = 100
		}
		// Reset volume to 0 then step up in increments of 2 via native VK_VOLUME_DOWN/UP.
		for i := 0; i < 50; i++ {
			sendVK(0xAE, false)
			sendVK(0xAE, true)
		}
		steps := lvl / 2
		for i := 0; i < steps; i++ {
			sendVK(0xAF, false)
			sendVK(0xAF, true)
		}
		return map[string]any{"ok": true, "action": "volume", "level": lvl}, nil
	case "mute":
		sendVK(0xAD, false)
		sendVK(0xAD, true)
		return map[string]any{"ok": true, "action": "mute"}, nil
	case "lock":
		_, _, _ = pLockWorkStation.Call()
		return map[string]any{"ok": true, "action": "lock"}, nil
	case "sleep":
		_, _, _ = pSetSuspendState.Call(0, 1, 0)
		return map[string]any{"ok": true, "action": "sleep"}, nil
	case "restart":
		_ = exec.Command("shutdown.exe", "/r", "/t", "3").Start()
		return map[string]any{"ok": true, "action": "restart"}, nil
	case "shutdown":
		_ = exec.Command("shutdown.exe", "/s", "/t", "3").Start()
		return map[string]any{"ok": true, "action": "shutdown"}, nil
	default:
		return nil, fmt.Errorf("未知系统动作: %s", act)
	}
}

// winUiaWorker keeps a warm STA PowerShell process alive for fast UI Automation queries.
type winUiaWorker struct {
	mu     sync.Mutex
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout *bufio.Reader
	done   chan struct{}
}

const uiaLoopScript = `
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$root = [System.Windows.Automation.AutomationElement]::RootElement
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  try {
    $req = $line | ConvertFrom-Json
    $maxN = if ($req.maxNodes) { [Math]::Min(200, [Math]::Max(10, [int]$req.maxNodes)) } else { 100 }
    $q = if ($req.query) { [string]$req.query } else { '' }
    $target = [System.Windows.Automation.AutomationElement]::FocusedElement
    if ($null -eq $target) { $target = $root }
    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $top = $target
    while ($null -ne $top) {
      $p = $walker.GetParent($top)
      if ($null -eq $p -or $p -eq $root) { break }
      $top = $p
    }
    if ($null -eq $top) { $top = $root }
    $nodes = New-Object System.Collections.Generic.List[object]
    $queue = New-Object System.Collections.Generic.Queue[object]
    $queue.Enqueue(@{el=$top; d=0})
    while ($queue.Count -gt 0 -and $nodes.Count -lt $maxN) {
      $cur = $queue.Dequeue()
      $el = $cur.el; $d = $cur.d
      try {
        $nm = $el.Current.Name
        $ct = $el.Current.ControlType.ProgrammaticName -replace '^ControlType\.',''
        $rc = $el.Current.BoundingRectangle
        if ($rc.Width -gt 0 -and $rc.Height -gt 0 -and -not [double]::IsInfinity($rc.X)) {
          $hit = (-not $q) -or ($nm -and $nm.IndexOf($q, [StringComparison]::OrdinalIgnoreCase) -ge 0) -or ($ct.IndexOf($q, [StringComparison]::OrdinalIgnoreCase) -ge 0)
          if ($hit) {
            $nodes.Add([ordered]@{ id=("uia-"+$nodes.Count); name=([string]$nm); controlType=$ct; x=[int]$rc.X; y=[int]$rc.Y; width=[int]$rc.Width; height=[int]$rc.Height; cx=[int]($rc.X+$rc.Width/2); cy=[int]($rc.Y+$rc.Height/2) })
          }
        }
        if ($d -lt 5) {
          $ch = $walker.GetFirstChild($el)
          $cnt = 0
          while ($null -ne $ch -and $cnt -lt 35) {
            $queue.Enqueue(@{el=$ch; d=($d+1)})
            $ch = $walker.GetNextSibling($ch); $cnt++
          }
        }
      } catch {}
    }
    @{ ok=$true; nodes=$nodes } | ConvertTo-Json -Depth 4 -Compress | Write-Output
  } catch {
    @{ ok=$false; error=$_.Exception.Message } | ConvertTo-Json -Compress | Write-Output
  }
}
`

func (w *winUiaWorker) ensureStarted() error {
	if w.cmd != nil && w.done != nil {
		select {
		case <-w.done:
			w.closeLocked()
		default:
			return nil
		}
	}
	u16 := utf16.Encode([]rune(uiaLoopScript))
	raw := make([]byte, len(u16)*2)
	for i, c := range u16 {
		raw[i*2] = byte(c)
		raw[i*2+1] = byte(c >> 8)
	}
	enc := base64.StdEncoding.EncodeToString(raw)
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc)
	sin, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	sout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	done := make(chan struct{})
	go func(c *exec.Cmd, ch chan struct{}) {
		_ = c.Wait()
		close(ch)
	}(cmd, done)
	w.cmd = cmd
	w.stdin = sin
	w.stdout = bufio.NewReader(sout)
	w.done = done
	return nil
}

func (w *winUiaWorker) query(params map[string]any) (map[string]any, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if err := w.ensureStarted(); err != nil {
		return nil, err
	}
	reqBytes, _ := json.Marshal(params)
	if _, err := w.stdin.Write(append(reqBytes, '\n')); err != nil {
		w.closeLocked()
		return nil, err
	}
	type readRes struct {
		line string
		err  error
	}
	ch := make(chan readRes, 1)
	rd := w.stdout
	go func() {
		l, e := rd.ReadString('\n')
		ch <- readRes{line: l, err: e}
	}()
	var line string
	select {
	case res := <-ch:
		if res.err != nil {
			w.closeLocked()
			return nil, res.err
		}
		line = res.line
	case <-time.After(12 * time.Second):
		w.closeLocked()
		return nil, fmt.Errorf("Windows UIA 查询超时（12s），已重启工作进程")
	}
	var out map[string]any
	if err := json.Unmarshal([]byte(strings.TrimSpace(line)), &out); err != nil {
		return nil, err
	}
	if ok, _ := out["ok"].(bool); !ok {
		return nil, fmt.Errorf("%v", out["error"])
	}
	return out, nil
}

func (w *winUiaWorker) closeLocked() {
	if w.stdin != nil {
		_ = w.stdin.Close()
		w.stdin = nil
	}
	if w.cmd != nil && w.cmd.Process != nil {
		_ = w.cmd.Process.Kill()
	}
	w.cmd = nil
	w.stdout = nil
	w.done = nil
}

func (w *winUiaWorker) close() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.closeLocked()
}

func (b *windowsBackend) UITree(params map[string]any) (map[string]any, error) {
	b.uiaOnce.Do(func() { b.uia = &winUiaWorker{} })
	res, err := b.uia.query(params)
	if err != nil {
		return nil, err
	}
	if clickName := strings.TrimSpace(paramStr(params, "clickName", "")); clickName != "" {
		cl := strings.ToLower(clickName)
		if nodes, ok := res["nodes"].([]any); ok {
			for _, raw := range nodes {
				if n, ok := raw.(map[string]any); ok {
					if strings.Contains(strings.ToLower(paramStr(n, "name", "")), cl) {
						cx, cy := paramInt(n, "cx", 0), paramInt(n, "cy", 0)
						_, _ = b.MouseAction(map[string]any{"action": "click", "x": cx, "y": cy})
						res["clicked"] = n
						break
					}
				}
			}
		}
	}
	return res, nil
}
