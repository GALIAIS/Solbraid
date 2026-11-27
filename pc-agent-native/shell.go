package main

import (
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"runtime"
	"strings"
	"time"
)

func runShellCommand(params map[string]any) (map[string]any, error) {
	command := strings.TrimSpace(paramStr(params, "command", ""))
	if command == "" {
		return nil, fmt.Errorf("command 不能为空")
	}
	timeoutMs := paramInt(params, "timeoutMs", 30_000)
	if _, supplied := params["timeoutSec"]; supplied {
		seconds := paramInt(params, "timeoutSec", 30)
		if seconds < 1 {
			seconds = 1
		}
		if seconds > 180 {
			seconds = 180
		}
		timeoutMs = seconds * 1000
	}
	if timeoutMs < 1000 {
		timeoutMs = 1000
	}
	if timeoutMs > 180_000 {
		timeoutMs = 180_000
	}
	cwd := expandPath(paramStr(params, "cwd", ""))

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		shellPref := strings.ToLower(strings.TrimSpace(paramStr(params, "shell", "powershell")))
		if shellPref == "cmd" {
			cmd = exec.CommandContext(ctx, "cmd.exe", "/C", "chcp 65001>nul & "+command)
		} else {
			ps := "$ProgressPreference='SilentlyContinue'; [Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $OutputEncoding=[System.Text.Encoding]::UTF8; " + command
			cmd = exec.CommandContext(ctx, "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", ps)
		}
	} else {
		cmd = exec.CommandContext(ctx, "/bin/sh", "-lc", command)
	}
	if cwd != "" {
		cmd.Dir = cwd
	}
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	exitCode := 0
	if err != nil {
		if ctx.Err() == context.DeadlineExceeded {
			return map[string]any{
				"ok":       false,
				"exitCode": -1,
				"timedOut": true,
				"stdout":   truncateStr(stdout.String(), 32000),
				"stderr":   truncateStr(stderr.String(), 16000),
			}, nil
		}
		if ee, ok := err.(*exec.ExitError); ok {
			exitCode = ee.ExitCode()
		} else {
			return nil, err
		}
	}
	return map[string]any{
		"ok":       exitCode == 0,
		"exitCode": exitCode,
		"stdout":   truncateStr(stdout.String(), 32000),
		"stderr":   truncateStr(stderr.String(), 16000),
	}, nil
}

func truncateStr(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return s[:max] + "\n...[truncated]"
}
