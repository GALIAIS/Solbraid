package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const agentVersion = "2.0.0-ultimate"

type DesktopAgent struct {
	cfg            *AgentConfig
	cfgDir         string
	stopFile       string
	auditFile      string
	ephemeral      bool
	tlsFingerprint string
	auth           *AuthVerifier
	backend        OSBackend
	auditMu        sync.Mutex
}

func newDesktopAgent(cfg *AgentConfig, cfgDir string, ephemeral bool, backend OSBackend) *DesktopAgent {
	return &DesktopAgent{
		cfg:       cfg,
		cfgDir:    cfgDir,
		stopFile:  filepath.Join(cfgDir, "STOP"),
		auditFile: filepath.Join(cfgDir, "audit.jsonl"),
		ephemeral: ephemeral,
		auth:      NewAuthVerifier(cfg.Token),
		backend:   backend,
	}
}

func (a *DesktopAgent) logAudit(entry map[string]any) {
	if a.ephemeral {
		return
	}
	entry["ts"] = time.Now().UnixMilli()
	raw, err := json.Marshal(entry)
	if err != nil {
		return
	}
	a.auditMu.Lock()
	defer a.auditMu.Unlock()
	f, err := os.OpenFile(a.auditFile, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	_, _ = f.Write(append(raw, '\n'))
	_ = f.Close()
}

func (a *DesktopAgent) stopFileExists() bool {
	_, err := os.Stat(a.stopFile)
	return err == nil
}

func (a *DesktopAgent) dispatchRPC(method string, params map[string]any) (int, any, error) {
	if a.stopFileExists() && method != "agent/ping" && method != "agent/capabilities" {
		return http.StatusForbidden, nil, fmt.Errorf("电脑端急停文件生效中（%s），删除该文件后恢复操控", a.stopFile)
	}
	pol := requiredPolicyFor(method, params)
	if tierRank(a.cfg.PermissionLevel) < tierRank(pol.Tier) {
		return http.StatusForbidden, nil, fmt.Errorf("电脑端当前权限档位为 %s，拒绝执行需要 %s 权限的 %s", a.cfg.PermissionLevel, pol.Tier, method)
	}
	if pol.HighRisk && !paramBool(params, "confirmHighRisk", false) {
		return http.StatusForbidden, nil, fmt.Errorf("电脑端拒绝未确认的高危操作：%s", method)
	}

	switch method {
	case "agent/ping":
		host, _ := os.Hostname()
		user := os.Getenv("USERNAME")
		if user == "" {
			user = os.Getenv("USER")
		}
		return http.StatusOK, map[string]any{
			"ok":                  true,
			"version":             agentVersion,
			"engine":              "native-go",
			"backend":             a.backend.Name(),
			"platform":            runtime.GOOS,
			"arch":                runtime.GOARCH,
			"hostname":            host,
			"user":                user,
			"permissionLevel":     a.cfg.PermissionLevel,
			"emergencyStopActive": a.stopFileExists(),
			"pairId":              a.cfg.PairID,
			"tls":                 a.cfg.TLS,
			"tlsFingerprint":      a.tlsFingerprint,
			"mjpegStream":         true,
		}, nil

	case "agent/capabilities":
		return http.StatusOK, map[string]any{
			"ok":          true,
			"version":     agentVersion,
			"engine":      "native-go",
			"backend":     a.backend.Name(),
			"platform":    runtime.GOOS,
			"arch":        runtime.GOARCH,
			"mjpegStream": true,
			"autoTls":     a.cfg.TLS,
		}, nil

	case "pc/screenshot":
		res, err := a.backend.CaptureScreen(params)
		if err != nil {
			return http.StatusInternalServerError, nil, err
		}
		return http.StatusOK, captureResultToRPC(res), nil

	case "pc/ui_tree":
		res, err := a.backend.UITree(params)
		return statusFromErr(res, err)
	case "pc/window":
		res, err := a.backend.WindowAction(params)
		return statusFromErr(res, err)
	case "pc/mouse":
		res, err := a.backend.MouseAction(params)
		return statusFromErr(res, err)
	case "pc/keyboard":
		res, err := a.backend.KeyboardAction(params)
		return statusFromErr(res, err)
	case "pc/shell":
		res, err := runShellCommand(params)
		return statusFromErr(res, err)
	case "pc/process":
		res, err := a.backend.ProcessAction(params)
		return statusFromErr(res, err)
	case "pc/system":
		res, err := a.backend.SystemAction(params)
		return statusFromErr(res, err)
	case "pc/files":
		res, err := handleFilesAction(params)
		return statusFromErr(res, err)
	case "pc/clipboard":
		res, err := a.backend.ClipboardAction(params)
		return statusFromErr(res, err)
	case "pc/browser":
		res, err := handleBrowserCDP(params, a.cfg.CDPPort)
		return statusFromErr(res, err)
	default:
		return http.StatusNotFound, nil, fmt.Errorf("未知电脑代理方法：%s", method)
	}
}

func statusFromErr(res map[string]any, err error) (int, any, error) {
	if err != nil {
		return http.StatusInternalServerError, nil, err
	}
	return http.StatusOK, res, nil
}

func (a *DesktopAgent) handleSignedRPC(headers map[string]string, rawBody []byte) (int, []byte) {
	if st, msg := a.auth.Verify(headers, rawBody); st != http.StatusOK {
		b, _ := json.Marshal(map[string]any{"ok": false, "error": msg})
		return st, b
	}
	var req struct {
		ID           any            `json:"id"`
		Method       string         `json:"method"`
		Params       map[string]any `json:"params"`
		ResponseAuth int            `json:"responseAuth"`
	}
	if err := json.Unmarshal(rawBody, &req); err != nil {
		b, _ := json.Marshal(map[string]any{"ok": false, "error": "无效 JSON 请求体"})
		return http.StatusBadRequest, b
	}
	if req.Params == nil {
		req.Params = map[string]any{}
	}
	reply := func(status int, result map[string]any) (int, []byte) {
		payload, err := json.Marshal(result)
		if err != nil {
			status = http.StatusInternalServerError
			payload, _ = json.Marshal(map[string]any{"ok": false, "id": req.ID, "error": "无法编码电脑代理响应"})
		}
		if req.ResponseAuth != 1 {
			return status, payload
		}
		signature := computeHMAC(a.cfg.Token, fmt.Sprintf("response.%s.%d.%s", strings.TrimSpace(headers["x-solbraid-nonce"]), status, payload))
		envelope, _ := json.Marshal(map[string]string{"payload": string(payload), "signature": signature})
		return status, envelope
	}
	started := time.Now()
	status, data, err := a.dispatchRPC(req.Method, req.Params)
	ms := time.Since(started).Milliseconds()
	if err != nil {
		a.logAudit(map[string]any{"method": req.Method, "ok": false, "status": status, "ms": ms, "error": err.Error()})
		return reply(status, map[string]any{"ok": false, "id": req.ID, "error": err.Error()})
	}
	a.logAudit(map[string]any{"method": req.Method, "ok": true, "ms": ms})
	return reply(http.StatusOK, map[string]any{"ok": true, "id": req.ID, "data": data})
}

// handleMJPEGStream serves an HMAC-authenticated continuous multipart/x-mixed-replace JPEG stream.
func (a *DesktopAgent) handleMJPEGStream(w http.ResponseWriter, r *http.Request) {
	// Authenticate via headers signed over query string (or empty body).
	sigBody := []byte(r.URL.RawQuery)
	if st, msg := a.auth.Verify(extractLowerHeaders(r.Header), sigBody); st != http.StatusOK {
		http.Error(w, msg, st)
		return
	}
	if a.stopFileExists() {
		http.Error(w, "急停文件生效中", http.StatusForbidden)
		return
	}
	q := r.URL.Query()
	fps, _ := strconv.Atoi(q.Get("fps"))
	if fps < 1 {
		fps = 10
	}
	if fps > 30 {
		fps = 30
	}
	maxWidth, _ := strconv.Atoi(q.Get("maxWidth"))
	if maxWidth < 320 || maxWidth > 2560 {
		maxWidth = 1024
	}
	quality, _ := strconv.Atoi(q.Get("quality"))
	if quality < 25 || quality > 95 {
		quality = 62
	}
	capParams := map[string]any{"maxWidth": maxWidth, "quality": quality}
	if monStr := q.Get("monitor"); monStr != "" {
		if m, err := strconv.Atoi(monStr); err == nil {
			capParams["monitor"] = m
		}
	}

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=solbraidframe")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")

	interval := time.Second / time.Duration(fps)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	ctx := r.Context()
	for {
		if a.stopFileExists() {
			return
		}
		frame, err := a.backend.CaptureScreen(capParams)
		if err == nil && len(frame.JPEG) > 0 {
			header := fmt.Sprintf("--solbraidframe\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\nX-Screen-Width: %d\r\nX-Screen-Height: %d\r\n\r\n",
				len(frame.JPEG), frame.ScreenWidth, frame.ScreenHeight)
			if _, wErr := io.WriteString(w, header); wErr != nil {
				return
			}
			if _, wErr := w.Write(frame.JPEG); wErr != nil {
				return
			}
			if _, wErr := io.WriteString(w, "\r\n"); wErr != nil {
				return
			}
			flusher.Flush()
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (a *DesktopAgent) httpHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		host, _ := os.Hostname()
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"ok":              true,
			"version":         agentVersion,
			"engine":          "native-go",
			"backend":         a.backend.Name(),
			"platform":        runtime.GOOS,
			"arch":            runtime.GOARCH,
			"hostname":        host,
			"permissionLevel": a.cfg.PermissionLevel,
			"tls":             a.cfg.TLS,
			"tlsFingerprint":  a.tlsFingerprint,
		})
	})
	mux.HandleFunc("/rpc", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, `{"ok":false,"error":"Method Not Allowed"}`, http.StatusMethodNotAllowed)
			return
		}
		rawBody, err := io.ReadAll(io.LimitReader(r.Body, 48*1024*1024))
		if err != nil {
			http.Error(w, `{"ok":false,"error":"read error"}`, http.StatusBadRequest)
			return
		}
		status, respBody := a.handleSignedRPC(extractLowerHeaders(r.Header), rawBody)
		w.Header().Set("content-type", "application/json; charset=utf-8")
		w.WriteHeader(status)
		_, _ = w.Write(respBody)
	})
	mux.HandleFunc("/stream", a.handleMJPEGStream)
	return mux
}

func loadOrInitConfig(cfgDir string, cli map[string]any, ephemeral bool) (*AgentConfig, error) {
	if err := os.MkdirAll(cfgDir, 0o700); err != nil {
		return nil, err
	}
	cfgFile := filepath.Join(cfgDir, "config.json")
	cfg := &AgentConfig{
		Host:            "127.0.0.1",
		Port:            29840,
		PermissionLevel: "operate",
		CDPPort:         9222,
		DiscoveryPort:   29841,
	}
	if raw, err := os.ReadFile(cfgFile); err == nil {
		_ = json.Unmarshal(raw, cfg)
	}
	if v, ok := cli["host"].(string); ok && v != "" {
		cfg.Host = v
	}
	if v, ok := cli["port"].(int); ok && v >= 0 {
		cfg.Port = v
	}
	if v, ok := cli["token"].(string); ok && v != "" {
		cfg.Token = v
	}
	if cfg.Token == "" {
		cfg.Token = randomHex(32)
	}
	if v, ok := cli["permission"].(string); ok && (v == "observe" || v == "operate" || v == "admin") {
		cfg.PermissionLevel = v
	}
	if v, ok := cli["relay"].(string); ok && v != "" {
		cfg.RelayURL = v
	}
	if v, ok := cli["relaySecret"].(string); ok && v != "" {
		cfg.RelaySecret = v
	}
	if cfg.RelayURL != "" && strings.TrimSpace(cfg.RelaySecret) == "" {
		return nil, fmt.Errorf("公网中继需要 --relay-secret，请填写中继服务器使用的密钥")
	}
	if v, ok := cli["pairId"].(string); ok && v != "" {
		cfg.PairID = v
	}
	if cfg.PairID == "" {
		host, _ := os.Hostname()
		cfg.PairID = strings.ToLower(strings.ReplaceAll(host, " ", "-")) + "-" + randomHex(3)
	}
	if v, ok := cli["autoTls"].(bool); ok && v {
		cfg.TLS = true
		cfg.AutoTLS = true
	}
	if v, ok := cli["tls"].(bool); ok && v {
		cfg.TLS = true
	}
	if v, ok := cli["cert"].(string); ok && v != "" {
		cfg.CertFile = v
	}
	if v, ok := cli["key"].(string); ok && v != "" {
		cfg.KeyFile = v
	}
	if v, ok := cli["noDiscovery"].(bool); ok && v {
		cfg.DisableDiscover = true
	}
	if !ephemeral {
		raw, _ := json.MarshalIndent(cfg, "", "  ")
		_ = os.WriteFile(cfgFile, append(raw, '\n'), 0o600)
	}
	return cfg, nil
}

func main() {
	home, _ := os.UserHomeDir()
	defaultCfgDir := filepath.Join(home, ".solbraid-pc-agent")

	hostFlag := flag.String("host", "", "监听地址（如 127.0.0.1 或 0.0.0.0）")
	portFlag := flag.Int("port", -1, "监听端口（默认 29840）")
	tokenFlag := flag.String("token", "", "256-bit 配对令牌")
	tokenStdinFlag := flag.Bool("token-stdin", false, "从标准输入安全读取配对令牌（避免命令行暴露）")
	permFlag := flag.String("permission", "", "权限档位: observe | operate | admin")
	relayFlag := flag.String("relay", "", "公网中继 URL")
	relaySecretFlag := flag.String("relay-secret", "", "公网中继通道鉴权密钥")
	pairIDFlag := flag.String("pair-id", "", "公网中继配对 ID")
	tlsFlag := flag.Bool("tls", false, "启用 TLS")
	autoTLSFlag := flag.Bool("auto-tls", false, "自动生成并持久化自签 ECDSA TLS 证书")
	certFlag := flag.String("cert", "", "TLS 证书 PEM 路径")
	keyFlag := flag.String("key", "", "TLS 私钥 PEM 路径")
	cfgDirFlag := flag.String("config-dir", defaultCfgDir, "配置与急停文件目录")
	ephemeralFlag := flag.Bool("ephemeral", false, "临时运行，不落盘 config/audit")
	noDiscFlag := flag.Bool("no-discovery", false, "关闭局域网 UDP 发现广播")
	installSvc := flag.Bool("install-service", false, "注册并启用开机自启服务")
	uninstallSvc := flag.Bool("uninstall-service", false, "卸载开机自启服务")
	printPairJSON := flag.Bool("print-pair-json", false, "仅输出当前配对 JSON 后退出")
	versionFlag := flag.Bool("version", false, "打印版本号")
	flag.Parse()

	if *versionFlag {
		fmt.Printf("solbraid-desktop %s (%s/%s)\n", agentVersion, runtime.GOOS, runtime.GOARCH)
		return
	}

	tokVal := *tokenFlag
	if *tokenStdinFlag {
		rawTok, _ := io.ReadAll(io.LimitReader(os.Stdin, 1024))
		lines := strings.Split(strings.ReplaceAll(string(rawTok), "\r\n", "\n"), "\n")
		if len(lines) > 0 && strings.TrimSpace(lines[0]) != "" {
			tokVal = strings.TrimSpace(lines[0])
		}
	} else if tokVal == "" {
		if envTok := strings.TrimSpace(os.Getenv("SOLBRAID_TOKEN")); envTok != "" {
			tokVal = envTok
		}
	}

	cli := map[string]any{
		"host": *hostFlag, "token": tokVal, "permission": *permFlag,
		"relay": *relayFlag, "relaySecret": *relaySecretFlag, "pairId": *pairIDFlag,
		"tls": *tlsFlag, "autoTls": *autoTLSFlag, "cert": *certFlag, "key": *keyFlag,
		"noDiscovery": *noDiscFlag,
	}
	if *portFlag >= 0 {
		cli["port"] = *portFlag
	}
	cfg, err := loadOrInitConfig(*cfgDirFlag, cli, *ephemeralFlag)
	if err != nil {
		fmt.Fprintf(os.Stderr, "加载配置失败: %v\n", err)
		os.Exit(1)
	}

	var tlsCfg *tls.Config
	tlsFP := ""
	if cfg.TLS {
		cFile, kFile := cfg.CertFile, cfg.KeyFile
		if cFile == "" || kFile == "" || cfg.AutoTLS {
			cFile, kFile, tlsFP, err = ensureSelfSignedTLS(*cfgDirFlag)
			if err != nil {
				fmt.Fprintf(os.Stderr, "生成自签 TLS 证书失败: %v\n", err)
				os.Exit(1)
			}
		}
		tlsCfg, tlsFP, err = loadTLSConfig(cFile, kFile)
		if err != nil {
			fmt.Fprintf(os.Stderr, "加载 TLS 证书失败: %v\n", err)
			os.Exit(1)
		}
	}

	if *printPairJSON {
		fmt.Println(buildPairingJSON(cfg, tlsFP))
		return
	}
	if *installSvc {
		if err := manageAutostartService("install", cfg); err != nil {
			fmt.Fprintf(os.Stderr, "安装自启服务失败: %v\n", err)
			os.Exit(1)
		}
		return
	}
	if *uninstallSvc {
		if err := manageAutostartService("uninstall", cfg); err != nil {
			fmt.Fprintf(os.Stderr, "卸载自启服务失败: %v\n", err)
			os.Exit(1)
		}
		return
	}

	backend := newOSBackend()
	defer backend.Close()
	agent := newDesktopAgent(cfg, *cfgDirFlag, *ephemeralFlag, backend)
	agent.tlsFingerprint = tlsFP

	ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", cfg.Host, cfg.Port))
	if err != nil {
		fmt.Fprintf(os.Stderr, "监听端口失败: %v\n", err)
		os.Exit(1)
	}
	if tcpAddr, ok := ln.Addr().(*net.TCPAddr); ok {
		cfg.Port = tcpAddr.Port
	}

	srv := &http.Server{
		Handler:           agent.httpHandler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	if tlsCfg != nil {
		ln = tls.NewListener(ln, tlsCfg)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var stopDisc func()
	if !cfg.DisableDiscover {
		stopDisc, _ = startLanDiscovery(cfg, tlsFP, agentVersion)
		if stopDisc != nil {
			defer stopDisc()
		}
	}
	if cfg.RelayURL != "" {
		go agent.runRelayLoop(ctx)
	}

	host, _ := os.Hostname()
	fmt.Println("========================================")
	fmt.Printf("Solbraid Native Desktop Agent %s 已启动\n", agentVersion)
	fmt.Printf("引擎: %s | 平台: %s/%s | 主机: %s\n", backend.Name(), runtime.GOOS, runtime.GOARCH, host)
	fmt.Printf("监听: %s | 权限档位: %s\n", formatBannerAddr(cfg, tlsFP), cfg.PermissionLevel)
	if cfg.RelayURL != "" {
		fmt.Printf("公网中继: %s (pairId=%s)\n", cfg.RelayURL, cfg.PairID)
	}
	fmt.Printf("配对令牌: %s\n", cfg.Token)
	fmt.Printf("急停方式: 创建 %s 或按 Ctrl+C\n", agent.stopFile)
	fmt.Println("----------------------------------------")
	fmt.Println("手机端配对 JSON:")
	fmt.Println(buildPairingJSON(cfg, tlsFP))
	fmt.Println("========================================")

	go func() {
		if err := srv.Serve(ln); err != nil && err != http.ErrServerClosed {
			fmt.Fprintf(os.Stderr, "服务异常退出: %v\n", err)
		}
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	<-sigCh
	cancel()
	shutCtx, shutCancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer shutCancel()
	_ = srv.Shutdown(shutCtx)
}
