//go:build linux

package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
)

type linuxBackend struct {
	dimMu sync.Mutex
	lastW int
	lastH int
}

var linuxResRe = regexp.MustCompile(`(\d{3,5})x(\d{3,5})`)

func (b *linuxBackend) detectMonitors() []MonitorInfo {
	b.dimMu.Lock()
	w, h := b.lastW, b.lastH
	b.dimMu.Unlock()
	if w > 0 && h > 0 {
		return []MonitorInfo{{Index: 0, Primary: true, X: 0, Y: 0, Width: w, Height: h}}
	}
	// Fallback query via xrandr / xdpyinfo / wlr-randr.
	tryParse := func(name string, args ...string) (int, int) {
		if !hasBin(name) {
			return 0, 0
		}
		out, err := exec.Command(name, args...).Output()
		if err != nil {
			return 0, 0
		}
		for _, line := range strings.Split(string(out), "\n") {
			if strings.Contains(line, "*") || strings.Contains(line, "current") || strings.Contains(line, "dimensions:") {
				if m := linuxResRe.FindStringSubmatch(line); len(m) == 3 {
					pw, _ := strconv.Atoi(m[1])
					ph, _ := strconv.Atoi(m[2])
					if pw >= 320 && ph >= 240 {
						return pw, ph
					}
				}
			}
		}
		return 0, 0
	}
	if os.Getenv("WAYLAND_DISPLAY") != "" {
		w, h = tryParse("wlr-randr")
	}
	if w == 0 {
		w, h = tryParse("xrandr", "--current")
	}
	if w == 0 {
		w, h = tryParse("xdpyinfo")
	}
	if w > 0 && h > 0 {
		b.dimMu.Lock()
		b.lastW, b.lastH = w, h
		b.dimMu.Unlock()
		return []MonitorInfo{{Index: 0, Primary: true, X: 0, Y: 0, Width: w, Height: h}}
	}
	return nil
}

func newOSBackend() OSBackend { return &linuxBackend{} }

func (b *linuxBackend) Name() string {
	if os.Getenv("WAYLAND_DISPLAY") != "" {
		return "linux-native-wayland"
	}
	return "linux-native-x11"
}

func (b *linuxBackend) Close() {}

func hasBin(name string) bool {
	_, err := exec.LookPath(name)
	return err == nil
}

func (b *linuxBackend) CaptureScreen(params map[string]any) (*ScreenCaptureResult, error) {
	tmp := filepath.Join(os.TempDir(), "solbraid-lin-"+randomHex(6)+".png")
	defer os.Remove(tmp)

	isWayland := os.Getenv("WAYLAND_DISPLAY") != ""
	var cmd *exec.Cmd
	switch {
	case isWayland && hasBin("grim"):
		var args []string
		if reg, ok := params["region"].(map[string]any); ok && paramInt(reg, "width", 0) > 0 {
			args = append(args, "-g", fmt.Sprintf("%d,%d %dx%d", paramInt(reg, "x", 0), paramInt(reg, "y", 0), paramInt(reg, "width", 0), paramInt(reg, "height", 0)))
		}
		args = append(args, tmp)
		cmd = exec.Command("grim", args...)
	case hasBin("scrot"):
		cmd = exec.Command("scrot", "-o", tmp)
	case hasBin("import"):
		cmd = exec.Command("import", "-window", "root", tmp)
	default:
		return nil, fmt.Errorf("未检测到截屏工具，请安装 grim（Wayland）或 scrot / imagemagick（X11）")
	}
	if out, err := cmd.CombinedOutput(); err != nil {
		return nil, fmt.Errorf("Linux 截屏失败: %s", string(out))
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
	b.dimMu.Lock()
	b.lastW, b.lastH = sb.Dx(), sb.Dy()
	b.dimMu.Unlock()
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

func (b *linuxBackend) UITree(params map[string]any) (map[string]any, error) {
	maxN := paramInt(params, "maxNodes", 100)
	py := fmt.Sprintf(`import json
try:
 import pyatspi
except Exception as e:
 print(json.dumps({"ok":False,"error":"需要安装 python3-pyatspi: "+str(e)})); raise SystemExit(0)
desktop = pyatspi.Registry.getDesktop(0); nodes = []
def walk(a, d):
 if d > 5 or len(nodes) >= %d: return
 try:
  nm = a.name or ""; rl = a.getRoleName() or ""
  if nm:
   e = a.queryComponent().getExtents(pyatspi.DESKTOP_COORDS)
   if e.width > 0 and e.height > 0:
    nodes.append({"id":f"at-{len(nodes)}","name":nm,"controlType":rl,"x":e.x,"y":e.y,"width":e.width,"height":e.height,"cx":e.x+e.width//2,"cy":e.y+e.height//2})
 except Exception: pass
 try:
  for i in range(min(a.childCount, 40)): walk(a.getChildAtIndex(i), d+1)
 except Exception: pass
for app in desktop: walk(app, 0)
print(json.dumps({"ok":True,"nodes":nodes}))`, maxN)
	out, err := exec.Command("python3", "-c", py).Output()
	if err != nil {
		return nil, err
	}
	var parsed map[string]any
	if err := json.Unmarshal(bytes.TrimSpace(out), &parsed); err != nil {
		return nil, err
	}
	if ok, _ := parsed["ok"].(bool); !ok {
		return nil, fmt.Errorf("%v", parsed["error"])
	}
	q := strings.ToLower(strings.TrimSpace(paramStr(params, "query", "")))
	clickName := strings.TrimSpace(paramStr(params, "clickName", ""))
	if rawNodes, ok := parsed["nodes"].([]any); ok {
		if q != "" {
			filtered := make([]any, 0, len(rawNodes))
			for _, item := range rawNodes {
				if n, ok := item.(map[string]any); ok {
					nm := strings.ToLower(paramStr(n, "name", ""))
					ct := strings.ToLower(paramStr(n, "controlType", ""))
					if strings.Contains(nm, q) || strings.Contains(ct, q) {
						filtered = append(filtered, n)
					}
				}
			}
			rawNodes = filtered
			parsed["nodes"] = rawNodes
		}
		if clickName != "" {
			cl := strings.ToLower(clickName)
			for _, item := range rawNodes {
				if n, ok := item.(map[string]any); ok {
					if strings.Contains(strings.ToLower(paramStr(n, "name", "")), cl) {
						cx, cy := paramInt(n, "cx", 0), paramInt(n, "cy", 0)
						if _, err := b.MouseAction(map[string]any{"action": "click", "x": cx, "y": cy}); err != nil {
							return nil, err
						}
						parsed["clicked"] = n
						break
					}
				}
			}
		}
	}
	return parsed, nil
}

func detectActiveX11WindowID() string {
	if !hasBin("xprop") {
		return ""
	}
	out, err := exec.Command("xprop", "-root", "_NET_ACTIVE_WINDOW").Output()
	if err != nil {
		return ""
	}
	parts := strings.Fields(string(out))
	for i := len(parts) - 1; i >= 0; i-- {
		tok := strings.TrimRight(parts[i], ",")
		if strings.HasPrefix(tok, "0x") {
			if v, err := strconv.ParseUint(strings.TrimPrefix(tok, "0x"), 16, 64); err == nil && v > 0 {
				return fmt.Sprintf("0x%x", v)
			}
		}
	}
	return ""
}

func (b *linuxBackend) WindowAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "list")))
	if !hasBin("wmctrl") {
		return nil, fmt.Errorf("Linux 窗口管理需要安装 wmctrl（sudo apt install wmctrl）")
	}
	if act == "list" || act == "active" {
		out, err := exec.Command("wmctrl", "-lGp").Output()
		if err != nil {
			return nil, err
		}
		activeHex := detectActiveX11WindowID()
		var wins []WindowItem
		var activeWin *WindowItem
		for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			f := strings.Fields(line)
			if len(f) < 9 {
				continue
			}
			pid, _ := strconv.Atoi(f[2])
			x, _ := strconv.Atoi(f[3])
			y, _ := strconv.Atoi(f[4])
			w, _ := strconv.Atoi(f[5])
			h, _ := strconv.Atoi(f[6])
			isAct := false
			if activeHex != "" {
				if v, err := strconv.ParseUint(strings.TrimPrefix(strings.ToLower(f[0]), "0x"), 16, 64); err == nil {
					isAct = fmt.Sprintf("0x%x", v) == activeHex
				}
			}
			item := WindowItem{Hwnd: f[0], PID: pid, X: x, Y: y, Width: w, Height: h, Active: isAct, Title: strings.Join(f[8:], " ")}
			wins = append(wins, item)
			if isAct && activeWin == nil {
				cp := item
				activeWin = &cp
			}
		}
		if act == "active" {
			return map[string]any{"ok": true, "window": activeWin}, nil
		}
		return map[string]any{"ok": true, "windows": wins}, nil
	}
	target := paramStr(params, "hwnd", "")
	byID := target != ""
	if target == "" {
		target = paramStr(params, "title", "")
	}
	if target == "" {
		return nil, fmt.Errorf("需提供 hwnd 或 title")
	}
	prefix := func(args ...string) []string {
		if byID {
			return append([]string{"-i"}, args...)
		}
		return args
	}
	switch act {
	case "focus":
		return map[string]any{"ok": true, "action": act}, exec.Command("wmctrl", prefix("-a", target)...).Run()
	case "close":
		return map[string]any{"ok": true, "action": act}, exec.Command("wmctrl", prefix("-c", target)...).Run()
	case "maximize":
		return map[string]any{"ok": true, "action": act}, exec.Command("wmctrl", prefix("-r", target, "-b", "add,maximized_vert,maximized_horz")...).Run()
	case "restore":
		return map[string]any{"ok": true, "action": act}, exec.Command("wmctrl", prefix("-r", target, "-b", "remove,maximized_vert,maximized_horz")...).Run()
	default:
		return map[string]any{"ok": true, "action": act}, nil
	}
}

func (b *linuxBackend) MouseAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "click")))
	var mons []MonitorInfo
	if paramBool(params, "normalized", false) {
		mons = b.detectMonitors()
	}
	rawX := paramFloat(params, "x", 0)
	rawY := paramFloat(params, "y", 0)
	x, y := resolveMonitorCoords(params, mons, rawX, rawY)
	if os.Getenv("WAYLAND_DISPLAY") != "" && hasBin("ydotool") {
		if err := exec.Command("ydotool", "mousemove", "-a", "-x", strconv.Itoa(x), "-y", strconv.Itoa(y)).Run(); err != nil {
			return nil, fmt.Errorf("ydotool mousemove 失败: %w", err)
		}
		switch act {
		case "move":
		case "click":
			if err := exec.Command("ydotool", "click", "0xC0").Run(); err != nil {
				return nil, err
			}
		case "right_click":
			if err := exec.Command("ydotool", "click", "0xC1").Run(); err != nil {
				return nil, err
			}
		case "middle_click":
			if err := exec.Command("ydotool", "click", "0xC2").Run(); err != nil {
				return nil, err
			}
		case "double_click":
			if err := exec.Command("ydotool", "click", "0xC0").Run(); err != nil {
				return nil, err
			}
			if err := exec.Command("ydotool", "click", "0xC0").Run(); err != nil {
				return nil, err
			}
		case "down":
			if err := exec.Command("ydotool", "click", "0x40").Run(); err != nil {
				return nil, err
			}
		case "up":
			if err := exec.Command("ydotool", "click", "0x80").Run(); err != nil {
				return nil, err
			}
		case "drag":
			tx, ty := resolveMonitorCoords(params, mons, paramFloat(params, "toX", rawX), paramFloat(params, "toY", rawY))
			if err := exec.Command("ydotool", "click", "0x40").Run(); err != nil {
				return nil, err
			}
			moveErr := exec.Command("ydotool", "mousemove", "-a", "-x", strconv.Itoa(tx), "-y", strconv.Itoa(ty)).Run()
			upErr := exec.Command("ydotool", "click", "0x80").Run()
			if moveErr != nil {
				return nil, moveErr
			}
			if upErr != nil {
				return nil, upErr
			}
		case "scroll":
			dy := paramInt(params, "deltaY", -120)
			step := dy / 40
			if step == 0 {
				if dy >= 0 {
					step = 1
				} else {
					step = -1
				}
			}
			if err := exec.Command("ydotool", "mousemove", "-w", "--", "0", strconv.Itoa(step)).Run(); err != nil {
				return nil, err
			}
		default:
			return nil, fmt.Errorf("未知鼠标动作: %s", act)
		}
		return map[string]any{"ok": true, "backend": "ydotool", "action": act, "x": x, "y": y}, nil
	}
	if !hasBin("xdotool") {
		return nil, fmt.Errorf("请安装 xdotool（X11）或 ydotool（Wayland）")
	}
	xs, ys := strconv.Itoa(x), strconv.Itoa(y)
	switch act {
	case "move":
		return map[string]any{"ok": true, "x": x, "y": y}, exec.Command("xdotool", "mousemove", "--", xs, ys).Run()
	case "click":
		return map[string]any{"ok": true, "x": x, "y": y}, exec.Command("xdotool", "mousemove", "--", xs, ys, "click", "1").Run()
	case "double_click":
		return map[string]any{"ok": true, "x": x, "y": y}, exec.Command("xdotool", "mousemove", "--", xs, ys, "click", "--repeat", "2", "1").Run()
	case "right_click":
		return map[string]any{"ok": true, "x": x, "y": y}, exec.Command("xdotool", "mousemove", "--", xs, ys, "click", "3").Run()
	case "middle_click":
		return map[string]any{"ok": true, "x": x, "y": y}, exec.Command("xdotool", "mousemove", "--", xs, ys, "click", "2").Run()
	case "down":
		btn := "1"
		switch strings.ToLower(paramStr(params, "button", "left")) {
		case "right":
			btn = "3"
		case "middle":
			btn = "2"
		}
		return map[string]any{"ok": true, "x": x, "y": y}, exec.Command("xdotool", "mousemove", "--", xs, ys, "mousedown", btn).Run()
	case "up":
		btn := "1"
		switch strings.ToLower(paramStr(params, "button", "left")) {
		case "right":
			btn = "3"
		case "middle":
			btn = "2"
		}
		return map[string]any{"ok": true, "x": x, "y": y}, exec.Command("xdotool", "mousemove", "--", xs, ys, "mouseup", btn).Run()
	case "drag":
		tx, ty := resolveMonitorCoords(params, mons, paramFloat(params, "toX", rawX), paramFloat(params, "toY", rawY))
		return map[string]any{"ok": true, "x": x, "y": y}, exec.Command("xdotool", "mousemove", "--", xs, ys, "mousedown", "1", "mousemove", "--", strconv.Itoa(tx), strconv.Itoa(ty), "mouseup", "1").Run()
	case "scroll":
		btn := "5"
		if paramInt(params, "deltaY", -120) > 0 {
			btn = "4"
		}
		return map[string]any{"ok": true, "action": "scroll"}, exec.Command("xdotool", "mousemove", "--", xs, ys, "click", "--repeat", "3", btn).Run()
	default:
		return nil, fmt.Errorf("未知鼠标动作: %s", act)
	}
}

func (b *linuxBackend) KeyboardAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "type")))
	if os.Getenv("WAYLAND_DISPLAY") != "" && hasBin("wtype") {
		switch act {
		case "type":
			if err := exec.Command("wtype", "--", paramStr(params, "text", "")).Run(); err != nil {
				return nil, err
			}
			if paramBool(params, "pressEnter", false) {
				if err := exec.Command("wtype", "-k", "Return").Run(); err != nil {
					return nil, err
				}
			}
			return map[string]any{"ok": true, "backend": "wtype", "action": "type"}, nil
		case "key":
			k := paramStr(params, "key", "Return")
			if strings.EqualFold(k, "enter") {
				k = "Return"
			} else if strings.EqualFold(k, "esc") {
				k = "Escape"
			}
			return map[string]any{"ok": true, "backend": "wtype", "action": "key"}, exec.Command("wtype", "-k", k).Run()
		case "hotkey":
			keys := paramStrSlice(params, "keys")
			if len(keys) == 0 {
				return nil, fmt.Errorf("hotkey 需提供 keys 数组")
			}
			var mods []string
			var args []string
			mainKey := ""
			for _, raw := range keys {
				switch strings.ToLower(raw) {
				case "ctrl", "control":
					mods = append(mods, "ctrl")
				case "alt":
					mods = append(mods, "alt")
				case "shift":
					mods = append(mods, "shift")
				case "win", "super", "meta", "logo":
					mods = append(mods, "logo")
				case "enter":
					mainKey = "Return"
				case "esc":
					mainKey = "Escape"
				default:
					mainKey = raw
				}
			}
			for _, m := range mods {
				args = append(args, "-M", m)
			}
			if mainKey != "" {
				args = append(args, "-k", mainKey)
			}
			for i := len(mods) - 1; i >= 0; i-- {
				args = append(args, "-m", mods[i])
			}
			return map[string]any{"ok": true, "backend": "wtype", "action": "hotkey"}, exec.Command("wtype", args...).Run()
		default:
			return nil, fmt.Errorf("未知键盘动作: %s", act)
		}
	}
	if !hasBin("xdotool") {
		return nil, fmt.Errorf("请安装 xdotool（X11）或 wtype（Wayland）")
	}
	switch act {
	case "type":
		if err := exec.Command("xdotool", "type", "--delay", "5", "--", paramStr(params, "text", "")).Run(); err != nil {
			return nil, err
		}
		if paramBool(params, "pressEnter", false) {
			if err := exec.Command("xdotool", "key", "Return").Run(); err != nil {
				return nil, err
			}
		}
		return map[string]any{"ok": true, "action": "type"}, nil
	case "key":
		return map[string]any{"ok": true, "action": "key"}, exec.Command("xdotool", "key", "--", paramStr(params, "key", "Return")).Run()
	case "hotkey":
		keys := paramStrSlice(params, "keys")
		mapped := make([]string, 0, len(keys))
		for _, k := range keys {
			switch strings.ToLower(k) {
			case "ctrl", "control":
				mapped = append(mapped, "ctrl")
			case "win", "super", "meta":
				mapped = append(mapped, "Super_L")
			case "esc":
				mapped = append(mapped, "Escape")
			case "enter":
				mapped = append(mapped, "Return")
			default:
				mapped = append(mapped, k)
			}
		}
		return map[string]any{"ok": true, "action": "hotkey"}, exec.Command("xdotool", "key", "--", strings.Join(mapped, "+")).Run()
	default:
		return nil, fmt.Errorf("未知键盘动作: %s", act)
	}
}

// ProcessAction reads /proc natively in pure Go without forking ps.
func (b *linuxBackend) ProcessAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "list")))
	switch act {
	case "list":
		entries, err := os.ReadDir("/proc")
		if err != nil {
			return nil, err
		}
		q := strings.ToLower(paramStr(params, "query", ""))
		limit := paramInt(params, "limit", 60)
		var list []ProcessItem
		for _, e := range entries {
			if !e.IsDir() {
				continue
			}
			pid, err := strconv.Atoi(e.Name())
			if err != nil {
				continue
			}
			comm, err := os.ReadFile(filepath.Join("/proc", e.Name(), "comm"))
			if err != nil {
				continue
			}
			name := strings.TrimSpace(string(comm))
			if q != "" && !strings.Contains(strings.ToLower(name), q) {
				continue
			}
			list = append(list, ProcessItem{PID: pid, Name: name})
			if len(list) >= limit {
				break
			}
		}
		return map[string]any{"ok": true, "processes": list}, nil
	case "launch":
		target := paramStr(params, "target", "")
		cmd := exec.Command(target, paramStrSlice(params, "args")...)
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		go func() { _ = cmd.Wait() }()
		return map[string]any{"ok": true, "launched": target, "pid": cmd.Process.Pid}, nil
	case "kill":
		if pid := paramInt(params, "pid", 0); pid > 0 {
			if pid == os.Getpid() {
				return nil, fmt.Errorf("拒绝结束当前代理自身进程")
			}
			return map[string]any{"ok": true, "killed": pid}, syscall.Kill(pid, syscall.SIGKILL)
		}
		if name := paramStr(params, "name", ""); name != "" {
			return map[string]any{"ok": true, "killed": name}, exec.Command("pkill", "-f", "--", name).Run()
		}
		return nil, fmt.Errorf("kill 需提供 pid 或 name")
	default:
		return nil, fmt.Errorf("未知进程动作: %s", act)
	}
}

func (b *linuxBackend) SystemAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "info")))
	switch act {
	case "info", "monitors":
		m := collectSystemInfo()
		m["ok"] = true
		return m, nil
	case "volume":
		lvl := paramInt(params, "level", 50)
		if hasBin("pactl") {
			return map[string]any{"ok": true, "level": lvl}, exec.Command("pactl", "set-sink-volume", "@DEFAULT_SINK@", fmt.Sprintf("%d%%", lvl)).Run()
		}
		return map[string]any{"ok": true, "level": lvl}, exec.Command("amixer", "sset", "Master", fmt.Sprintf("%d%%", lvl)).Run()
	case "mute":
		flag := "1"
		if !paramBool(params, "muted", true) {
			flag = "0"
		}
		if hasBin("pactl") {
			return map[string]any{"ok": true}, exec.Command("pactl", "set-sink-mute", "@DEFAULT_SINK@", flag).Run()
		}
		return map[string]any{"ok": true}, exec.Command("amixer", "sset", "Master", "toggle").Run()
	case "lock":
		return map[string]any{"ok": true}, exec.Command("loginctl", "lock-session").Run()
	case "sleep":
		return map[string]any{"ok": true}, exec.Command("systemctl", "suspend").Run()
	case "restart":
		return map[string]any{"ok": true}, exec.Command("systemctl", "reboot").Run()
	case "shutdown":
		return map[string]any{"ok": true}, exec.Command("systemctl", "poweroff").Run()
	default:
		return nil, fmt.Errorf("未知系统动作: %s", act)
	}
}

func (b *linuxBackend) ClipboardAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(paramStr(params, "action", "get"))
	isWayland := os.Getenv("WAYLAND_DISPLAY") != ""
	if act == "set" {
		txt := paramStr(params, "text", "")
		var cmd *exec.Cmd
		if isWayland && hasBin("wl-copy") {
			cmd = exec.Command("wl-copy")
		} else if hasBin("xclip") {
			cmd = exec.Command("xclip", "-selection", "clipboard")
		} else {
			return nil, fmt.Errorf("请安装 wl-clipboard 或 xclip")
		}
		cmd.Stdin = strings.NewReader(txt)
		return map[string]any{"ok": true, "action": "set", "length": len([]rune(txt))}, cmd.Run()
	}
	var cmd *exec.Cmd
	if isWayland && hasBin("wl-paste") {
		cmd = exec.Command("wl-paste", "-n")
	} else if hasBin("xclip") {
		cmd = exec.Command("xclip", "-o", "-selection", "clipboard")
	} else {
		return nil, fmt.Errorf("请安装 wl-clipboard 或 xclip")
	}
	out, err := cmd.Output()
	if err != nil {
		return nil, err
	}
	return map[string]any{"ok": true, "text": string(out)}, nil
}
