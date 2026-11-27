//go:build windows

package main

import (
	"fmt"
	"image"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

var (
	modUser32   = syscall.NewLazyDLL("user32.dll")
	modGdi32    = syscall.NewLazyDLL("gdi32.dll")
	modKernel32 = syscall.NewLazyDLL("kernel32.dll")
	modPowrProf = syscall.NewLazyDLL("powrprof.dll")

	pSetProcessDpiAwarenessContext = modUser32.NewProc("SetProcessDpiAwarenessContext")
	pSetProcessDPIAware            = modUser32.NewProc("SetProcessDPIAware")
	pGetSystemMetrics              = modUser32.NewProc("GetSystemMetrics")
	pEnumDisplayMonitors           = modUser32.NewProc("EnumDisplayMonitors")
	pGetMonitorInfoW               = modUser32.NewProc("GetMonitorInfoW")
	pGetDC                         = modUser32.NewProc("GetDC")
	pReleaseDC                     = modUser32.NewProc("ReleaseDC")
	pSetCursorPos                  = modUser32.NewProc("SetCursorPos")
	pSendInput                     = modUser32.NewProc("SendInput")
	pEnumWindows                   = modUser32.NewProc("EnumWindows")
	pIsWindowVisible               = modUser32.NewProc("IsWindowVisible")
	pGetWindowTextLengthW          = modUser32.NewProc("GetWindowTextLengthW")
	pGetWindowTextW                = modUser32.NewProc("GetWindowTextW")
	pGetWindowRect                 = modUser32.NewProc("GetWindowRect")
	pGetWindowThreadProcessId      = modUser32.NewProc("GetWindowThreadProcessId")
	pGetForegroundWindow           = modUser32.NewProc("GetForegroundWindow")
	pSetForegroundWindow           = modUser32.NewProc("SetForegroundWindow")
	pShowWindow                    = modUser32.NewProc("ShowWindow")
	pMoveWindow                    = modUser32.NewProc("MoveWindow")
	pPostMessageW                  = modUser32.NewProc("PostMessageW")
	pSendMessageW                  = modUser32.NewProc("SendMessageW")
	pLockWorkStation               = modUser32.NewProc("LockWorkStation")
	pOpenClipboard                 = modUser32.NewProc("OpenClipboard")
	pCloseClipboard                = modUser32.NewProc("CloseClipboard")
	pEmptyClipboard                = modUser32.NewProc("EmptyClipboard")
	pGetClipboardData              = modUser32.NewProc("GetClipboardData")
	pSetClipboardData              = modUser32.NewProc("SetClipboardData")

	pCreateCompatibleDC     = modGdi32.NewProc("CreateCompatibleDC")
	pDeleteDC               = modGdi32.NewProc("DeleteDC")
	pCreateCompatibleBitmap = modGdi32.NewProc("CreateCompatibleBitmap")
	pSelectObject           = modGdi32.NewProc("SelectObject")
	pDeleteObject           = modGdi32.NewProc("DeleteObject")
	pBitBlt                 = modGdi32.NewProc("BitBlt")
	pGetDIBits              = modGdi32.NewProc("GetDIBits")

	pGlobalAlloc                = modKernel32.NewProc("GlobalAlloc")
	pGlobalLock                 = modKernel32.NewProc("GlobalLock")
	pGlobalUnlock               = modKernel32.NewProc("GlobalUnlock")
	pGlobalFree                 = modKernel32.NewProc("GlobalFree")
	pCreateToolhelp32Snapshot   = modKernel32.NewProc("CreateToolhelp32Snapshot")
	pProcess32FirstW            = modKernel32.NewProc("Process32FirstW")
	pProcess32NextW             = modKernel32.NewProc("Process32NextW")
	pOpenProcess                = modKernel32.NewProc("OpenProcess")
	pTerminateProcess           = modKernel32.NewProc("TerminateProcess")
	pCloseHandle                = modKernel32.NewProc("CloseHandle")
	pQueryFullProcessImageNameW = modKernel32.NewProc("QueryFullProcessImageNameW")
	pSetSuspendState            = modPowrProf.NewProc("SetSuspendState")
)

type winRect struct {
	Left, Top, Right, Bottom int32
}

type monitorInfoExW struct {
	cbSize    uint32
	rcMonitor winRect
	rcWork    winRect
	dwFlags   uint32
	szDevice  [32]uint16
}

type bitmapInfoHeader struct {
	biSize          uint32
	biWidth         int32
	biHeight        int32
	biPlanes        uint16
	biBitCount      uint16
	biCompression   uint32
	biSizeImage     uint32
	biXPelsPerMeter int32
	biYPelsPerMeter int32
	biClrUsed       uint32
	biClrImportant  uint32
}

type mouseInput struct {
	dx, dy      int32
	mouseData   uint32
	dwFlags     uint32
	time        uint32
	dwExtraInfo uintptr
}

type keybdInput struct {
	wVk         uint16
	wScan       uint16
	dwFlags     uint32
	time        uint32
	dwExtraInfo uintptr
}

// winInput matches Win32 INPUT struct on 64-bit Windows (40 bytes) & 32-bit Windows (28 bytes).
type winInput struct {
	kind uint32
	_    uint32 // padding on 64-bit; matches union alignment
	data [32]byte
}

type windowsBackend struct {
	mu      sync.Mutex
	uiaOnce sync.Once
	uia     *winUiaWorker
}

func newOSBackend() OSBackend {
	// Enable Per-Monitor V2 DPI awareness once for crisp physical pixel coordinates.
	if err := pSetProcessDpiAwarenessContext.Find(); err == nil {
		neg4 := ^uintptr(3) // (DPI_AWARENESS_CONTEXT)-4
		_, _, _ = pSetProcessDpiAwarenessContext.Call(neg4)
	} else {
		_, _, _ = pSetProcessDPIAware.Call()
	}
	return &windowsBackend{}
}

func (b *windowsBackend) Name() string { return "win32-native-gdi" }

func (b *windowsBackend) Close() {
	if b.uia != nil {
		b.uia.close()
	}
}

var (
	enumMonMu     sync.Mutex
	enumMonOnce   sync.Once
	enumMonCB     uintptr
	enumMonResult []MonitorInfo
)

func initEnumMonitorsCB() {
	enumMonCB = syscall.NewCallback(func(hMon uintptr, hdc uintptr, lprc uintptr, lParam uintptr) uintptr {
		var mi monitorInfoExW
		mi.cbSize = uint32(unsafe.Sizeof(mi))
		ret, _, _ := pGetMonitorInfoW.Call(hMon, uintptr(unsafe.Pointer(&mi)))
		if ret != 0 {
			enumMonResult = append(enumMonResult, MonitorInfo{
				Index:   len(enumMonResult),
				Primary: (mi.dwFlags & 1) != 0,
				X:       int(mi.rcMonitor.Left),
				Y:       int(mi.rcMonitor.Top),
				Width:   int(mi.rcMonitor.Right - mi.rcMonitor.Left),
				Height:  int(mi.rcMonitor.Bottom - mi.rcMonitor.Top),
			})
		}
		return 1
	})
}

func (b *windowsBackend) enumMonitors() []MonitorInfo {
	enumMonOnce.Do(initEnumMonitorsCB)
	enumMonMu.Lock()
	enumMonResult = nil
	_, _, _ = pEnumDisplayMonitors.Call(0, 0, enumMonCB, 0)
	mons := append([]MonitorInfo(nil), enumMonResult...)
	enumMonResult = nil
	enumMonMu.Unlock()
	if len(mons) == 0 {
		w, _, _ := pGetSystemMetrics.Call(0) // SM_CXSCREEN
		h, _, _ := pGetSystemMetrics.Call(1) // SM_CYSCREEN
		if w > 0 && h > 0 {
			mons = append(mons, MonitorInfo{Index: 0, Primary: true, Width: int(w), Height: int(h)})
		}
	}
	return mons
}

func (b *windowsBackend) CaptureScreen(params map[string]any) (*ScreenCaptureResult, error) {
	mons := b.enumMonitors()
	var rx, ry, rw, rh int
	if reg, ok := params["region"].(map[string]any); ok && paramInt(reg, "width", 0) > 0 && paramInt(reg, "height", 0) > 0 {
		rx = paramInt(reg, "x", 0)
		ry = paramInt(reg, "y", 0)
		rw = paramInt(reg, "width", 0)
		rh = paramInt(reg, "height", 0)
	} else if wt := strings.TrimSpace(paramStr(params, "windowTitle", "")); wt != "" {
		wins := b.listWindows()
		wl := strings.ToLower(wt)
		found := false
		for _, w := range wins {
			if strings.Contains(strings.ToLower(w.Title), wl) {
				rx, ry, rw, rh = w.X, w.Y, w.Width, w.Height
				found = true
				break
			}
		}
		if !found {
			return nil, fmt.Errorf("未找到匹配标题的窗口: %s", wt)
		}
	} else if _, hasMon := params["monitor"]; hasMon && paramInt(params, "monitor", 0) == -1 {
		vx, _, _ := pGetSystemMetrics.Call(76) // SM_XVIRTUALSCREEN
		vy, _, _ := pGetSystemMetrics.Call(77) // SM_YVIRTUALSCREEN
		vw, _, _ := pGetSystemMetrics.Call(78) // SM_CXVIRTUALSCREEN
		vh, _, _ := pGetSystemMetrics.Call(79) // SM_CYVIRTUALSCREEN
		rx, ry, rw, rh = int(int32(vx)), int(int32(vy)), int(vw), int(vh)
	} else {
		idx := paramInt(params, "monitor", 0)
		if idx < 0 || idx >= len(mons) {
			idx = 0
		}
		if len(mons) == 0 {
			return nil, fmt.Errorf("未检测到可用显示器")
		}
		rx, ry, rw, rh = mons[idx].X, mons[idx].Y, mons[idx].Width, mons[idx].Height
	}
	if rw <= 0 || rh <= 0 {
		return nil, fmt.Errorf("无效截屏尺寸 %dx%d", rw, rh)
	}

	hdcScreen, _, _ := pGetDC.Call(0)
	if hdcScreen == 0 {
		return nil, fmt.Errorf("无法获取屏幕 DC（当前会话可能锁屏或处于非交互桌面）")
	}
	defer pReleaseDC.Call(0, hdcScreen)

	hdcMem, _, _ := pCreateCompatibleDC.Call(hdcScreen)
	if hdcMem == 0 {
		return nil, fmt.Errorf("CreateCompatibleDC 失败")
	}
	defer pDeleteDC.Call(hdcMem)

	hBmp, _, _ := pCreateCompatibleBitmap.Call(hdcScreen, uintptr(rw), uintptr(rh))
	if hBmp == 0 {
		return nil, fmt.Errorf("CreateCompatibleBitmap 失败")
	}
	defer pDeleteObject.Call(hBmp)

	oldObj, _, _ := pSelectObject.Call(hdcMem, hBmp)
	defer pSelectObject.Call(hdcMem, oldObj)

	// SRCCOPY | CAPTUREBLT = 0x00CC0020 | 0x40000000
	okBlt, _, bltErr := pBitBlt.Call(hdcMem, 0, 0, uintptr(rw), uintptr(rh), hdcScreen, uintptr(rx), uintptr(ry), 0x40CC0020)
	if okBlt == 0 {
		return nil, fmt.Errorf("无法截取 Windows 桌面（BitBlt 失败: %v）：当前电脑处于锁屏/安全桌面，或进程运行在非交互会话中，请在已解锁的交互桌面终端启动", bltErr)
	}

	var bih bitmapInfoHeader
	bih.biSize = uint32(unsafe.Sizeof(bih))
	bih.biWidth = int32(rw)
	bih.biHeight = -int32(rh) // top-down DIB
	bih.biPlanes = 1
	bih.biBitCount = 32
	bih.biCompression = 0 // BI_RGB

	buf := make([]byte, rw*rh*4)
	lines, _, _ := pGetDIBits.Call(hdcMem, hBmp, 0, uintptr(rh), uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&bih)), 0)
	if lines == 0 {
		return nil, fmt.Errorf("GetDIBits 读取像素失败")
	}

	// Convert BGRA -> RGBA in place.
	for i := 0; i < len(buf); i += 4 {
		buf[i], buf[i+2] = buf[i+2], buf[i]
		buf[i+3] = 255
	}
	rgba := &image.RGBA{Pix: buf, Stride: rw * 4, Rect: image.Rect(0, 0, rw, rh)}
	maxW := paramInt(params, "maxWidth", 1280)
	if maxW < 320 {
		maxW = 320
	}
	if maxW > 2560 {
		maxW = 2560
	}
	scaled := resizeRGBA(rgba, maxW)
	jpg, err := encodeJPEG(scaled, paramInt(params, "quality", 72))
	if err != nil {
		return nil, err
	}
	return &ScreenCaptureResult{
		JPEG:         jpg,
		Width:        scaled.Bounds().Dx(),
		Height:       scaled.Bounds().Dy(),
		ScreenWidth:  rw,
		ScreenHeight: rh,
		OriginX:      rx,
		OriginY:      ry,
		Monitors:     mons,
	}, nil
}

func sendMouseFlags(flags, data uint32) {
	var in winInput
	in.kind = 0 // INPUT_MOUSE
	mi := (*mouseInput)(unsafe.Pointer(&in.data[0]))
	mi.dwFlags = flags
	mi.mouseData = data
	_, _, _ = pSendInput.Call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
}

func sendVK(vk uint16, keyUp bool) {
	var in winInput
	in.kind = 1 // INPUT_KEYBOARD
	ki := (*keybdInput)(unsafe.Pointer(&in.data[0]))
	ki.wVk = vk
	if keyUp {
		ki.dwFlags = 2 // KEYEVENTF_KEYUP
	}
	_, _, _ = pSendInput.Call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
}

func (b *windowsBackend) MouseAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "click")))
	var mons []MonitorInfo
	normalized := paramBool(params, "normalized", false)
	if normalized {
		mons = b.enumMonitors()
	}
	rawX := paramFloat(params, "x", 0)
	rawY := paramFloat(params, "y", 0)
	x, y := resolveMonitorCoords(params, mons, rawX, rawY)
	if _, hasX := params["x"]; hasX {
		_, _, _ = pSetCursorPos.Call(uintptr(x), uintptr(y))
	}
	switch act {
	case "move":
		// Cursor already positioned above.
	case "click":
		sendMouseFlags(0x0002, 0)
		sendMouseFlags(0x0004, 0)
	case "double_click":
		sendMouseFlags(0x0002, 0)
		sendMouseFlags(0x0004, 0)
		time.Sleep(45 * time.Millisecond)
		sendMouseFlags(0x0002, 0)
		sendMouseFlags(0x0004, 0)
	case "right_click":
		sendMouseFlags(0x0008, 0)
		sendMouseFlags(0x0010, 0)
	case "middle_click":
		sendMouseFlags(0x0020, 0)
		sendMouseFlags(0x0040, 0)
	case "down":
		btn := strings.ToLower(paramStr(params, "button", "left"))
		switch btn {
		case "right":
			sendMouseFlags(0x0008, 0)
		case "middle":
			sendMouseFlags(0x0020, 0)
		default:
			sendMouseFlags(0x0002, 0)
		}
	case "up":
		btn := strings.ToLower(paramStr(params, "button", "left"))
		switch btn {
		case "right":
			sendMouseFlags(0x0010, 0)
		case "middle":
			sendMouseFlags(0x0040, 0)
		default:
			sendMouseFlags(0x0004, 0)
		}
	case "drag":
		toX, toY := resolveMonitorCoords(params, mons, paramFloat(params, "toX", rawX), paramFloat(params, "toY", rawY))
		sendMouseFlags(0x0002, 0)
		const steps = 12
		for i := 1; i <= steps; i++ {
			ix := x + (toX-x)*i/steps
			iy := y + (toY-y)*i/steps
			_, _, _ = pSetCursorPos.Call(uintptr(ix), uintptr(iy))
			time.Sleep(12 * time.Millisecond)
		}
		sendMouseFlags(0x0004, 0)
	case "scroll":
		dy := paramInt(params, "deltaY", -120)
		sendMouseFlags(0x0800, uint32(int32(dy)))
	default:
		return nil, fmt.Errorf("未知鼠标动作: %s", act)
	}
	return map[string]any{"ok": true, "action": act, "x": x, "y": y}, nil
}

var winVKMap = map[string]uint16{
	"backspace": 0x08, "tab": 0x09, "enter": 0x0D, "return": 0x0D,
	"shift": 0x10, "ctrl": 0x11, "control": 0x11, "alt": 0x12, "menu": 0x12,
	"pause": 0x13, "capslock": 0x14, "esc": 0x1B, "escape": 0x1B, "space": 0x20,
	"pageup": 0x21, "pgup": 0x21, "pagedown": 0x22, "pgdn": 0x22,
	"end": 0x23, "home": 0x24, "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
	"printscreen": 0x2C, "insert": 0x2D, "delete": 0x2E, "del": 0x2E,
	"win": 0x5B, "lwin": 0x5B, "cmd": 0x5B, "super": 0x5B, "meta": 0x5B, "rwin": 0x5C,
	"f1": 0x70, "f2": 0x71, "f3": 0x72, "f4": 0x73, "f5": 0x74, "f6": 0x75,
	"f7": 0x76, "f8": 0x77, "f9": 0x78, "f10": 0x79, "f11": 0x7A, "f12": 0x7B,
}

func resolveWinVK(key string) (uint16, error) {
	k := strings.ToLower(strings.TrimSpace(key))
	if vk, ok := winVKMap[k]; ok {
		return vk, nil
	}
	if len(k) == 1 {
		c := strings.ToUpper(k)[0]
		if (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') {
			return uint16(c), nil
		}
	}
	return 0, fmt.Errorf("未识别的 Windows 键名: %s", key)
}

func (b *windowsBackend) KeyboardAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "type")))
	switch act {
	case "type":
		text := paramStr(params, "text", "")
		units := utf16.Encode([]rune(text))
		inputs := make([]winInput, 0, len(units)*2+2)
		for _, u := range units {
			if u == '\r' {
				continue
			}
			if u == '\n' {
				var d, up winInput
				d.kind, up.kind = 1, 1
				(*keybdInput)(unsafe.Pointer(&d.data[0])).wVk = 0x0D
				ku := (*keybdInput)(unsafe.Pointer(&up.data[0]))
				ku.wVk = 0x0D
				ku.dwFlags = 2
				inputs = append(inputs, d, up)
				continue
			}
			var d, up winInput
			d.kind, up.kind = 1, 1
			kd := (*keybdInput)(unsafe.Pointer(&d.data[0]))
			kd.wScan = u
			kd.dwFlags = 4 // KEYEVENTF_UNICODE
			ku := (*keybdInput)(unsafe.Pointer(&up.data[0]))
			ku.wScan = u
			ku.dwFlags = 6 // KEYEVENTF_UNICODE | KEYEVENTF_KEYUP
			inputs = append(inputs, d, up)
		}
		if paramBool(params, "pressEnter", false) {
			var d, up winInput
			d.kind, up.kind = 1, 1
			(*keybdInput)(unsafe.Pointer(&d.data[0])).wVk = 0x0D
			ku := (*keybdInput)(unsafe.Pointer(&up.data[0]))
			ku.wVk = 0x0D
			ku.dwFlags = 2
			inputs = append(inputs, d, up)
		}
		if len(inputs) > 0 {
			_, _, _ = pSendInput.Call(uintptr(len(inputs)), uintptr(unsafe.Pointer(&inputs[0])), unsafe.Sizeof(inputs[0]))
		}
		return map[string]any{"ok": true, "action": "type", "length": len([]rune(text))}, nil

	case "key":
		vk, err := resolveWinVK(paramStr(params, "key", "Enter"))
		if err != nil {
			return nil, err
		}
		sendVK(vk, false)
		sendVK(vk, true)
		return map[string]any{"ok": true, "action": "key", "key": paramStr(params, "key", "")}, nil

	case "hotkey":
		keys := paramStrSlice(params, "keys")
		if len(keys) == 0 {
			return nil, fmt.Errorf("hotkey 需提供 keys 数组")
		}
		vks := make([]uint16, 0, len(keys))
		for _, k := range keys {
			vk, err := resolveWinVK(k)
			if err != nil {
				return nil, err
			}
			vks = append(vks, vk)
		}
		for _, vk := range vks {
			sendVK(vk, false)
		}
		time.Sleep(25 * time.Millisecond)
		for i := len(vks) - 1; i >= 0; i-- {
			sendVK(vks[i], true)
		}
		return map[string]any{"ok": true, "action": "hotkey", "keys": keys}, nil

	default:
		return nil, fmt.Errorf("未知键盘动作: %s", act)
	}
}
