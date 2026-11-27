package main

import (
	"encoding/base64"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const maxTransferBytes = 32 * 1024 * 1024

func expandPath(raw string) string {
	p := strings.TrimSpace(raw)
	if p == "" || p == "~" {
		h, _ := os.UserHomeDir()
		return h
	}
	if strings.HasPrefix(p, "~/") || strings.HasPrefix(p, `~\`) {
		h, _ := os.UserHomeDir()
		return filepath.Join(h, p[2:])
	}
	return p
}

func resolveCanonicalPath(p string) string {
	abs, err := filepath.Abs(p)
	if err != nil {
		abs = filepath.Clean(p)
	}
	if real, err := filepath.EvalSymlinks(abs); err == nil {
		return filepath.Clean(real)
	}
	// Resolve nearest existing ancestor for non-existent target paths.
	cur := abs
	var tail []string
	for {
		parent := filepath.Dir(cur)
		if parent == cur {
			return abs
		}
		tail = append([]string{filepath.Base(cur)}, tail...)
		cur = parent
		if real, err := filepath.EvalSymlinks(cur); err == nil {
			parts := append([]string{real}, tail...)
			return filepath.Clean(filepath.Join(parts...))
		}
	}
}

func isWithinDir(target, dir string) bool {
	if dir == "" {
		return false
	}
	rel, err := filepath.Rel(dir, target)
	if err != nil {
		return false
	}
	return rel == "." || (!strings.HasPrefix(rel, ".."+string(filepath.Separator)) && rel != "..")
}

func isProtectedAgentConfigPath(p string, writeOp bool) bool {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return false
	}
	canonTarget := resolveCanonicalPath(p)
	agentDir := resolveCanonicalPath(filepath.Join(home, ".solbraid-pc-agent"))
	if !isWithinDir(canonTarget, agentDir) {
		return false
	}
	if writeOp {
		return true
	}
	base := strings.ToLower(filepath.Base(canonTarget))
	return base == "config.json" || base == "server.key"
}

func isDangerousDeleteTarget(p string) bool {
	canon := resolveCanonicalPath(p)
	if filepath.Dir(canon) == canon {
		return true
	}
	if home, err := os.UserHomeDir(); err == nil && home != "" {
		if canon == resolveCanonicalPath(home) {
			return true
		}
	}
	return false
}

func handleFilesAction(params map[string]any) (map[string]any, error) {
	act := strings.ToLower(strings.TrimSpace(paramStr(params, "action", "list")))
	target := expandPath(paramStr(params, "path", "~"))
	if target == "" {
		return nil, fmt.Errorf("缺少 path 参数")
	}
	isWriteAct := act == "write" || act == "write_base64" || act == "mkdir" || act == "move" || act == "delete"
	if isProtectedAgentConfigPath(target, isWriteAct) {
		return nil, fmt.Errorf("安全限制：禁止经由文件接口访问代理敏感配置目录 (~/.solbraid-pc-agent)")
	}

	switch act {
	case "list":
		entries, err := os.ReadDir(target)
		if err != nil {
			return nil, err
		}
		limit := paramInt(params, "limit", 200)
		if limit < 10 {
			limit = 10
		}
		if limit > 1000 {
			limit = 1000
		}
		items := make([]map[string]any, 0, len(entries))
		for _, e := range entries {
			info, err := e.Info()
			var size int64
			var mtime int64
			if err == nil {
				size = info.Size()
				mtime = info.ModTime().UnixMilli()
			}
			items = append(items, map[string]any{
				"name":  e.Name(),
				"path":  filepath.Join(target, e.Name()),
				"isDir": e.IsDir(),
				"size":  size,
				"mtime": mtime,
			})
		}
		sort.Slice(items, func(i, j int) bool {
			di := items[i]["isDir"].(bool)
			dj := items[j]["isDir"].(bool)
			if di != dj {
				return di
			}
			return strings.ToLower(items[i]["name"].(string)) < strings.ToLower(items[j]["name"].(string))
		})
		if len(items) > limit {
			items = items[:limit]
		}
		return map[string]any{"ok": true, "path": target, "entries": items}, nil

	case "stat":
		st, err := os.Stat(target)
		if err != nil {
			return nil, err
		}
		return map[string]any{
			"ok":    true,
			"path":  target,
			"isDir": st.IsDir(),
			"size":  st.Size(),
			"mtime": st.ModTime().UnixMilli(),
		}, nil

	case "read":
		st, err := os.Stat(target)
		if err != nil {
			return nil, err
		}
		if st.Size() > 2*1024*1024 {
			return nil, fmt.Errorf("文本读取上限 2MB，大文件请使用 pull / read_base64")
		}
		raw, err := os.ReadFile(target)
		if err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "path": target, "size": len(raw), "content": string(raw)}, nil

	case "read_base64":
		st, err := os.Stat(target)
		if err != nil {
			return nil, err
		}
		if st.Size() > maxTransferBytes {
			return nil, fmt.Errorf("单次传输上限 32MB")
		}
		raw, err := os.ReadFile(target)
		if err != nil {
			return nil, err
		}
		return map[string]any{
			"ok":     true,
			"path":   target,
			"size":   len(raw),
			"base64": base64.StdEncoding.EncodeToString(raw),
		}, nil

	case "write":
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return nil, err
		}
		content := []byte(paramStr(params, "content", ""))
		if err := os.WriteFile(target, content, 0o644); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "path": target, "bytes": len(content)}, nil

	case "write_base64":
		raw, err := base64.StdEncoding.DecodeString(paramStr(params, "base64", ""))
		if err != nil {
			return nil, fmt.Errorf("base64 解码失败: %w", err)
		}
		if len(raw) > maxTransferBytes {
			return nil, fmt.Errorf("单次写入上限 32MB")
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return nil, err
		}
		if err := os.WriteFile(target, raw, 0o644); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "path": target, "bytes": len(raw)}, nil

	case "mkdir":
		if err := os.MkdirAll(target, 0o755); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "path": target}, nil

	case "move":
		dst := expandPath(paramStr(params, "dest", ""))
		if dst == "" {
			return nil, fmt.Errorf("move 需提供 dest")
		}
		if isProtectedAgentConfigPath(dst, true) {
			return nil, fmt.Errorf("安全限制：禁止移动文件到代理敏感配置目录 (~/.solbraid-pc-agent)")
		}
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			return nil, err
		}
		if err := os.Rename(target, dst); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "from": target, "to": dst}, nil

	case "delete":
		if isDangerousDeleteTarget(target) {
			return nil, fmt.Errorf("安全限制：禁止删除文件系统根目录或用户主目录")
		}
		if err := os.RemoveAll(target); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "deleted": target}, nil

	case "search":
		q := strings.ToLower(strings.TrimSpace(paramStr(params, "query", "")))
		if q == "" {
			return nil, fmt.Errorf("search 需提供 query")
		}
		limit := paramInt(params, "limit", 60)
		if limit < 5 {
			limit = 5
		}
		if limit > 300 {
			limit = 300
		}
		var hits []map[string]any
		_ = filepath.WalkDir(target, func(p string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if d.IsDir() && (d.Name() == ".git" || d.Name() == "node_modules" || d.Name() == "$RECYCLE.BIN") {
				return filepath.SkipDir
			}
			if strings.Contains(strings.ToLower(d.Name()), q) {
				hits = append(hits, map[string]any{"path": p, "name": d.Name(), "isDir": d.IsDir()})
				if len(hits) >= limit {
					return fs.SkipAll
				}
			}
			return nil
		})
		return map[string]any{"ok": true, "root": target, "query": q, "matches": hits}, nil

	default:
		return nil, fmt.Errorf("未知文件动作: %s", act)
	}
}
