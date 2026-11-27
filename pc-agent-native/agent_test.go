package main

import (
	"bytes"
	"encoding/json"
	"image"
	"image/color"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type fakeBackend struct{}

func (f *fakeBackend) Name() string { return "fake-test" }
func (f *fakeBackend) Close()       {}
func (f *fakeBackend) CaptureScreen(params map[string]any) (*ScreenCaptureResult, error) {
	img := image.NewRGBA(image.Rect(0, 0, 64, 36))
	for y := 0; y < 36; y++ {
		for x := 0; x < 64; x++ {
			img.Set(x, y, color.RGBA{R: 40, G: 120, B: 200, A: 255})
		}
	}
	jpg, err := encodeJPEG(img, 70)
	if err != nil {
		return nil, err
	}
	return &ScreenCaptureResult{JPEG: jpg, Width: 64, Height: 36, ScreenWidth: 1920, ScreenHeight: 1080}, nil
}
func (f *fakeBackend) UITree(params map[string]any) (map[string]any, error) {
	return map[string]any{"ok": true, "nodes": []UINode{{ID: "n1", Name: "OK", ControlType: "Button"}}}, nil
}
func (f *fakeBackend) WindowAction(params map[string]any) (map[string]any, error) {
	return map[string]any{"ok": true, "windows": []WindowItem{{Hwnd: "1", Title: "Demo", Active: true}}}, nil
}
func (f *fakeBackend) MouseAction(params map[string]any) (map[string]any, error) {
	return map[string]any{"ok": true, "action": params["action"]}, nil
}
func (f *fakeBackend) KeyboardAction(params map[string]any) (map[string]any, error) {
	return map[string]any{"ok": true, "action": params["action"]}, nil
}
func (f *fakeBackend) ProcessAction(params map[string]any) (map[string]any, error) {
	return map[string]any{"ok": true, "processes": []ProcessItem{{PID: 7, Name: "demo"}}}, nil
}
func (f *fakeBackend) SystemAction(params map[string]any) (map[string]any, error) {
	return map[string]any{"ok": true, "action": params["action"]}, nil
}
func (f *fakeBackend) ClipboardAction(params map[string]any) (map[string]any, error) {
	return map[string]any{"ok": true, "text": "NATIVE_CLIP"}, nil
}

func TestHMACAuthPermissionsStopAndTLS(t *testing.T) {
	tmp := t.TempDir()
	cfg := &AgentConfig{
		Host: "127.0.0.1", Port: 29840, Token: "native-secret-token-256bit",
		PermissionLevel: "admin", PairID: "pair-1",
	}
	agent := newDesktopAgent(cfg, tmp, true, &fakeBackend{})
	srv := httptest.NewServer(agent.httpHandler())
	defer srv.Close()

	// 1. Missing auth rejected with 401.
	body := []byte(`{"id":"1","method":"agent/ping","params":{}}`)
	resp, err := http.Post(srv.URL+"/rpc", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 401 {
		t.Fatalf("expected 401, got %d", resp.StatusCode)
	}
	_ = resp.Body.Close()

	// 2. Valid HMAC succeeds, replay fails with 401.
	hdrs := SignRequestHeaders(cfg.Token, body)
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/rpc", bytes.NewReader(body))
	for k, v := range hdrs {
		req.Header.Set(k, v)
	}
	resp2, err := http.DefaultClient.Do(req)
	if err != nil || resp2.StatusCode != 200 {
		t.Fatalf("expected 200, err=%v status=%v", err, resp2)
	}
	_ = resp2.Body.Close()

	reqReplay, _ := http.NewRequest(http.MethodPost, srv.URL+"/rpc", bytes.NewReader(body))
	for k, v := range hdrs {
		reqReplay.Header.Set(k, v)
	}
	respReplay, _ := http.DefaultClient.Do(reqReplay)
	if respReplay.StatusCode != 401 {
		t.Fatalf("replay must be 401, got %d", respReplay.StatusCode)
	}
	_ = respReplay.Body.Close()

	// 3. High-risk without confirmHighRisk must be 403; with confirmHighRisk succeeds.
	killUnconfirmed := []byte(`{"id":"2","method":"pc/process","params":{"action":"kill","pid":7}}`)
	st, _ := agent.handleSignedRPC(SignRequestHeaders(cfg.Token, killUnconfirmed), killUnconfirmed)
	if st != 403 {
		t.Fatalf("expected 403 for unconfirmed kill, got %d", st)
	}
	killConfirmed := []byte(`{"id":"3","method":"pc/process","params":{"action":"kill","pid":7,"confirmHighRisk":true}}`)
	st, _ = agent.handleSignedRPC(SignRequestHeaders(cfg.Token, killConfirmed), killConfirmed)
	if st != 200 {
		t.Fatalf("expected 200 for confirmed kill, got %d", st)
	}

	// 4. STOP file blocks non-ping calls.
	_ = os.WriteFile(filepath.Join(tmp, "STOP"), []byte("1"), 0o600)
	winReq := []byte(`{"id":"4","method":"pc/window","params":{"action":"list"}}`)
	st, _ = agent.handleSignedRPC(SignRequestHeaders(cfg.Token, winReq), winReq)
	if st != 403 {
		t.Fatalf("expected 403 under STOP file, got %d", st)
	}
	_ = os.Remove(filepath.Join(tmp, "STOP"))

	// 5. Auto self-signed TLS generation + deterministic fingerprint + pairing JSON.
	cPath, kPath, fp, err := ensureSelfSignedTLS(tmp)
	if err != nil || len(fp) != 64 {
		t.Fatalf("ensureSelfSignedTLS failed: err=%v fp=%s", err, fp)
	}
	_, fp2, err := loadTLSConfig(cPath, kPath)
	if err != nil || fp2 != fp {
		t.Fatalf("fingerprint mismatch: %s vs %s (%v)", fp, fp2, err)
	}
	cfg.TLS = true
	cfg.RelayURL = "https://relay.example.com"
	cfg.RelaySecret = "rsec"
	pj := buildPairingJSON(cfg, fp)
	var parsed map[string]any
	if err := json.Unmarshal([]byte(pj), &parsed); err != nil {
		t.Fatal(err)
	}
	if parsed["transport"] != "relay" || parsed["relaySecret"] != "rsec" {
		t.Fatalf("unexpected relay pairing json: %s", pj)
	}
	if _, hasFp := parsed["tlsFingerprint"]; hasFp {
		t.Fatalf("relay pairing json must not reuse PC self-signed tlsFingerprint: %s", pj)
	}
	cfg.RelayURL = ""
	cfg.Host = "0.0.0.0"
	lanJson := buildPairingJSON(cfg, fp)
	var parsedLan map[string]any
	if err := json.Unmarshal([]byte(lanJson), &parsedLan); err != nil {
		t.Fatal(err)
	}
	if parsedLan["transport"] != "lan" || parsedLan["tlsFingerprint"] != fp {
		t.Fatalf("unexpected lan pairing json: %s", lanJson)
	}

	// 6. Native files write + read_base64 roundtrip.
	testFile := filepath.Join(tmp, "sub", "hi.txt")
	if _, err := handleFilesAction(map[string]any{"action": "write", "path": testFile, "content": "HELLO_NATIVE"}); err != nil {
		t.Fatal(err)
	}
	rd, err := handleFilesAction(map[string]any{"action": "read", "path": testFile})
	if err != nil || !strings.Contains(rd["content"].(string), "HELLO_NATIVE") {
		t.Fatalf("unexpected file read: %v %v", rd, err)
	}
}
