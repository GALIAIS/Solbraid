package main

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

type cdpTarget struct {
	ID                   string `json:"id"`
	Type                 string `json:"type"`
	Title                string `json:"title"`
	URL                  string `json:"url"`
	WebSocketDebuggerURL string `json:"webSocketDebuggerUrl"`
}

func fetchCdpTargets(port int) ([]cdpTarget, error) {
	cli := &http.Client{Timeout: 4 * time.Second}
	resp, err := cli.Get(fmt.Sprintf("http://127.0.0.1:%d/json/list", port))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("CDP HTTP %d", resp.StatusCode)
	}
	var all []cdpTarget
	if err := json.NewDecoder(resp.Body).Decode(&all); err != nil {
		return nil, err
	}
	var pages []cdpTarget
	for _, t := range all {
		if t.Type == "page" {
			pages = append(pages, t)
		}
	}
	return pages, nil
}

// sendCdpCommand implements a minimal zero-dependency RFC 6455 WebSocket client for local CDP.
func sendCdpCommand(wsURL, method string, params map[string]any, timeout time.Duration) (map[string]any, error) {
	u, err := url.Parse(wsURL)
	if err != nil {
		return nil, err
	}
	conn, err := net.DialTimeout("tcp", u.Host, 5*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))

	keyBytes := make([]byte, 16)
	_, _ = rand.Read(keyBytes)
	wsKey := base64.StdEncoding.EncodeToString(keyBytes)
	reqStr := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n",
		u.RequestURI(), u.Host, wsKey)
	if _, err := conn.Write([]byte(reqStr)); err != nil {
		return nil, err
	}
	br := bufio.NewReader(conn)
	statusLine, err := br.ReadString('\n')
	if err != nil || !strings.Contains(statusLine, "101") {
		return nil, fmt.Errorf("CDP WebSocket 握手失败: %s", strings.TrimSpace(statusLine))
	}
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return nil, err
		}
		if line == "\r\n" || line == "\n" {
			break
		}
	}

	payload, _ := json.Marshal(map[string]any{"id": 1, "method": method, "params": params})
	if err := wsWriteClientText(conn, payload); err != nil {
		return nil, err
	}

	for {
		msgBytes, err := wsReadFrame(br)
		if err != nil {
			return nil, err
		}
		var msg map[string]any
		if err := json.Unmarshal(msgBytes, &msg); err != nil {
			continue
		}
		if idFloat, ok := msg["id"].(float64); ok && int(idFloat) == 1 {
			if errObj, ok := msg["error"].(map[string]any); ok {
				return nil, fmt.Errorf("CDP 错误: %v", errObj["message"])
			}
			if res, ok := msg["result"].(map[string]any); ok {
				return res, nil
			}
			return map[string]any{}, nil
		}
	}
}

func wsWriteClientText(w io.Writer, data []byte) error {
	mask := make([]byte, 4)
	_, _ = rand.Read(mask)
	var hdr []byte
	hdr = append(hdr, 0x81) // FIN + text
	n := len(data)
	switch {
	case n < 126:
		hdr = append(hdr, byte(0x80|n))
	case n <= 65535:
		hdr = append(hdr, 0x80|126, byte(n>>8), byte(n))
	default:
		hdr = append(hdr, 0x80|127)
		var b [8]byte
		binary.BigEndian.PutUint64(b[:], uint64(n))
		hdr = append(hdr, b[:]...)
	}
	hdr = append(hdr, mask...)
	masked := make([]byte, n)
	for i := 0; i < n; i++ {
		masked[i] = data[i] ^ mask[i%4]
	}
	if _, err := w.Write(hdr); err != nil {
		return err
	}
	_, err := w.Write(masked)
	return err
}

func wsReadFrame(r *bufio.Reader) ([]byte, error) {
	var full []byte
	for {
		b1, err := r.ReadByte()
		if err != nil {
			return nil, err
		}
		b2, err := r.ReadByte()
		if err != nil {
			return nil, err
		}
		fin := (b1 & 0x80) != 0
		op := b1 & 0x0f
		masked := (b2 & 0x80) != 0
		ln := uint64(b2 & 0x7f)
		if ln == 126 {
			var b [2]byte
			if _, err := io.ReadFull(r, b[:]); err != nil {
				return nil, err
			}
			ln = uint64(binary.BigEndian.Uint16(b[:]))
		} else if ln == 127 {
			var b [8]byte
			if _, err := io.ReadFull(r, b[:]); err != nil {
				return nil, err
			}
			ln = binary.BigEndian.Uint64(b[:])
		}
		const maxWsFrameBytes = 32 * 1024 * 1024
		if ln > maxWsFrameBytes || uint64(len(full))+ln > maxWsFrameBytes {
			return nil, fmt.Errorf("CDP WebSocket 帧超出 32MB 上限")
		}
		var mask [4]byte
		if masked {
			if _, err := io.ReadFull(r, mask[:]); err != nil {
				return nil, err
			}
		}
		buf := make([]byte, ln)
		if _, err := io.ReadFull(r, buf); err != nil {
			return nil, err
		}
		if masked {
			for i := range buf {
				buf[i] ^= mask[i%4]
			}
		}
		if op == 8 {
			return nil, io.EOF
		}
		if op == 9 || op == 10 {
			continue
		}
		full = append(full, buf...)
		if fin {
			return full, nil
		}
	}
}

func findBrowserExe(pref string) string {
	var cands []string
	switch runtime.GOOS {
	case "windows":
		pf := os.Getenv("ProgramFiles")
		if pf == "" {
			pf = `C:\Program Files`
		}
		pf86 := os.Getenv("ProgramFiles(x86)")
		if pf86 == "" {
			pf86 = `C:\Program Files (x86)`
		}
		local := os.Getenv("LOCALAPPDATA")
		edge := []string{filepath.Join(pf86, `Microsoft\Edge\Application\msedge.exe`), filepath.Join(pf, `Microsoft\Edge\Application\msedge.exe`)}
		chrome := []string{filepath.Join(pf, `Google\Chrome\Application\chrome.exe`), filepath.Join(pf86, `Google\Chrome\Application\chrome.exe`)}
		if local != "" {
			chrome = append(chrome, filepath.Join(local, `Google\Chrome\Application\chrome.exe`))
		}
		if pref == "edge" {
			cands = append(edge, chrome...)
		} else {
			cands = append(chrome, edge...)
		}
	case "darwin":
		chrome := "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
		edge := "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
		if pref == "edge" {
			cands = []string{edge, chrome}
		} else {
			cands = []string{chrome, edge}
		}
	default:
		cands = []string{"/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/microsoft-edge"}
	}
	for _, p := range cands {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func handleBrowserCDP(params map[string]any, defaultPort int) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "tabs")))
	port := paramInt(params, "cdpPort", defaultPort)
	if port < 1024 || port > 65535 {
		port = 9222
	}
	if act == "launch" {
		exe := findBrowserExe(paramStr(params, "browser", "auto"))
		if exe == "" {
			return nil, fmt.Errorf("未找到 Chrome/Edge，请手动以 --remote-debugging-port=%d 启动", port)
		}
		home, _ := os.UserHomeDir()
		profile := filepath.Join(home, ".solbraid-pc-agent", fmt.Sprintf("cdp-profile-%d", port))
		_ = os.MkdirAll(profile, 0o755)
		args := []string{fmt.Sprintf("--remote-debugging-port=%d", port), "--user-data-dir=" + profile, "--no-first-run"}
		if u := paramStr(params, "url", ""); u != "" {
			args = append(args, u)
		}
		cmd := exec.Command(exe, args...)
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		go func() { _ = cmd.Wait() }()
		return map[string]any{"ok": true, "launched": exe, "cdpPort": port, "profileDir": profile}, nil
	}

	tabs, err := fetchCdpTargets(port)
	if err != nil {
		return nil, fmt.Errorf("未检测到本机 CDP 浏览器 (127.0.0.1:%d): %w", port, err)
	}
	if act == "tabs" {
		list := make([]map[string]any, 0, len(tabs))
		for _, t := range tabs {
			list = append(list, map[string]any{"id": t.ID, "title": t.Title, "url": t.URL})
		}
		return map[string]any{"ok": true, "cdpPort": port, "tabs": list}, nil
	}
	if act == "open" {
		u := paramStr(params, "url", "about:blank")
		req, _ := http.NewRequest(http.MethodPut, fmt.Sprintf("http://127.0.0.1:%d/json/new?%s", port, url.QueryEscape(u)), nil)
		resp, err := (&http.Client{Timeout: 6 * time.Second}).Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		var created map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&created)
		return map[string]any{"ok": true, "id": created["id"], "url": u}, nil
	}

	var target *cdpTarget
	tid := paramStr(params, "targetId", "")
	for i := range tabs {
		if tid == "" || tabs[i].ID == tid {
			target = &tabs[i]
			break
		}
	}
	if target == nil || target.WebSocketDebuggerURL == "" {
		return nil, fmt.Errorf("未找到可用页面标签")
	}

	switch act {
	case "navigate":
		u := strings.TrimSpace(paramStr(params, "url", ""))
		if u == "" {
			return nil, fmt.Errorf("navigate 需提供 url")
		}
		res, err := sendCdpCommand(target.WebSocketDebuggerURL, "Page.navigate", map[string]any{"url": u}, 15*time.Second)
		if err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "targetId": target.ID, "url": u, "frameId": res["frameId"]}, nil
	case "eval":
		expr := strings.TrimSpace(paramStr(params, "expression", ""))
		if expr == "" {
			return nil, fmt.Errorf("eval 需提供 expression")
		}
		res, err := sendCdpCommand(target.WebSocketDebuggerURL, "Runtime.evaluate", map[string]any{"expression": expr, "returnByValue": true, "awaitPromise": true}, 15*time.Second)
		if err != nil {
			return nil, err
		}
		var val any
		if rObj, ok := res["result"].(map[string]any); ok {
			val = rObj["value"]
		}
		return map[string]any{"ok": true, "targetId": target.ID, "value": val}, nil
	case "read":
		expr := `(() => ({ title: document.title, url: location.href, text: (document.body?.innerText || "").slice(0, 16000) }))()`
		res, err := sendCdpCommand(target.WebSocketDebuggerURL, "Runtime.evaluate", map[string]any{"expression": expr, "returnByValue": true}, 15*time.Second)
		if err != nil {
			return nil, err
		}
		out := map[string]any{"ok": true, "targetId": target.ID}
		if rObj, ok := res["result"].(map[string]any); ok {
			if m, ok := rObj["value"].(map[string]any); ok {
				for k, v := range m {
					out[k] = v
				}
			}
		}
		return out, nil
	case "click":
		sel := strings.TrimSpace(paramStr(params, "selector", ""))
		if sel == "" {
			return nil, fmt.Errorf("click 需提供 selector")
		}
		selJSON, _ := json.Marshal(sel)
		expr := fmt.Sprintf(`(() => { const el = document.querySelector(%s); if (!el) return {ok:false, error:"未匹配到元素"}; el.scrollIntoView({block:"center"}); el.click(); return {ok:true, tag:el.tagName}; })()`, string(selJSON))
		res, err := sendCdpCommand(target.WebSocketDebuggerURL, "Runtime.evaluate", map[string]any{"expression": expr, "returnByValue": true}, 15*time.Second)
		if err != nil {
			return nil, err
		}
		if rObj, ok := res["result"].(map[string]any); ok {
			if m, ok := rObj["value"].(map[string]any); ok {
				m["targetId"] = target.ID
				return m, nil
			}
		}
		return map[string]any{"ok": true, "targetId": target.ID}, nil
	case "type":
		sel := strings.TrimSpace(paramStr(params, "selector", ""))
		txt := paramStr(params, "text", "")
		if sel == "" {
			return nil, fmt.Errorf("type 需提供 selector")
		}
		selJSON, _ := json.Marshal(sel)
		txtJSON, _ := json.Marshal(txt)
		expr := fmt.Sprintf(`(() => { const el = document.querySelector(%s); if (!el) return {ok:false, error:"未匹配到输入框"}; el.focus(); el.value = %s; el.dispatchEvent(new Event("input",{bubbles:true})); el.dispatchEvent(new Event("change",{bubbles:true})); return {ok:true}; })()`, string(selJSON), string(txtJSON))
		res, err := sendCdpCommand(target.WebSocketDebuggerURL, "Runtime.evaluate", map[string]any{"expression": expr, "returnByValue": true}, 15*time.Second)
		if err != nil {
			return nil, err
		}
		if rObj, ok := res["result"].(map[string]any); ok {
			if m, ok := rObj["value"].(map[string]any); ok {
				m["targetId"] = target.ID
				return m, nil
			}
		}
		return map[string]any{"ok": true, "targetId": target.ID}, nil
	case "screenshot":
		res, err := sendCdpCommand(target.WebSocketDebuggerURL, "Page.captureScreenshot", map[string]any{"format": "jpeg", "quality": 75}, 15*time.Second)
		if err != nil {
			return nil, err
		}
		return map[string]any{
			"ok":          true,
			"targetId":    target.ID,
			"title":       target.Title,
			"url":         target.URL,
			"mimeType":    "image/jpeg",
			"imageBase64": res["data"],
		}, nil
	default:
		return nil, fmt.Errorf("未知浏览器动作: %s", act)
	}
}
