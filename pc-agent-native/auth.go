package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

var highRiskShellRe = regexp.MustCompile(`(?i)\b(rm\s+-rf|rmdir\s+/s|del\s+/[fq]|format\s+[a-z]:|diskpart|shutdown|reboot|poweroff|halt|mkfs|dd\s+if=|reg\s+delete|bcdedit|Remove-Item\s+.*-Recurse\s+.*-Force|Stop-Computer|Restart-Computer|Clear-Disk)\b`)

type AuthVerifier struct {
	token     string
	mu        sync.Mutex
	seenNonce map[string]int64
}

func NewAuthVerifier(token string) *AuthVerifier {
	return &AuthVerifier{
		token:     token,
		seenNonce: make(map[string]int64),
	}
}

func randomHex(nBytes int) string {
	b := make([]byte, nBytes)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func computeHMAC(secret, msg string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(msg))
	return hex.EncodeToString(mac.Sum(nil))
}

func SignRequestHeaders(token string, rawBody []byte) map[string]string {
	ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
	nonce := randomHex(12)
	bh := sha256.Sum256(rawBody)
	sig := computeHMAC(token, fmt.Sprintf("%s.%s.%s", ts, nonce, hex.EncodeToString(bh[:])))
	return map[string]string{
		"x-solbraid-timestamp": ts,
		"x-solbraid-nonce":     nonce,
		"x-solbraid-signature": sig,
	}
}

func (v *AuthVerifier) Verify(headers map[string]string, rawBody []byte) (int, string) {
	tsRaw := strings.TrimSpace(headers["x-solbraid-timestamp"])
	nonce := strings.TrimSpace(headers["x-solbraid-nonce"])
	sig := strings.ToLower(strings.TrimSpace(headers["x-solbraid-signature"]))
	if tsRaw == "" || nonce == "" || sig == "" {
		return http.StatusUnauthorized, "缺少 Solbraid HMAC 认证头"
	}
	ts, err := strconv.ParseInt(tsRaw, 10, 64)
	if err != nil {
		return http.StatusUnauthorized, "时间戳格式无效"
	}
	now := time.Now().UnixMilli()
	if math.Abs(float64(now-ts)) > 120_000 {
		return http.StatusUnauthorized, "请求时间戳超出允许窗口（±120s）"
	}

	bh := sha256.Sum256(rawBody)
	expected := computeHMAC(v.token, fmt.Sprintf("%s.%s.%s", tsRaw, nonce, hex.EncodeToString(bh[:])))
	if !hmac.Equal([]byte(sig), []byte(expected)) {
		return http.StatusUnauthorized, "HMAC 签名校验失败"
	}

	v.mu.Lock()
	defer v.mu.Unlock()
	for k, seenTs := range v.seenNonce {
		if now-seenTs > 180_000 {
			delete(v.seenNonce, k)
		}
	}
	if _, dup := v.seenNonce[nonce]; dup {
		return http.StatusUnauthorized, "检测到重放请求（重复 nonce）"
	}
	v.seenNonce[nonce] = now
	return http.StatusOK, ""
}

func extractLowerHeaders(h http.Header) map[string]string {
	out := make(map[string]string, 4)
	for _, k := range []string{"x-solbraid-timestamp", "x-solbraid-nonce", "x-solbraid-signature"} {
		if val := h.Get(k); val != "" {
			out[k] = val
		}
	}
	return out
}

func tierRank(t string) int {
	switch t {
	case "admin":
		return 3
	case "operate":
		return 2
	default:
		return 1
	}
}

type actionPolicy struct {
	Tier     string
	HighRisk bool
}

func requiredPolicyFor(method string, params map[string]any) actionPolicy {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "")))
	switch method {
	case "agent/ping", "agent/capabilities", "pc/screenshot":
		return actionPolicy{Tier: "observe"}
	case "pc/ui_tree":
		if paramStr(params, "clickName", "") != "" {
			return actionPolicy{Tier: "operate"}
		}
		return actionPolicy{Tier: "observe"}
	case "pc/window":
		if act == "" || act == "list" || act == "active" {
			return actionPolicy{Tier: "observe"}
		}
		return actionPolicy{Tier: "operate"}
	case "pc/mouse", "pc/keyboard":
		return actionPolicy{Tier: "operate"}
	case "pc/clipboard":
		if act == "set" {
			return actionPolicy{Tier: "operate"}
		}
		return actionPolicy{Tier: "observe"}
	case "pc/process":
		if act == "" || act == "list" {
			return actionPolicy{Tier: "observe"}
		}
		if act == "kill" {
			return actionPolicy{Tier: "admin", HighRisk: true}
		}
		return actionPolicy{Tier: "operate"}
	case "pc/system":
		if act == "" || act == "info" || act == "monitors" {
			return actionPolicy{Tier: "observe"}
		}
		if act == "lock" || act == "sleep" || act == "restart" || act == "shutdown" {
			return actionPolicy{Tier: "admin", HighRisk: true}
		}
		return actionPolicy{Tier: "operate"}
	case "pc/files":
		if act == "" || act == "list" || act == "stat" || act == "read" || act == "read_base64" || act == "search" {
			return actionPolicy{Tier: "observe"}
		}
		if act == "delete" {
			return actionPolicy{Tier: "admin", HighRisk: true}
		}
		return actionPolicy{Tier: "operate"}
	case "pc/shell":
		if paramBool(params, "elevated", false) || highRiskShellRe.MatchString(paramStr(params, "command", "")) {
			return actionPolicy{Tier: "admin", HighRisk: true}
		}
		return actionPolicy{Tier: "operate"}
	case "pc/browser":
		if act == "" || act == "tabs" || act == "read" || act == "screenshot" {
			return actionPolicy{Tier: "observe"}
		}
		return actionPolicy{Tier: "operate"}
	default:
		return actionPolicy{Tier: "operate"}
	}
}
