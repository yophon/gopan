# gopan Agent Skill

给 Claude Code、Codex 等 Agent 用的 Agent Skill：教它用好 gopan 的远程 MCP——上传怎么走、批量改动先预览、改错了怎么办。

它不替代 MCP 接入：先按 [`docs/MCP.md`](../../docs/MCP.md) 的「客户端接入」把 `https://<你的站点>/mcp` 加进客户端，再装这个 Skill。

## 里面有什么

| 文件 | 作用 |
|---|---|
| `SKILL.md` | 给 Agent 读的说明：连接自检、路径与 id、读写、整理、上传、下载、分享、规矩、出错了怎么办 |
| `scripts/upload.py` | 只用 Python 标准库的上传脚本：算 sha256（争取秒传）；按 `prepare_upload` / `get_upload_parts` 的返回做单次 PUT 或分片 PUT，失败重试，提示下一步 |

## 安装

把整个 `skills/gopan` 目录复制到客户端的 skills 目录，目录名保持 `gopan`：

- Claude Code：个人级 `~/.claude/skills/gopan/`，或项目级 `.claude/skills/gopan/`
- 其他支持 Agent Skills 的客户端：放到它约定的 skills 目录（以该客户端文档为准）

## 上传脚本单独用

```bash
python scripts/upload.py hash ./报告.pdf          # → {"name","size","sha256","content_type"}
# 用上面的结果调 prepare_upload,把返回的 JSON 存成 plan.json
python scripts/upload.py put ./报告.pdf plan.json # 单次 PUT 或分片 PUT
# 然后调 complete_upload + wait_upload
```

2026-11-06 在测试实例上实测过三条路径：700 KiB 单次 PUT、34 MiB 分片（分两批取分片地址续传）、同内容带 sha256 秒传，`wait_upload` 均到 `ready`，网盘里的 sha256 与本地一致。
