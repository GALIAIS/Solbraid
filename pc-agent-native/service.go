package main

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

func xmlEscape(s string) string {
	var b bytes.Buffer
	_ = xml.EscapeText(&b, []byte(s))
	return b.String()
}

// manageAutostartService installs or removes OS-native login autostart for solbraid-desktop.
func manageAutostartService(action string, cfg *AgentConfig) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	exe, _ = filepath.Abs(exe)

	// Secrets (token, relaySecret) are loaded from 0600 config.json and never placed on service command lines.
	args := []string{
		"--host", cfg.Host,
		"--port", fmt.Sprintf("%d", cfg.Port),
		"--permission", cfg.PermissionLevel,
	}
	if cfg.AutoTLS || cfg.TLS {
		args = append(args, "--auto-tls")
	}
	if cfg.RelayURL != "" {
		args = append(args, "--relay", cfg.RelayURL, "--pair-id", cfg.PairID)
	}

	switch runtime.GOOS {
	case "windows":
		taskName := "SolbraidDesktopAgent"
		if action == "uninstall" {
			return exec.Command("schtasks.exe", "/Delete", "/TN", taskName, "/F").Run()
		}
		quoted := make([]string, 0, len(args)+1)
		quoted = append(quoted, `"`+exe+`"`)
		for _, a := range args {
			quoted = append(quoted, `"`+a+`"`)
		}
		tr := strings.Join(quoted, " ")
		if err := exec.Command("schtasks.exe", "/Create", "/TN", taskName, "/SC", "ONLOGON", "/RL", "HIGHEST", "/F", "/TR", tr).Run(); err != nil {
			if err2 := exec.Command("schtasks.exe", "/Create", "/TN", taskName, "/SC", "ONLOGON", "/F", "/TR", tr).Run(); err2 != nil {
				return err2
			}
		}
		fmt.Printf("已安装 Windows 登录自启任务: %s\n", taskName)
		return nil

	case "darwin":
		home, _ := os.UserHomeDir()
		plistDir := filepath.Join(home, "Library", "LaunchAgents")
		plist := filepath.Join(plistDir, "com.galiais.solbraid.desktop.plist")
		if action == "uninstall" {
			_ = exec.Command("launchctl", "unload", plist).Run()
			_ = os.Remove(plist)
			fmt.Println("已卸载 macOS LaunchAgent")
			return nil
		}
		_ = os.MkdirAll(plistDir, 0o755)
		var xmlArgs strings.Builder
		xmlArgs.WriteString(fmt.Sprintf("    <string>%s</string>\n", xmlEscape(exe)))
		for _, a := range args {
			xmlArgs.WriteString(fmt.Sprintf("    <string>%s</string>\n", xmlEscape(a)))
		}
		content := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.galiais.solbraid.desktop</string>
  <key>ProgramArguments</key><array>
%s  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict></plist>
`, xmlArgs.String())
		if err := os.WriteFile(plist, []byte(content), 0o600); err != nil {
			return err
		}
		_ = exec.Command("launchctl", "unload", plist).Run()
		if err := exec.Command("launchctl", "load", "-w", plist).Run(); err != nil {
			return err
		}
		fmt.Printf("已安装并启动 macOS LaunchAgent: %s\n", plist)
		return nil

	default:
		home, _ := os.UserHomeDir()
		unitDir := filepath.Join(home, ".config", "systemd", "user")
		unit := filepath.Join(unitDir, "solbraid-desktop.service")
		if action == "uninstall" {
			_ = exec.Command("systemctl", "--user", "disable", "--now", "solbraid-desktop.service").Run()
			_ = os.Remove(unit)
			_ = exec.Command("systemctl", "--user", "daemon-reload").Run()
			fmt.Println("已卸载 Linux systemd 用户服务")
			return nil
		}
		_ = os.MkdirAll(unitDir, 0o755)
		execLine := fmt.Sprintf(`"%s"`, exe)
		for _, a := range args {
			execLine += fmt.Sprintf(` "%s"`, a)
		}
		unitText := fmt.Sprintf(`[Unit]
Description=Solbraid Native Desktop Computer Use Agent
After=graphical-session.target

[Service]
ExecStart=%s
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
`, execLine)
		if err := os.WriteFile(unit, []byte(unitText), 0o600); err != nil {
			return err
		}
		_ = exec.Command("systemctl", "--user", "daemon-reload").Run()
		if err := exec.Command("systemctl", "--user", "enable", "--now", "solbraid-desktop.service").Run(); err != nil {
			return err
		}
		fmt.Printf("已安装并启动 Linux systemd 服务: %s\n", unit)
		return nil
	}
}
