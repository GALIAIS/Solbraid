package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type relayJob struct {
	ReqID   string            `json:"reqId"`
	Headers map[string]string `json:"headers"`
	Body    string            `json:"body"`
}

func addRelayAuthHeaders(req *http.Request, secret, pairID, endpoint string) {
	if secret == "" {
		return
	}
	ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
	nonce := randomHex(12)
	sig := computeHMAC(secret, fmt.Sprintf("%s.%s.%s.%s", ts, nonce, pairID, endpoint))
	req.Header.Set("x-solbraid-relay-ts", ts)
	req.Header.Set("x-solbraid-relay-nonce", nonce)
	req.Header.Set("x-solbraid-relay-auth", sig)
}

func (a *DesktopAgent) runRelayLoop(ctx context.Context) {
	if a.cfg.RelayURL == "" {
		return
	}
	base := strings.TrimRight(a.cfg.RelayURL, "/")
	pairEnc := url.PathEscape(a.cfg.PairID)
	pollClient := &http.Client{Timeout: 32 * time.Second}
	replyClient := &http.Client{Timeout: 15 * time.Second}

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, fmt.Sprintf("%s/relay/%s/poll", base, pairEnc), nil)
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}
		addRelayAuthHeaders(req, a.cfg.RelaySecret, a.cfg.PairID, "poll")
		resp, err := pollClient.Do(req)
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}
		if resp.StatusCode == http.StatusNoContent {
			_ = resp.Body.Close()
			continue
		}
		if resp.StatusCode != http.StatusOK {
			_ = resp.Body.Close()
			time.Sleep(2 * time.Second)
			continue
		}
		var job relayJob
		decErr := json.NewDecoder(resp.Body).Decode(&job)
		_ = resp.Body.Close()
		if decErr != nil || job.ReqID == "" {
			continue
		}

		status, body := a.handleSignedRPC(job.Headers, []byte(job.Body))
		replyBytes, _ := json.Marshal(map[string]any{
			"reqId":  job.ReqID,
			"status": status,
			"body":   string(body),
		})
		rReq, err := http.NewRequestWithContext(ctx, http.MethodPost, fmt.Sprintf("%s/relay/%s/reply", base, pairEnc), bytes.NewReader(replyBytes))
		if err != nil {
			continue
		}
		rReq.Header.Set("content-type", "application/json")
		addRelayAuthHeaders(rReq, a.cfg.RelaySecret, a.cfg.PairID, "reply")
		if rResp, err := replyClient.Do(rReq); err == nil {
			_ = rResp.Body.Close()
		}
	}
}
