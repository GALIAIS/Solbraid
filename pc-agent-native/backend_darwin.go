//go:build darwin

package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Embedded zero-dependency Swift CoreGraphics + Accessibility persistent worker compiled on target Mac via swiftc -O.
const macSwiftHelperSource = `
import Foundation
import CoreGraphics
import AppKit
import ApplicationServices

func postMouse(_ type: CGEventType, _ x: Double, _ y: Double, _ btn: CGMouseButton, _ clicks: Int64 = 1) {
    let pt = CGPoint(x: x, y: y)
    if let ev = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: pt, mouseButton: btn) {
        ev.setIntegerValueField(.mouseEventClickState, value: clicks)
        ev.post(tap: .cghidEventTap)
    }
}

func numVal(_ req: [String: Any], _ key: String, _ def: Double) -> Double {
    if let d = req[key] as? Double { return d }
    if let i = req[key] as? Int { return Double(i) }
    return def
}

func resolveCoords(_ req: [String: Any], _ rawX: Double, _ rawY: Double) -> (Double, Double) {
    let normalized = (req["normalized"] as? Bool) ?? false
    if !normalized { return (rawX, rawY) }
    let screens = NSScreen.screens
    if screens.isEmpty { return (rawX, rawY) }
    let primaryH = screens[0].frame.height
    let reqIdx = Int(numVal(req, "monitor", 0))
    let idx = (reqIdx >= 0 && reqIdx < screens.count) ? reqIdx : 0
    let f = screens[idx].frame
    let cgOriginX = Double(f.origin.x)
    let cgOriginY = Double(primaryH - (f.origin.y + f.height))
    let nx = max(0.0, min(1000.0, rawX))
    let ny = max(0.0, min(1000.0, rawY))
    return (cgOriginX + nx * Double(f.width) / 1000.0, cgOriginY + ny * Double(f.height) / 1000.0)
}

func handle(_ req: [String: Any]) -> [String: Any] {
    let op = (req["op"] as? String) ?? ""
    if (op == "mouse" || op == "type") && !AXIsProcessTrusted() {
        return ["ok": false, "error": "macOS 辅助功能未授权，请在 系统设置 → 隐私与安全性 → 辅助功能 中授权 solbraid-desktop"]
    }
    if op == "mouse" {
        let act = ((req["action"] as? String) ?? "click").lowercased()
        let rawX = numVal(req, "x", 0)
        let rawY = numVal(req, "y", 0)
        let (x, y) = resolveCoords(req, rawX, rawY)
        if act == "move" { postMouse(.mouseMoved, x, y, .left) }
        else if act == "click" { postMouse(.leftMouseDown, x, y, .left); postMouse(.leftMouseUp, x, y, .left) }
        else if act == "double_click" {
            postMouse(.leftMouseDown, x, y, .left, 1); postMouse(.leftMouseUp, x, y, .left, 1)
            postMouse(.leftMouseDown, x, y, .left, 2); postMouse(.leftMouseUp, x, y, .left, 2)
        } else if act == "right_click" { postMouse(.rightMouseDown, x, y, .right); postMouse(.rightMouseUp, x, y, .right) }
        else if act == "middle_click" { postMouse(.otherMouseDown, x, y, .center); postMouse(.otherMouseUp, x, y, .center) }
        else if act == "down" {
            let btn = ((req["button"] as? String) ?? "left").lowercased()
            if btn == "right" { postMouse(.rightMouseDown, x, y, .right) }
            else if btn == "middle" { postMouse(.otherMouseDown, x, y, .center) }
            else { postMouse(.leftMouseDown, x, y, .left) }
        } else if act == "up" {
            let btn = ((req["button"] as? String) ?? "left").lowercased()
            if btn == "right" { postMouse(.rightMouseUp, x, y, .right) }
            else if btn == "middle" { postMouse(.otherMouseUp, x, y, .center) }
            else { postMouse(.leftMouseUp, x, y, .left) }
        } else if act == "drag" {
            let (tx, ty) = resolveCoords(req, numVal(req, "toX", rawX), numVal(req, "toY", rawY))
            postMouse(.leftMouseDown, x, y, .left)
            for i in 1...10 {
                let ix = x + (tx - x) * Double(i) / 10.0; let iy = y + (ty - y) * Double(i) / 10.0
                postMouse(.leftMouseDragged, ix, iy, .left); usleep(10000)
            }
            postMouse(.leftMouseUp, tx, ty, .left)
        } else if act == "scroll" {
            let dy = Int32(numVal(req, "deltaY", -120.0) / 40.0)
            if let ev = CGEvent(scrollWheelEvent2Source: nil, units: .line, wheelCount: 1, wheel1: dy, wheel2: 0, wheel3: 0) {
                ev.post(tap: .cghidEventTap)
            }
        } else {
            return ["ok": false, "error": "未知鼠标动作: \(act)"]
        }
        return ["ok": true, "action": act, "x": Int(x), "y": Int(y)]
    }
    if op == "type" {
        let text = (req["text"] as? String) ?? ""
        let chars = Array(text.utf16)
        for u in chars {
            var ch = u
            if let d = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true) {
                d.keyboardSetUnicodeString(stringLength: 1, unicodeString: &ch); d.post(tap: .cghidEventTap)
            }
            if let up = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: false) {
                up.keyboardSetUnicodeString(stringLength: 1, unicodeString: &ch); up.post(tap: .cghidEventTap)
            }
        }
        return ["ok": true, "length": text.count]
    }
    return ["ok": false, "error": "unknown op"]
}

while let line = readLine() {
    let res: [String: Any]
    if let data = line.data(using: .utf8),
       let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
        res = handle(obj)
    } else {
        res = ["ok": false, "error": "invalid json"]
    }
    if let out = try? JSONSerialization.data(withJSONObject: res), let s = String(data: out, encoding: .utf8) {
        print(s); fflush(stdout)
    } else {
        print("{\"ok\":false,\"error\":\"encode failed\"}"); fflush(stdout)
    }
}
`

type darwinBackend struct {
	mu      sync.Mutex
	cmd     *exec.Cmd
	stdin   io.WriteCloser
	stdout  *bufio.Reader
	done    chan struct{}
	noSwift bool
}

func newOSBackend() OSBackend { return &darwinBackend{} }

func (b *darwinBackend) Name() string { return "darwin-native-cg" }

func (b *darwinBackend) closeHelperLocked() {
	if b.stdin != nil {
		_ = b.stdin.Close()
		b.stdin = nil
	}
	if b.cmd != nil && b.cmd.Process != nil {
		_ = b.cmd.Process.Kill()
	}
	b.cmd = nil
	b.stdout = nil
	b.done = nil
}

func (b *darwinBackend) Close() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.closeHelperLocked()
}

func (b *darwinBackend) callSwiftHelper(payload map[string]any) (map[string]any, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.noSwift {
		return nil, fmt.Errorf("swiftc unavailable")
	}
	if b.cmd != nil && b.done != nil {
		select {
		case <-b.done:
			b.closeHelperLocked()
		default:
		}
	}
	if b.cmd == nil {
		home, _ := os.UserHomeDir()
		binDir := filepath.Join(home, ".solbraid-pc-agent", "bin")
		_ = os.MkdirAll(binDir, 0o700)
		sum := sha256.Sum256([]byte(macSwiftHelperSource))
		tag := hex.EncodeToString(sum[:6])
		binPath := filepath.Join(binDir, "solbraid-mac-helper-"+tag)
		if _, err := os.Stat(binPath); err != nil {
			swiftc, errLook := exec.LookPath("swiftc")
			if errLook != nil {
				b.noSwift = true
				return nil, fmt.Errorf("swiftc unavailable")
			}
			srcPath := filepath.Join(binDir, "helper.swift")
			_ = os.WriteFile(srcPath, []byte(macSwiftHelperSource), 0o600)
			if out, errC := exec.Command(swiftc, "-O", srcPath, "-o", binPath).CombinedOutput(); errC != nil {
				b.noSwift = true
				return nil, fmt.Errorf("swiftc build failed: %s", string(out))
			}
		}
		cmd := exec.Command(binPath)
		sin, err := cmd.StdinPipe()
		if err != nil {
			return nil, err
		}
		sout, err := cmd.StdoutPipe()
		if err != nil {
			return nil, err
		}
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		done := make(chan struct{})
		go func(c *exec.Cmd, ch chan struct{}) {
			_ = c.Wait()
			close(ch)
		}(cmd, done)
		b.cmd, b.stdin, b.stdout, b.done = cmd, sin, bufio.NewReader(sout), done
	}
	raw, _ := json.Marshal(payload)
	if _, err := b.stdin.Write(append(raw, '\n')); err != nil {
		b.closeHelperLocked()
		return nil, err
	}
	type readRes struct {
		line string
		err  error
	}
	ch := make(chan readRes, 1)
	rd := b.stdout
	go func() {
		l, e := rd.ReadString('\n')
		ch <- readRes{line: l, err: e}
	}()
	var line string
	select {
	case res := <-ch:
		if res.err != nil {
			b.closeHelperLocked()
			return nil, res.err
		}
		line = res.line
	case <-time.After(10 * time.Second):
		b.closeHelperLocked()
		return nil, fmt.Errorf("macOS Swift 辅助进程响应超时（10s）")
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

func (b *darwinBackend) CaptureScreen(params map[string]any) (*ScreenCaptureResult, error) {
	tmp := filepath.Join(os.TempDir(), "solbraid-mac-"+randomHex(6)+".jpg")
	defer os.Remove(tmp)
	args := []string{"-x", "-t", "jpg"}
	if m := paramInt(params, "monitor", -2); m >= 0 {
		args = append(args, "-D", strconv.Itoa(m+1))
	}
	if reg, ok := params["region"].(map[string]any); ok && paramInt(reg, "width", 0) > 0 {
		args = append(args, "-R", fmt.Sprintf("%d,%d,%d,%d", paramInt(reg, "x", 0), paramInt(reg, "y", 0), paramInt(reg, "width", 0), paramInt(reg, "height", 0)))
	}
	args = append(args, tmp)
	if out, err := exec.Command("screencapture", args...).CombinedOutput(); err != nil {
		return nil, fmt.Errorf("screencapture 失败（请在 系统设置 → 隐私与安全性 → 录屏 授权）: %s", string(out))
	}
	raw, err := os.ReadFile(tmp)
	if err != nil {
		return nil, err
	}
	img, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	sb := img.Bounds()
	rgba := image.NewRGBA(image.Rect(0, 0, sb.Dx(), sb.Dy()))
	for y := 0; y < sb.Dy(); y++ {
		for x := 0; x < sb.Dx(); x++ {
			rgba.Set(x, y, img.At(sb.Min.X+x, sb.Min.Y+y))
		}
	}
	scaled := resizeRGBA(rgba, paramInt(params, "maxWidth", 1280))
	jpg, err := encodeJPEG(scaled, paramInt(params, "quality", 72))
	if err != nil {
		return nil, err
	}
	return &ScreenCaptureResult{
		JPEG: jpg, Width: scaled.Bounds().Dx(), Height: scaled.Bounds().Dy(),
		ScreenWidth: sb.Dx(), ScreenHeight: sb.Dy(),
	}, nil
}

func (b *darwinBackend) MouseAction(params map[string]any) (map[string]any, error) {
	req := map[string]any{"op": "mouse"}
	for k, v := range params {
		req[k] = v
	}
	res, err := b.callSwiftHelper(req)
	if err != nil {
		return nil, fmt.Errorf("macOS 鼠标操控失败（需要 Xcode Command Line Tools 并授权辅助功能权限）: %w", err)
	}
	return res, nil
}

func runOsascript(script string) error {
	if out, err := exec.Command("osascript", "-e", script).CombinedOutput(); err != nil {
		msg := strings.TrimSpace(string(out))
		if msg == "" {
			msg = err.Error()
		}
		return fmt.Errorf("osascript 失败: %s", msg)
	}
	return nil
}

func (b *darwinBackend) KeyboardAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "type")))
	if act == "type" {
		if res, err := b.callSwiftHelper(map[string]any{"op": "type", "text": paramStr(params, "text", "")}); err == nil {
			if paramBool(params, "pressEnter", false) {
				if err := runOsascript(`tell application "System Events" to key code 36`); err != nil {
					return nil, err
				}
			}
			return res, nil
		}
		// Fallback via pbcopy + Cmd+V
		c := exec.Command("pbcopy")
		c.Stdin = strings.NewReader(paramStr(params, "text", ""))
		if err := c.Run(); err != nil {
			return nil, fmt.Errorf("pbcopy 失败: %w", err)
		}
		if err := runOsascript(`tell application "System Events" to keystroke "v" using command down`); err != nil {
			return nil, err
		}
		if paramBool(params, "pressEnter", false) {
			if err := runOsascript(`tell application "System Events" to key code 36`); err != nil {
				return nil, err
			}
		}
		return map[string]any{"ok": true, "action": "type"}, nil
	}
	if act == "key" {
		codeMap := map[string]int{"enter": 36, "return": 36, "tab": 48, "space": 49, "backspace": 51, "escape": 53, "esc": 53, "left": 123, "right": 124, "down": 125, "up": 126}
		k := strings.ToLower(paramStr(params, "key", "enter"))
		if code, ok := codeMap[k]; ok {
			if err := runOsascript(fmt.Sprintf(`tell application "System Events" to key code %d`, code)); err != nil {
				return nil, err
			}
		} else {
			if err := runOsascript(fmt.Sprintf(`tell application "System Events" to keystroke %q`, k)); err != nil {
				return nil, err
			}
		}
		return map[string]any{"ok": true, "action": "key"}, nil
	}
	if act == "hotkey" {
		keys := paramStrSlice(params, "keys")
		var mods []string
		mainKey := ""
		for _, raw := range keys {
			k := strings.ToLower(raw)
			switch k {
			case "cmd", "command", "meta", "win":
				mods = append(mods, "command down")
			case "ctrl", "control":
				mods = append(mods, "control down")
			case "alt", "option":
				mods = append(mods, "option down")
			case "shift":
				mods = append(mods, "shift down")
			default:
				mainKey = k
			}
		}
		using := ""
		if len(mods) > 0 {
			using = " using {" + strings.Join(mods, ", ") + "}"
		}
		if err := runOsascript(fmt.Sprintf(`tell application "System Events" to keystroke %q%s`, mainKey, using)); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "action": "hotkey", "keys": keys}, nil
	}
	return nil, fmt.Errorf("未知键盘动作: %s", act)
}

func (b *darwinBackend) UITree(params map[string]any) (map[string]any, error) {
	maxN := paramInt(params, "maxNodes", 80)
	script := fmt.Sprintf(`tell application "System Events"
  set frontProc to first application process whose frontmost is true
  set outList to {}
  try
    set win to front window of frontProc
    repeat with e in (entire contents of win)
      if (count of outList) >= %d then exit repeat
      try
        set pos to position of e
        set sz to size of e
        set end of outList to (role of e as string) & "||" & (name of e as string) & "||" & (item 1 of pos) & "," & (item 2 of pos) & "," & (item 1 of sz) & "," & (item 2 of sz)
      end try
    end repeat
  end try
  set AppleScript's text item delimiters to linefeed
  return outList as text
end tell`, maxN)
	out, err := exec.Command("osascript", "-e", script).Output()
	if err != nil {
		return nil, fmt.Errorf("读取 macOS 辅助功能树失败: %w", err)
	}
	q := strings.ToLower(paramStr(params, "query", ""))
	var nodes []UINode
	for i, part := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		segs := strings.Split(part, "||")
		if len(segs) < 3 {
			continue
		}
		nums := strings.Split(segs[2], ",")
		if len(nums) < 4 {
			continue
		}
		x, _ := strconv.Atoi(strings.TrimSpace(nums[0]))
		y, _ := strconv.Atoi(strings.TrimSpace(nums[1]))
		w, _ := strconv.Atoi(strings.TrimSpace(nums[2]))
		h, _ := strconv.Atoi(strings.TrimSpace(nums[3]))
		if q != "" && !strings.Contains(strings.ToLower(segs[1]), q) && !strings.Contains(strings.ToLower(segs[0]), q) {
			continue
		}
		nodes = append(nodes, UINode{ID: fmt.Sprintf("ax-%d", i), ControlType: segs[0], Name: segs[1], X: x, Y: y, Width: w, Height: h, CX: x + w/2, CY: y + h/2})
	}
	res := map[string]any{"ok": true, "nodes": nodes}
	if clickName := strings.TrimSpace(paramStr(params, "clickName", "")); clickName != "" {
		cl := strings.ToLower(clickName)
		for _, n := range nodes {
			if strings.Contains(strings.ToLower(n.Name), cl) {
				if _, err := b.MouseAction(map[string]any{"action": "click", "x": n.CX, "y": n.CY}); err != nil {
					return nil, err
				}
				res["clicked"] = n
				break
			}
		}
	}
	return res, nil
}

func (b *darwinBackend) WindowAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "list")))
	if act == "list" || act == "active" {
		out, err := exec.Command("osascript", "-e", `tell application "System Events"
  set rows to {}
  repeat with p in (every application process whose background only is false)
    repeat with w in (windows of p)
      try
        set pos to position of w
        set sz to size of w
        set end of rows to (name of p) & "||" & (name of w) & "||" & (frontmost of p) & "||" & (item 1 of pos) & "," & (item 2 of pos) & "," & (item 1 of sz) & "," & (item 2 of sz)
      end try
    end repeat
  end repeat
  set AppleScript's text item delimiters to linefeed
  return rows as text
end tell`).Output()
		if err != nil {
			return nil, err
		}
		var wins []WindowItem
		for _, r := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			r = strings.TrimSpace(r)
			if r == "" {
				continue
			}
			p := strings.Split(r, "||")
			if len(p) < 4 {
				continue
			}
			nums := strings.Split(p[3], ",")
			if len(nums) < 4 {
				continue
			}
			x, _ := strconv.Atoi(strings.TrimSpace(nums[0]))
			y, _ := strconv.Atoi(strings.TrimSpace(nums[1]))
			w, _ := strconv.Atoi(strings.TrimSpace(nums[2]))
			h, _ := strconv.Atoi(strings.TrimSpace(nums[3]))
			item := WindowItem{Process: p[0], Title: p[1], Active: p[2] == "true", X: x, Y: y, Width: w, Height: h}
			if act == "list" || item.Active {
				wins = append(wins, item)
			}
		}
		return map[string]any{"ok": true, "windows": wins}, nil
	}
	title := paramStr(params, "title", "")
	if act == "focus" && title != "" {
		if err := runOsascript(fmt.Sprintf(`tell application %q to activate`, title)); err != nil {
			return nil, err
		}
	} else if act == "close" && title != "" {
		if err := runOsascript(fmt.Sprintf(`tell application "System Events" to click button 1 of front window of (first process whose name contains %q)`, title)); err != nil {
			return nil, err
		}
	}
	return map[string]any{"ok": true, "action": act, "title": title}, nil
}

func (b *darwinBackend) ProcessAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "list")))
	switch act {
	case "list":
		out, err := exec.Command("ps", "-axo", "pid,pcpu,pmem,comm").Output()
		if err != nil {
			return nil, err
		}
		q := strings.ToLower(paramStr(params, "query", ""))
		limit := paramInt(params, "limit", 50)
		var list []ProcessItem
		for i, l := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			if i == 0 {
				continue
			}
			f := strings.Fields(l)
			if len(f) < 4 {
				continue
			}
			pid, _ := strconv.Atoi(f[0])
			cpu, _ := strconv.ParseFloat(f[1], 64)
			mem, _ := strconv.ParseFloat(f[2], 64)
			name := filepath.Base(strings.Join(f[3:], " "))
			if q != "" && !strings.Contains(strings.ToLower(name), q) {
				continue
			}
			list = append(list, ProcessItem{PID: pid, CPU: cpu, MemPct: mem, Name: name})
			if len(list) >= limit {
				break
			}
		}
		return map[string]any{"ok": true, "processes": list}, nil
	case "launch":
		target := paramStr(params, "target", "")
		cmd := exec.Command("open", "-a", target)
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		go func() { _ = cmd.Wait() }()
		return map[string]any{"ok": true, "launched": target}, nil
	case "kill":
		if pid := paramInt(params, "pid", 0); pid > 0 {
			if pid == os.Getpid() {
				return nil, fmt.Errorf("拒绝结束当前代理自身进程")
			}
			return map[string]any{"ok": true, "killed": pid}, exec.Command("kill", "-9", strconv.Itoa(pid)).Run()
		}
		if name := paramStr(params, "name", ""); name != "" {
			return map[string]any{"ok": true, "killed": name}, exec.Command("pkill", "-f", "--", name).Run()
		}
		return nil, fmt.Errorf("kill 需提供 pid 或 name")
	default:
		return nil, fmt.Errorf("未知进程动作: %s", act)
	}
}

func (b *darwinBackend) SystemAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "info")))
	switch act {
	case "info", "monitors":
		m := collectSystemInfo()
		m["ok"] = true
		return m, nil
	case "volume":
		lvl := paramInt(params, "level", 50)
		if lvl < 0 {
			lvl = 0
		}
		if lvl > 100 {
			lvl = 100
		}
		if err := runOsascript(fmt.Sprintf("set volume output volume %d", lvl)); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "level": lvl}, nil
	case "mute":
		muted := paramBool(params, "muted", true)
		if err := runOsascript(fmt.Sprintf("set volume output muted %v", muted)); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "muted": muted}, nil
	case "lock":
		if err := exec.Command("pmset", "displaysleepnow").Run(); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, nil
	case "sleep":
		if err := exec.Command("pmset", "sleepnow").Run(); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, nil
	case "restart":
		if err := runOsascript(`tell application "System Events" to restart`); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, nil
	case "shutdown":
		if err := runOsascript(`tell application "System Events" to shut down`); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, nil
	default:
		return nil, fmt.Errorf("未知系统动作: %s", act)
	}
}

func (b *darwinBackend) ClipboardAction(params map[string]any) (map[string]any, error) {
	if strings.ToLower(paramStr(params, "action", "get")) == "set" {
		txt := paramStr(params, "text", "")
		c := exec.Command("pbcopy")
		c.Stdin = strings.NewReader(txt)
		if err := c.Run(); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "action": "set", "length": len([]rune(txt))}, nil
	}
	out, err := exec.Command("pbpaste").Output()
	if err != nil {
		return nil, err
	}
	return map[string]any{"ok": true, "text": string(out)}, nil
}
