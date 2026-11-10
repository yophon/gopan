# gopan Agent Skill

给 Claude Code、Codex 等 Agent 用的 Agent Skill：教它用好 gopan 的远程 MCP——上传怎么走、批量改动先预览、改错了怎么办。

它不替代 MCP 接入：先按 [`docs/MCP.md`](../../docs/MCP.md) 的「客户端接入」把 `https://<你的站点>/mcp` 加进客户端，再装这个 Skill。

## 里面有什么

| 文件 | 作用 |
|---|---|
| `SKILL.md` | 给 Agent 读的说明：权限与目录范围、找文件读文档、写入整理、分享、我的设备与错误恢复 |
| `references/upload.md` | 本地上传时按需读取：脚本定位、传输与完成、断线续传、过期与取消 |
| `scripts/upload.py` | 只用 Python 标准库的上传脚本：算 sha256（争取秒传）；按 `prepare_upload` / `get_upload_parts` 的返回做单次 PUT 或分片 PUT，失败重试，提示下一步 |

## 安装

把整个 `skills/gopan` 目录复制到客户端的 skills 目录，目录名保持 `gopan`：

- Claude Code：个人级 `~/.claude/skills/gopan/`，或项目级 `.claude/skills/gopan/`
- 其他支持 Agent Skills 的客户端：放到它约定的 skills 目录（以该客户端文档为准）

## 上传脚本单独用

从任意工作目录运行时，用实际安装目录定位脚本，文件与计划也使用绝对路径。下列路径需替换：

```text
python "<skill目录>/scripts/upload.py" hash "<文件绝对路径>"
# 用上面的结果调 prepare_upload,把返回的 JSON 存成 plan.json
python "<skill目录>/scripts/upload.py" put "<文件绝对路径>" "<计划JSON绝对路径>"
# 然后调 complete_upload + wait_upload
```

2026-11-06 在测试实例上实测过三条路径：700 KiB 单次 PUT、34 MiB 分片（分两批取分片地址续传）、同内容带 sha256 秒传，`wait_upload` 均到 `ready`，网盘里的 sha256 与本地一致。
