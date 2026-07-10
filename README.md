# gopan

自部署云盘。Go + GraphQL + Postgres + MinIO 后端,Vue 3 前端,单二进制交付。

当前阶段:**M1 完成**(认证 + 目录树 + 前后端类型链)。M2 传输 → M3 预览 → M4 分享,见 doc/08 末尾。

## 快速开始

```bash
make dev-env      # compose 起 postgres/minio/gotenberg
make dev-server   # 后端 :8080(自动跑迁移)
make dev-web      # 前端 :5173(proxy → 8080)

make gen          # 改了 schema.graphqls / db/queries 后重新生成三件套
make test         # go test + vet(集成测试需 TEST_DB_URL)
make build        # 前端构建 + embed → server/gopan 单二进制
```

生产运行只需要 `server/gopan` 二进制 + `GOPAN_DB_URL` + `GOPAN_JWT_SECRET`(≥32 字节),配置项见 doc/08。

## 设计文档

全部设计文档在 [doc/](./doc/):

| 文档 | 内容 |
|---|---|
| [01 · 需求与范围](./doc/01-需求与范围.md) | v1 功能清单、明确不做的、非功能要求、术语表 |
| [02 · 技术选型](./doc/02-技术选型.md) | 前后端选型表、不用什么及理由 |
| [03 · 架构与核心流程](./doc/03-架构与核心流程.md) | 组件图;上传(秒传/分片/断点)、下载、预览、分享、GC 流程 |
| [04 · 数据模型](./doc/04-数据模型.md) | Postgres 全量 DDL、不变量、容量估算 |
| [05 · GraphQL 设计](./doc/05-GraphQL设计.md) | 完整 SDL(接口唯一事实源)、约定、resolver 要点 |
| [06 · 认证与安全](./doc/06-认证与安全.md) | 双 token 细节、访客授权、预签名边界、限速 |
| [07 · 前端设计](./doc/07-前端设计.md) | 路由/组件/store 划分、上传器状态机、token 处理层 |
| [08 · 项目结构与部署](./doc/08-项目结构与部署.md) | 仓库布局、开发流、compose、运维、里程碑 |

## 读法

想看全貌:01 → 02 → 03。要动手:04、05 是实现的两份合同,08 末尾是里程碑顺序(M1 骨架 → M2 传输 → M3 预览 → M4 分享)。
