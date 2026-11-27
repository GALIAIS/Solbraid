package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"runtime"
	"strings"
)

const discoveryMagic = "SOLBRAID_DISCOVER_V1"

type discoveryReply struct {
	Magic           string `json:"magic"`
	Name            string `json:"name"`
	Hostname        string `json:"hostname"`
	OS              string `json:"os"`
	Arch            string `json:"arch"`
	Port            int    `json:"port"`
	TLS             bool   `json:"tls"`
	TLSFingerprint  string `json:"tlsFingerprint,omitempty"`
	PairID          string `json:"pairId"`
	PermissionLevel string `json:"permissionLevel"`
	MAC             string `json:"mac,omitempty"`
	Engine          string `json:"engine"`
	Version         string `json:"version"`
}

// startLanDiscovery responds to Solbraid phone UDP broadcasts (never reveals pairing token).
func startLanDiscovery(cfg *AgentConfig, tlsFP string, version string) (func(), error) {
	port := cfg.DiscoveryPort
	if port <= 0 {
		port = 29841
	}
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: port})
	if err != nil {
		return func() {}, err
	}
	host, _ := os.Hostname()
	mac := ""
	if nets := collectNetworks(); len(nets) > 0 {
		mac = nets[0].MAC
	}
	replyObj := discoveryReply{
		Magic:           "SOLBRAID_DESKTOP_V1",
		Name:            host,
		Hostname:        host,
		OS:              runtime.GOOS,
		Arch:            runtime.GOARCH,
		Port:            cfg.Port,
		TLS:             cfg.TLS,
		TLSFingerprint:  tlsFP,
		PairID:          cfg.PairID,
		PermissionLevel: cfg.PermissionLevel,
		MAC:             mac,
		Engine:          "native-go",
		Version:         version,
	}
	payload, _ := json.Marshal(replyObj)

	done := make(chan struct{})
	go func() {
		buf := make([]byte, 1024)
		for {
			n, remote, err := conn.ReadFromUDP(buf)
			if err != nil {
				select {
				case <-done:
					return
				default:
					continue
				}
			}
			msg := strings.TrimSpace(string(buf[:n]))
			if strings.HasPrefix(msg, discoveryMagic) {
				_, _ = conn.WriteToUDP(payload, remote)
			}
		}
	}()

	return func() {
		close(done)
		_ = conn.Close()
	}, nil
}

func formatBannerAddr(cfg *AgentConfig, tlsFP string) string {
	scheme := "http"
	if cfg.TLS {
		scheme = "https"
	}
	extra := ""
	if tlsFP != "" {
		extra = " | TLS-SHA256: " + tlsFP[:16] + "..."
	}
	return fmt.Sprintf("%s://%s:%d%s", scheme, cfg.Host, cfg.Port, extra)
}
