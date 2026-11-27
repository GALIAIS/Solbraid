package main

import (
	"encoding/json"
	"os"
	"strings"
)

// buildPairingJSON returns the exact paste-ready pairing JSON for Solbraid mobile app,
// automatically adapting to relay / public / lan / usb + TLS fingerprint modes.
func buildPairingJSON(cfg *AgentConfig, tlsFP string) string {
	host, _ := os.Hostname()
	id := strings.ToLower(host)
	var clean strings.Builder
	for _, r := range id {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' {
			clean.WriteRune(r)
		} else {
			clean.WriteRune('-')
		}
	}
	hostID := strings.Trim(clean.String(), "-")
	if hostID == "" {
		hostID = "desktop"
	}

	nets := collectNetworks()
	lanIP := "127.0.0.1"
	mac := ""
	if len(nets) > 0 {
		lanIP = nets[0].Address
		mac = nets[0].MAC
	}

	m := map[string]any{
		"action":          "add",
		"id":              hostID,
		"name":            host,
		"port":            cfg.Port,
		"permissionLevel": cfg.PermissionLevel,
		"token":           cfg.Token,
		"mac":             mac,
	}
	if cfg.RelayURL != "" {
		m["transport"] = "relay"
		m["relayUrl"] = cfg.RelayURL
		m["pairId"] = cfg.PairID
		if cfg.RelaySecret != "" {
			m["relaySecret"] = cfg.RelaySecret
		}
	} else if cfg.Host == "127.0.0.1" || cfg.Host == "localhost" {
		m["transport"] = "usb"
		m["address"] = "127.0.0.1"
	} else {
		m["transport"] = "lan"
		m["address"] = lanIP
	}
	if cfg.TLS && cfg.RelayURL == "" {
		m["tls"] = true
		if tlsFP != "" {
			m["tlsFingerprint"] = tlsFP
		}
	}
	b, _ := json.Marshal(m)
	return string(b)
}
