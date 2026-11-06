# 跨平台构建:web/go 两个构建阶段钉在 BUILDPLATFORM(构建机原生架构)上跑,
# 只有最终运行时层是 TARGETPLATFORM。这样 arm64 Mac 上产 amd64 镜像不用 QEMU 模拟构建,
# 也就能把镜像整个 save 到小内存服务器 load——那种机器上 vite + go build 会 OOM。
# ---- 前端构建(产物是纯 JS,与架构无关)----
FROM --platform=$BUILDPLATFORM node:22-slim AS web
RUN corepack enable
WORKDIR /src/web
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
# codegen 产物已提交进仓库,这里只需 vite 构建;schema 供 codegen.ts 引用路径存在即可
COPY server/graph/schema.graphqls /src/server/graph/schema.graphqls
# 发布页 /app 的更新日志在仓库根目录,构建时打进前端(web/src/views/AppReleaseView.vue)
COPY CHANGELOG.md /src/CHANGELOG.md
COPY web/ ./
RUN pnpm build

# ---- 后端构建(Go 原生交叉编译到 TARGETARCH,不走 QEMU)----
FROM --platform=$BUILDPLATFORM golang:1.26-bookworm AS build
ARG GOPROXY=https://proxy.golang.org,direct
ARG TARGETARCH
ENV GOPROXY=${GOPROXY}
WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
COPY --from=web /src/web/dist ./cmd/gopan/dist
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build -ldflags='-s -w' -o /out/gopan ./cmd/gopan

# ---- 运行层:debian-slim + 静态 ffmpeg(apt 版依赖树 ~500MB,静态单文件 ~110MB)----
# 单独成一个阶段,只在系统依赖变化时才变。Dockerfile.prebuilt 以它(gopan-runtime:<版本>)为底
# 只叠一层二进制。改了这一段(加系统包、升 ffmpeg / qjs),要把 Dockerfile.prebuilt 里的运行层版本号加一。
FROM mwader/static-ffmpeg:7.1 AS ffmpeg

FROM debian:bookworm-slim AS runtime
# poppler-utils:内容搜索提取 PDF 文字(pdftotext);poppler-data:中文 PDF 的 CMap(doc/20)
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl poppler-utils poppler-data \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 gopan
# 助手在服务器上运行(doc/34):quickjs-ng 的 qjs(静态编译,每一轮起一个进程);固定版本,sha256 对不上就不装
ARG QJS_VERSION=v0.17.0
ARG QJS_SHA256=0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5
RUN curl -fsSL -o /usr/local/bin/qjs "https://github.com/quickjs-ng/quickjs/releases/download/${QJS_VERSION}/qjs-linux-x86_64" \
    && echo "${QJS_SHA256}  /usr/local/bin/qjs" | sha256sum -c - \
    && chmod 755 /usr/local/bin/qjs
COPY --from=ffmpeg /ffmpeg /ffprobe /usr/local/bin/
USER gopan
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
    CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["gopan"]

# ---- 最终镜像:运行层 + 一层二进制 ----
FROM runtime
COPY --from=build /out/gopan /usr/local/bin/gopan
