# 本地文件上传

只有上传本地文件时才需要本流程。Agent 须能读该文件、运行 Python 3 并访问预签名 HTTP 地址；只有 MCP 调用能力不能独立搬运本地文件。已有 URL 可用 `fetch_url` 让服务器下载，无需先下到本机。

## 定位脚本

`scripts/upload.py` 相对本 skill 目录，不相对用户当前工作目录。根据加载的 `SKILL.md` 位置找到脚本，命令中使用脚本、本地文件与计划文件的绝对路径并正确引用。以下占位路径执行前须替换，不要求切换工作目录：

```text
python "<skill目录>/scripts/upload.py" hash "<本地文件绝对路径>"
python "<skill目录>/scripts/upload.py" put "<本地文件绝对路径>" "<计划JSON绝对路径>"
```

脚本只用 Python 标准库，只负责 hash 与 HTTP PUT，不调用 MCP、不读取 OAuth 或 API Key。`put` 的计划也可用 `-` 从 stdin 读取。

## 发起与完成

1. `hash` 输出 `name`、`size`、`sha256`、`content_type`。SHA 必须是实际计算值，计算后保持文件不变。
2. 调 `prepare_upload`，传上述字段、用户指定的 `parent_path`（或 `parent_id`）和本次逻辑上传唯一的 `idempotency_key`。同一请求重试沿用此键；改变文件或目标属于新请求。
3. 检查返回：`mode: "instant"` 且 `status: "ready"` 时已秒传完成，核对节点即可。`single_put` 或 `multipart` 且状态为 `uploading` 时，将实际返回保存为 UTF-8 JSON 交给 `put`。支持原始工具 JSON、含 `structuredContent` 或文本内容的 MCP 结果，以及外层 `result`，无需手工拼 URL。重试返回已完成或处理中的会话时，按状态核对或等待，不重复上传字节。
4. `put` 返回 `ok: true` 只表示本次 PUT 成功，不代表文件已入盘。分片若仍有 `missing_parts`，用 `get_upload_parts(upload_id, first_part, limit=100)` 获取下一批地址，保存新计划再次 `put`，直到缺失数为零。
5. 字节齐全后调 `complete_upload(upload_id)`，再 `wait_upload(upload_id)`。只有 `ready` 才完成；等待超时返回非终态时可继续等。`failed` 看 `failure`，`aborted` 不再重试该会话。
6. 回报实际保存路径；返回节点有 SHA 时与本地计算值核对。不为证明上传成功另建公开分享。

默认自动选择 64 MiB 以内单 PUT、更大文件分片（通常 16 MiB / 片）；以服务端返回的 `mode`、`part_size`、`total_parts` 为准。多文件逐个走完即可，同账号最多 20 个活跃上传；`storage_status` 有读权限才可调用，不是上传的前置条件。

## 断线、过期与取消

- 脚本对网络错误和服务端 5xx 有有限次数退避重试，4xx 立即停止。HTTP 403 可能是过期，也可能是签名或存储配置错误，不能一律归因于凭据。
- 地址过期或断线：用 `get_upload_parts` 查询服务端已收到的分片和刷新地址，再将新计划交给脚本。单 PUT 也可通过该工具刷新 `put_url`。刷新后仍失败时报告错误，停止反复刷新或新建上传。
- MCP 初始化响应丢失：用相同参数和原 `idempotency_key` 重试 `prepare_upload`，取回原会话；不要为一次网络超时换键创建多个会话。
- 文件在计算 hash 后变化：用 `abort_upload` 取消未完成会话，重新计算 hash，按用户原目标以新键发起新上传。
- 用户取消时用 `abort_upload`，不再调 `complete_upload`。若会话已完成，取消上传不等于删除文件，另行按用户要求处理。
