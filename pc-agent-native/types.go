package main

import (
	"bytes"
	"encoding/base64"
	"image"
	"image/jpeg"
	"net"
	"os"
	"runtime"
	"strings"
)

// AgentConfig holds runtime configuration persisted in config.json.
type AgentConfig struct {
	Host            string `json:"host"`
	Port            int    `json:"port"`
	Token           string `json:"token"`
	PermissionLevel string `json:"permissionLevel"`
	PairID          string `json:"pairId"`
	CDPPort         int    `json:"cdpPort"`
	RelayURL        string `json:"relayUrl"`
	RelaySecret     string `json:"relaySecret,omitempty"`
	TLS             bool   `json:"tls"`
	AutoTLS         bool   `json:"autoTls,omitempty"`
	CertFile        string `json:"certFile,omitempty"`
	KeyFile         string `json:"keyFile,omitempty"`
	DiscoveryPort   int    `json:"discoveryPort,omitempty"`
	DisableDiscover bool   `json:"disableDiscovery,omitempty"`
}

// MonitorInfo describes an attached desktop monitor in virtual screen coordinates.
type MonitorInfo struct {
	Index   int  `json:"index"`
	Primary bool `json:"primary"`
	X       int  `json:"x"`
	Y       int  `json:"y"`
	Width   int  `json:"width"`
	Height  int  `json:"height"`
}

// ScreenCaptureResult carries raw JPEG bytes plus metadata for RPC & MJPEG streaming.
type ScreenCaptureResult struct {
	JPEG         []byte
	Width        int
	Height       int
	ScreenWidth  int
	ScreenHeight int
	OriginX      int
	OriginY      int
	Monitors     []MonitorInfo
}

// WindowItem describes a top-level visible desktop window.
type WindowItem struct {
	Hwnd    string `json:"hwnd,omitempty"`
	Title   string `json:"title"`
	PID     int    `json:"pid,omitempty"`
	Process string `json:"process,omitempty"`
	Active  bool   `json:"active"`
	X       int    `json:"x"`
	Y       int    `json:"y"`
	Width   int    `json:"width"`
	Height  int    `json:"height"`
}

// UINode describes an accessibility / UIA widget node.
type UINode struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	ControlType string `json:"controlType"`
	X           int    `json:"x"`
	Y           int    `json:"y"`
	Width       int    `json:"width"`
	Height      int    `json:"height"`
	CX          int    `json:"cx"`
	CY          int    `json:"cy"`
}

// ProcessItem describes an OS process entry.
type ProcessItem struct {
	PID    int     `json:"pid"`
	Name   string  `json:"name"`
	CPU    float64 `json:"cpu,omitempty"`
	MemMB  float64 `json:"memMB,omitempty"`
	MemPct float64 `json:"memPct,omitempty"`
	Title  string  `json:"title,omitempty"`
}

// NetIfaceInfo holds a non-loopback IPv4 interface for LAN pairing & WoL.
type NetIfaceInfo struct {
	Name    string `json:"name"`
	Address string `json:"address"`
	MAC     string `json:"mac"`
}

// OSBackend is implemented natively per OS via build tags (windows / darwin / linux).
type OSBackend interface {
	Name() string
	CaptureScreen(params map[string]any) (*ScreenCaptureResult, error)
	UITree(params map[string]any) (map[string]any, error)
	WindowAction(params map[string]any) (map[string]any, error)
	MouseAction(params map[string]any) (map[string]any, error)
	KeyboardAction(params map[string]any) (map[string]any, error)
	ProcessAction(params map[string]any) (map[string]any, error)
	SystemAction(params map[string]any) (map[string]any, error)
	ClipboardAction(params map[string]any) (map[string]any, error)
	Close()
}

func collectNetworks() []NetIfaceInfo {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []NetIfaceInfo
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		mac := strings.ToUpper(ifc.HardwareAddr.String())
		for _, a := range addrs {
			ipNet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			v4 := ipNet.IP.To4()
			if v4 == nil || v4.IsLoopback() || v4.IsLinkLocalUnicast() {
				continue
			}
			// Prefer physical LAN addresses over virtual/TUN ranges (198.18.x.x).
			out = append(out, NetIfaceInfo{Name: ifc.Name, Address: v4.String(), MAC: mac})
		}
	}
	// Move non-198.18 and non-zero MAC interfaces to the front.
	var preferred, fallback []NetIfaceInfo
	for _, n := range out {
		if !strings.HasPrefix(n.Address, "198.18.") && n.MAC != "" && n.MAC != "00:00:00:00:00:00" {
			preferred = append(preferred, n)
		} else {
			fallback = append(fallback, n)
		}
	}
	return append(preferred, fallback...)
}

func collectSystemInfo() map[string]any {
	host, _ := os.Hostname()
	user := os.Getenv("USERNAME")
	if user == "" {
		user = os.Getenv("USER")
	}
	return map[string]any{
		"hostname": host,
		"platform": runtime.GOOS,
		"arch":     runtime.GOARCH,
		"cpus":     runtime.NumCPU(),
		"user":     user,
		"engine":   "native-go",
		"networks": collectNetworks(),
	}
}

// resizeRGBA performs fast bilinear downscaling when src exceeds maxWidth.
func resizeRGBA(src *image.RGBA, maxWidth int) *image.RGBA {
	sb := src.Bounds()
	sw, sh := sb.Dx(), sb.Dy()
	if maxWidth <= 0 || sw <= maxWidth || sw <= 0 || sh <= 0 {
		return src
	}
	dw := maxWidth
	dh := int(float64(sh)*float64(dw)/float64(sw) + 0.5)
	if dh < 1 {
		dh = 1
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	xRatio := float64(sw) / float64(dw)
	yRatio := float64(sh) / float64(dh)
	for dy := 0; dy < dh; dy++ {
		sy := int(float64(dy) * yRatio)
		if sy >= sh {
			sy = sh - 1
		}
		srcRow := sy * src.Stride
		dstRow := dy * dst.Stride
		for dx := 0; dx < dw; dx++ {
			sx := int(float64(dx) * xRatio)
			if sx >= sw {
				sx = sw - 1
			}
			si := srcRow + sx*4
			di := dstRow + dx*4
			dst.Pix[di] = src.Pix[si]
			dst.Pix[di+1] = src.Pix[si+1]
			dst.Pix[di+2] = src.Pix[si+2]
			dst.Pix[di+3] = 255
		}
	}
	return dst
}

func encodeJPEG(img image.Image, quality int) ([]byte, error) {
	if quality < 25 {
		quality = 25
	}
	if quality > 95 {
		quality = 95
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: quality}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func captureResultToRPC(r *ScreenCaptureResult) map[string]any {
	return map[string]any{
		"ok":           true,
		"width":        r.Width,
		"height":       r.Height,
		"screenWidth":  r.ScreenWidth,
		"screenHeight": r.ScreenHeight,
		"originX":      r.OriginX,
		"originY":      r.OriginY,
		"monitors":     r.Monitors,
		"mimeType":     "image/jpeg",
		"imageBase64":  base64.StdEncoding.EncodeToString(r.JPEG),
	}
}

func paramStr(m map[string]any, key, def string) string {
	if m == nil {
		return def
	}
	if v, ok := m[key]; ok && v != nil {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return def
}

func paramInt(m map[string]any, key string, def int) int {
	if m == nil {
		return def
	}
	v, ok := m[key]
	if !ok || v == nil {
		return def
	}
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	case int64:
		return int(n)
	}
	return def
}

func paramBool(m map[string]any, key string, def bool) bool {
	if m == nil {
		return def
	}
	if v, ok := m[key]; ok && v != nil {
		if b, ok := v.(bool); ok {
			return b
		}
	}
	return def
}

func paramFloat(m map[string]any, key string, def float64) float64 {
	if m == nil {
		return def
	}
	v, ok := m[key]
	if !ok || v == nil {
		return def
	}
	switch n := v.(type) {
	case float64:
		return n
	case int:
		return float64(n)
	case int64:
		return float64(n)
	}
	return def
}

// resolveMonitorCoords converts either physical coordinates or 0..1000 normalized coordinates
// on the target monitor (default 0 / primary) into absolute desktop coordinates.
func resolveMonitorCoords(params map[string]any, mons []MonitorInfo, rawX, rawY float64) (int, int) {
	if !paramBool(params, "normalized", false) {
		return int(rawX), int(rawY)
	}
	var mon MonitorInfo
	if len(mons) > 0 {
		idx := paramInt(params, "monitor", -1)
		if idx >= 0 && idx < len(mons) {
			mon = mons[idx]
		} else {
			mon = mons[0]
			for _, m := range mons {
				if m.Primary {
					mon = m
					break
				}
			}
		}
	}
	if mon.Width <= 0 || mon.Height <= 0 {
		return int(rawX), int(rawY)
	}
	nx := rawX
	ny := rawY
	if nx < 0 {
		nx = 0
	} else if nx > 1000 {
		nx = 1000
	}
	if ny < 0 {
		ny = 0
	} else if ny > 1000 {
		ny = 1000
	}
	ax := mon.X + int(nx*float64(mon.Width)/1000.0+0.5)
	ay := mon.Y + int(ny*float64(mon.Height)/1000.0+0.5)
	return ax, ay
}

func paramStrSlice(m map[string]any, key string) []string {
	if m == nil {
		return nil
	}
	raw, ok := m[key]
	if !ok || raw == nil {
		return nil
	}
	switch s := raw.(type) {
	case []string:
		return s
	case []any:
		out := make([]string, 0, len(s))
		for _, item := range s {
			if str, ok := item.(string); ok {
				out = append(out, str)
			}
		}
		return out
	}
	return nil
}
