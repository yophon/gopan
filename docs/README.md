# Gopan MCP

Gopan 提供远程 Streamable HTTP MCP endpoint，让 Agent 在不安装本地 bridge、
不打开上传页面的情况下管理云盘。MCP 传控制信息，文件字节由 Agent 使用自身的
HTTP 或 shell 工具通过预签名 URL 直接传输。

默认鉴权是 OAuth 2.1 Authorization Code + PKCE；Headless Agent/CI 可使用
用户在“Agent 接入”中创建的细粒度 API Key。MCP、OAuth server 与主服务同进程，
部署不增加可执行文件、容器或用户端安装步骤。

完整设计、工具合同与接入方式见 [MCP Agent 工具](./MCP.md)。
