<script setup lang="ts">
/**
 * 发布页 /app:公开、不用登录。说明功能,给安卓安装包与网页版入口,展示更新日志。
 * 安装包信息读本站 /api/app/android/latest(没发包时回 404,只留网页版入口);
 * 更新日志是构建时打进来的仓库根目录 CHANGELOG.md,所以第一条就是这个站点正在跑的版本。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { encode } from 'uqr'
import {
  ArrowRight,
  Box,
  ChatDotRound,
  Clock,
  Connection,
  CopyDocument,
  Download,
  Link,
  MagicStick,
  PieChart,
  Scissor,
  Search,
  Share,
  Upload,
  VideoPlay,
} from '@element-plus/icons-vue'

import changelogSrc from '../../../CHANGELOG.md?raw'
import { useAuthStore } from '@/stores/auth'
import { parseChangelog } from '@/utils/changelog'
import { ANDROID_APK_URL, detectVisitor, fetchAndroidRelease, releaseDate } from '@/utils/appRelease'
import { formatBytes } from '@/utils/format'

const auth = useAuthStore()
const visitor = detectVisitor(navigator.userAgent, navigator.maxTouchPoints ?? 0)

const release = useQuery({
  queryKey: ['android-release'],
  queryFn: () => fetchAndroidRelease(),
  staleTime: 60_000,
})
const rel = computed(() => release.data.value ?? null)
/** 本站确实没发安卓包(404);请求失败时仍给下载按钮,固定链接自己会报错 */
const noRelease = computed(() => release.isSuccess.value && release.data.value === null)
const canDownload = computed(() => !noRelease.value && !visitor.ios && !visitor.harmonyNext)
const blockedInApp = computed(() => visitor.inAppBrowser !== null && canDownload.value)

const entries = parseChangelog(changelogSrc)
const webVersion = entries[0]?.version ?? ''
const SHOW_FIRST = 3
const showAll = ref(false)
const shownEntries = computed(() => (showAll.value ? entries : entries.slice(0, SHOW_FIRST)))
/** 安装包的版本不在这份日志里(App 比站点新,或日志漏写)时,才显示 latest.json 里的一句话说明 */
const releaseNotes = computed(() => {
  const r = rel.value
  if (!r?.notes || entries.some((e) => e.version === r.versionName)) return ''
  return r.notes
})

const webEntry = computed(() => (auth.accessToken ? '进入网盘' : '打开网页版'))
const footerText = webVersion ? `gopan 云盘 ${webVersion} · 自部署网盘` : 'gopan 云盘 · 自部署网盘'

// 电脑上给二维码:扫的是这一页而不是安装包,手机上能看到说明,微信扫了也有提示
const pageUrl = `${location.origin}/app`
const qr = (() => {
  const { size, data } = encode(pageUrl, { ecc: 'M', border: 2 })
  let d = ''
  data.forEach((row, y) => {
    for (let x = 0; x < size; x++) {
      if (!row[x]) continue
      let run = 1
      while (x + run < size && row[x + run]) run++
      d += `M${x} ${y}h${run}v1h-${run}z`
      x += run - 1
    }
  })
  return { size, d }
})()

const features = [
  { icon: Upload, title: '传得快、断了能续', desc: '大文件分片直传对象存储,同一份内容秒传;断网、关页面之后接着传。文件夹与多选打包下载' },
  { icon: VideoPlay, title: '直接看、接着看', desc: '图片、视频、音频、PDF、Office、Markdown 在线预览;外挂字幕,换台设备从上次的位置继续,音乐按文件夹连播' },
  { icon: Share, title: '分享与收集', desc: '分享文件和文件夹,可设密码与有效期,随时取消;开收集链接,别人不用注册就能往你的文件夹里传,收到了会通知你' },
  { icon: ChatDotRound, title: '传输助手', desc: '像聊天一样在电脑和手机之间发文字、传文件;手机上的「分享」直接发进来' },
  { icon: Search, title: '按内容搜', desc: '按 PDF、Word / Excel / PowerPoint 与文本里的文字找文件,中英文都行,PDF 直接翻到命中的那页' },
  { icon: Clock, title: '动态与撤回', desc: '每次改动都记下是谁、在哪台设备上做的;误删、误移能单条、整批或按来源撤回' },
  { icon: PieChart, title: '空间分析', desc: '大文件、重复文件、很久没动的文件与空文件夹一眼看清,勾选后清进回收站' },
  { icon: Scissor, title: '订阅与剪藏', desc: 'RSS、播客、GitHub Releases 有新内容自动下进来;网页正文连图片存成 Markdown' },
  { icon: Box, title: '离线下载与压缩包', desc: '给个链接,服务器在后台下好;zip / 7z / rar 不下载就能浏览、预览和解压' },
  { icon: Link, title: '直链与图床', desc: '任意文件一条永久公开地址;PicGo、Typora、ShareX 直接把图片传上来' },
  { icon: Connection, title: 'WebDAV', desc: 'Finder、Windows 资源管理器、rclone 与手机上的文件 App 都能挂载' },
  { icon: MagicStick, title: 'Agent 接入', desc: 'Claude 等 AI Agent 经你授权后帮你整理、上传、下载,权限分五档' },
]

const appExtras = [
  '上传下载在后台跑,锁屏、断网、杀掉 App 之后接着传',
  '相册、文件管理器里「分享」给 gopan,直接进传输助手;浏览器里分享网址就能剪藏',
  '系统「文件」App 的侧栏里有 gopan,别的应用选文件时也能直接选',
  '「我的 → 关于 → 检查更新」一键升级,下载后先校验再安装',
]

async function copySha() {
  const sha = rel.value?.sha256
  if (!sha) return
  try {
    await navigator.clipboard.writeText(sha)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败,请手动选中复制')
  }
}

function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const prevTitle = document.title
onMounted(async () => {
  document.title = 'gopan 云盘 · 安卓 App 与更新日志'
  // App「关于」里的「更新日志」带 #changelog 打开
  const id = location.hash.slice(1)
  if (id) {
    if (id === 'changelog') showAll.value = true
    // 安装包信息到了页面上方会多出几行,等它到了再滚,免得停在半路
    await release.suspense().catch(() => undefined)
    await nextTick()
    document.getElementById(id)?.scrollIntoView({ block: 'start' })
  }
})
onBeforeUnmount(() => {
  document.title = prevTitle
})
</script>

<template>
  <div class="release-page">
    <div v-if="blockedInApp" class="inapp-tip" role="alert">
      {{ visitor.inAppBrowser === 'wechat' ? '微信' : 'QQ' }}里不能下载安装包:点右上角「···」,选「在浏览器打开」
      <span class="inapp-arrow" aria-hidden="true">↗</span>
    </div>

    <header class="topbar">
      <router-link to="/app" class="brand">
        <svg class="logo" viewBox="0 0 64 64" aria-hidden="true">
          <rect width="64" height="64" rx="14" fill="#409eff" />
          <path d="M14 22h13l4 5h19a3 3 0 0 1 3 3v15a3 3 0 0 1-3 3H14a3 3 0 0 1-3-3V25a3 3 0 0 1 3-3z" fill="#fff" />
        </svg>
        <span>gopan</span>
      </router-link>
      <nav class="topnav">
        <a href="#features" class="only-desktop" @click.prevent="scrollTo('features')">功能</a>
        <a href="#install" class="only-desktop" @click.prevent="scrollTo('install')">安装</a>
        <a href="#changelog" @click.prevent="scrollTo('changelog')">更新日志</a>
        <router-link to="/drive" class="topnav-web">{{ webEntry }}</router-link>
      </nav>
    </header>

    <main>
      <section class="hero">
        <div class="hero-text">
          <h1>gopan 云盘</h1>
          <p class="lead">放在自己服务器上的网盘。文件、照片、影音、网页剪藏都在自己手里,网页、安卓 App、WebDAV 与 AI Agent 都能用。</p>

          <div class="cta">
            <el-button
              v-if="canDownload"
              tag="a"
              :href="blockedInApp ? undefined : ANDROID_APK_URL"
              type="primary"
              size="large"
              :icon="Download"
              :disabled="blockedInApp"
              data-test="download-apk"
            >
              下载安卓 App
            </el-button>
            <router-link v-slot="{ href, navigate }" to="/drive" custom>
              <el-button tag="a" :href="href" size="large" :type="canDownload ? 'default' : 'primary'" @click="navigate">
                {{ webEntry }}<el-icon class="el-icon--right"><ArrowRight /></el-icon>
              </el-button>
            </router-link>
          </div>

          <p v-if="canDownload && rel" class="meta">
            安卓 App {{ rel.versionName }} · {{ formatBytes(rel.size) }}
            <template v-if="releaseDate(rel.publishedAt)"> · {{ releaseDate(rel.publishedAt) }} 发布</template>
            · 需要 Android 8.0 及以上
          </p>
          <p v-else-if="canDownload && release.isPending.value" class="meta">正在读取安装包信息…</p>
          <p v-if="releaseNotes" class="meta">{{ releaseNotes }}</p>
          <p v-if="webVersion" class="meta">网页版 {{ webVersion }},电脑和手机浏览器打开就能用</p>

          <el-alert v-if="visitor.ios" type="info" :closable="false" class="hero-note" title="iPhone / iPad 没有 App">
            用 Safari 打开网页版,点底部的「分享」→「添加到主屏幕」,就能像 App 一样从桌面打开;也能用 WebDAV 客户端挂载。
          </el-alert>
          <el-alert v-else-if="visitor.harmonyNext" type="info" :closable="false" class="hero-note" title="纯血鸿蒙装不了安卓安装包">
            请用网页版:浏览器菜单里「添加到桌面」后就能从桌面打开。
          </el-alert>
          <el-alert v-else-if="noRelease" type="info" :closable="false" class="hero-note" title="这个站点没有提供安卓安装包">
            网页版在手机浏览器里同样好用,也能「添加到主屏幕」。
          </el-alert>
        </div>

        <div v-if="visitor.desktop && canDownload" class="qr-card">
          <svg class="qr" :viewBox="`0 0 ${qr.size} ${qr.size}`" shape-rendering="crispEdges" role="img" aria-label="本页二维码">
            <rect :width="qr.size" :height="qr.size" fill="#fff" />
            <path :d="qr.d" fill="#1f2329" />
          </svg>
          <p>手机扫码打开这一页,<br />在手机上下载安装</p>
        </div>
      </section>

      <section id="features" class="section">
        <h2>能做什么</h2>
        <div class="feature-grid">
          <div v-for="f in features" :key="f.title" class="feature">
            <el-icon class="feature-icon"><component :is="f.icon" /></el-icon>
            <h3>{{ f.title }}</h3>
            <p>{{ f.desc }}</p>
          </div>
        </div>
      </section>

      <section id="install" class="section">
        <h2>安卓 App</h2>
        <div class="install">
          <div class="install-col">
            <h3>比网页版多了什么</h3>
            <ul class="ticks">
              <li v-for="x in appExtras" :key="x">{{ x }}</li>
            </ul>
          </div>
          <div class="install-col">
            <h3>安装与升级</h3>
            <ol class="steps">
              <li>
                <a v-if="canDownload" :href="ANDROID_APK_URL">下载安装包</a><span v-else>下载安装包</span>,点开安装。第一次安装时系统会问要不要允许浏览器「安装未知应用」,允许即可。
              </li>
              <li>打开 gopan,用网页版的同一个账号登录。</li>
              <li>以后升级:「我的 → 关于 → 检查更新」,登录状态和数据都保留;也可以重新下载安装包覆盖安装。</li>
            </ol>
            <p class="muted">锁屏后传输经常中断的话,在系统设置里允许 gopan 后台运行、关掉它的电池优化。</p>
            <p v-if="rel" class="sha">
              安装包 SHA-256
              <code>{{ rel.sha256 }}</code>
              <el-button link :icon="CopyDocument" title="复制" @click="copySha" />
            </p>
          </div>
        </div>
      </section>

      <section id="changelog" class="section">
        <h2>更新日志</h2>
        <p class="muted">网页版与安卓 App 用同一个版本号一起发布。</p>
        <ol class="timeline">
          <li v-for="(e, i) in shownEntries" :key="e.version" class="entry">
            <div class="entry-head">
              <span class="entry-version">{{ e.version }}</span>
              <span v-if="e.title" class="entry-title">{{ e.title }}</span>
              <el-tag v-if="i === 0" size="small" effect="plain" round>最新</el-tag>
              <span class="entry-date">{{ e.date }}</span>
            </div>
            <p v-for="(para, j) in e.intro" :key="`p${j}`" class="entry-intro">
              <template v-for="(seg, k) in para" :key="k">
                <strong v-if="seg.kind === 'strong'">{{ seg.text }}</strong>
                <code v-else-if="seg.kind === 'code'">{{ seg.text }}</code>
                <template v-else>{{ seg.text }}</template>
              </template>
            </p>
            <ul class="entry-items">
              <li v-for="(item, j) in e.items" :key="j">
                <template v-for="(seg, k) in item" :key="k">
                  <strong v-if="seg.kind === 'strong'">{{ seg.text }}</strong>
                  <code v-else-if="seg.kind === 'code'">{{ seg.text }}</code>
                  <template v-else>{{ seg.text }}</template>
                </template>
              </li>
            </ul>
          </li>
        </ol>
        <el-button v-if="!showAll && entries.length > SHOW_FIRST" class="more" @click="showAll = true">
          查看更早的 {{ entries.length - SHOW_FIRST }} 个版本
        </el-button>
      </section>
    </main>

    <footer class="footer">
      <span>{{ footerText }}</span>
      <router-link to="/drive">{{ webEntry }}</router-link>
    </footer>
  </div>
</template>

<style scoped>
.release-page {
  min-height: 100%;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  /* 横向不能滚:二维码、长 SHA-256、代码段都要收在视口里 */
  overflow-x: hidden;
}
.inapp-tip {
  position: sticky;
  top: 0;
  z-index: 10;
  padding: calc(10px + var(--sat)) 44px 10px 16px;
  background: #1f2329;
  color: #fff;
  font-size: 14px;
  line-height: 1.5;
}
.inapp-arrow {
  position: absolute;
  top: calc(4px + var(--sat));
  right: 14px;
  font-size: 24px;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  max-width: 1040px;
  margin: 0 auto;
  padding: calc(14px + var(--sat)) 24px 14px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: inherit;
  font-size: 18px;
  font-weight: 600;
  text-decoration: none;
}
.logo {
  width: 28px;
  height: 28px;
}
.topnav {
  display: flex;
  align-items: center;
  gap: 20px;
  font-size: 14px;
}
.topnav a {
  color: var(--el-text-color-regular);
  text-decoration: none;
}
.topnav a:hover {
  color: var(--el-color-primary);
}
.topnav .topnav-web {
  color: var(--el-color-primary);
  font-weight: 500;
}
main {
  max-width: 1040px;
  margin: 0 auto;
  padding: 0 24px;
}
.hero {
  display: flex;
  align-items: center;
  gap: 40px;
  padding: 48px 0 40px;
}
.hero-text {
  flex: 1;
  min-width: 0;
}
h1 {
  margin: 0;
  font-size: 40px;
  line-height: 1.2;
}
.lead {
  max-width: 560px;
  margin: 16px 0 28px;
  color: var(--el-text-color-regular);
  font-size: 17px;
  line-height: 1.7;
}
.cta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.cta .el-button {
  margin: 0;
  text-decoration: none;
}
.meta {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.hero-note {
  max-width: 560px;
  margin-top: 20px;
}
.qr-card {
  flex: none;
  padding: 20px 20px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: #fff;
  text-align: center;
  box-shadow: var(--el-box-shadow-lighter);
}
.qr {
  display: block;
  width: 168px;
  height: 168px;
}
.qr-card p {
  margin: 10px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.section {
  padding: 36px 0;
  border-top: 1px solid var(--el-border-color-lighter);
  scroll-margin-top: 12px;
}
h2 {
  margin: 0 0 20px;
  font-size: 24px;
}
h3 {
  margin: 0 0 8px;
  font-size: 16px;
}
.muted {
  margin: 0 0 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.feature-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px;
}
.feature {
  padding: 18px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.feature-icon {
  margin-bottom: 10px;
  color: var(--el-color-primary);
  font-size: 22px;
}
.feature p {
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.65;
}
.install {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 32px;
}
.install-col {
  min-width: 0;
}
.ticks,
.steps {
  margin: 0 0 16px;
  padding-left: 20px;
  line-height: 1.8;
  font-size: 14px;
}
.ticks li::marker {
  color: var(--el-color-primary);
}
.steps a {
  color: var(--el-color-primary);
}
.sha {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.sha code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--el-text-color-regular);
  font-size: 12px;
}
.timeline {
  max-width: 820px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.entry {
  position: relative;
  padding: 0 0 28px 24px;
  border-left: 2px solid var(--el-border-color-lighter);
}
.entry::before {
  content: '';
  position: absolute;
  top: 6px;
  left: -7px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--el-bg-color);
  border: 2px solid var(--el-color-primary);
  box-sizing: border-box;
}
.entry:first-child::before {
  background: var(--el-color-primary);
}
.entry:last-child {
  padding-bottom: 4px;
}
.entry-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 10px;
  margin-bottom: 8px;
}
.entry-version {
  font-size: 18px;
  font-weight: 600;
}
.entry-title {
  font-size: 15px;
  font-weight: 500;
}
.entry-date {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.entry-intro {
  margin: 0 0 8px;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 1.7;
}
.entry-items {
  margin: 0;
  padding-left: 18px;
  list-style: disc;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 1.75;
}
.entry-items li + li {
  margin-top: 4px;
}
.entry-items strong {
  color: var(--el-text-color-primary);
}
.entry code,
.entry-intro code {
  padding: 0 4px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-size: 0.92em;
}
.more {
  margin-left: 24px;
}
.footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  max-width: 1040px;
  margin: 0 auto;
  padding: 20px 24px calc(24px + var(--sab));
  border-top: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.footer a {
  color: var(--el-color-primary);
  text-decoration: none;
}

@media (max-width: 767px) {
  .topbar {
    padding: calc(12px + var(--sat)) 16px 12px;
  }
  .topnav {
    gap: 16px;
  }
  main {
    padding: 0 16px;
  }
  .hero {
    padding: 24px 0 28px;
  }
  h1 {
    font-size: 30px;
  }
  .lead {
    margin: 12px 0 22px;
    font-size: 15px;
  }
  .cta .el-button {
    flex: 1 1 140px;
  }
  .section {
    padding: 28px 0;
  }
  h2 {
    font-size: 20px;
    margin-bottom: 16px;
  }
  .feature-grid {
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }
  .feature {
    padding: 14px;
  }
  .install {
    grid-template-columns: 1fr;
    gap: 20px;
  }
  .entry {
    padding-left: 18px;
  }
  .more {
    margin-left: 18px;
  }
  .footer {
    padding: 16px 16px calc(20px + var(--sab));
  }
}
</style>
