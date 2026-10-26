<script setup lang="ts">
/**
 * 发布页 /app:公开、不用登录。给安卓安装包与网页版入口,讲功能,展示更新日志。
 * 安装包信息读本站 /api/app/android/latest(没发包时回 404,只留网页版入口);Windows 桌面端读 /api/app/desktop/info(doc/29);
 * 更新日志是构建时打进来的仓库根目录 CHANGELOG.md,所以第一条就是这个站点正在跑的版本。
 * 配色与文件夹记号在 styles/theme.css;页面上的手机画面是 components/release/ReleasePhone.vue。
 */
import { computed, h, nextTick, onBeforeUnmount, onMounted, ref, type FunctionalComponent } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { qrPath } from '@/utils/qr'

import changelogSrc from '../../../CHANGELOG.md?raw'
import BrandLogo from '@/components/BrandLogo.vue'
import ReleasePhone, { type PhoneScreen } from '@/components/release/ReleasePhone.vue'
import { useAuthStore } from '@/stores/auth'
import { parseChangelog, type ChangelogEntry, type Inline } from '@/utils/changelog'
import {
  ANDROID_APK_URL,
  DESKTOP_EXE_URL,
  dayLabel,
  detectVisitor,
  fetchAndroidRelease,
  fetchDesktopRelease,
} from '@/utils/appRelease'
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
const webEntry = computed(() => (auth.accessToken ? '进入网盘' : '打开网页版'))

// Windows 桌面端:手机上不显示;Windows 电脑上首屏换成下它
const desktopRelease = useQuery({
  queryKey: ['desktop-release'],
  queryFn: () => fetchDesktopRelease(),
  staleTime: 60_000,
  enabled: visitor.desktop,
})
const desk = computed(() => desktopRelease.data.value ?? null)
const showDesktop = computed(() => visitor.desktop && desk.value !== null)
const heroDesktop = computed(() => showDesktop.value && visitor.windows)
const exeSize = computed(() => {
  const [num = '', unit = ''] = formatBytes(desk.value?.size).split(' ')
  return { num, unit }
})

/** 「5.4 MB」拆成数字和单位,数字放大 */
const apkSize = computed(() => {
  const [num = '', unit = ''] = formatBytes(rel.value?.size).split(' ')
  return { num, unit }
})

// ---------- 更新日志 ----------

const entries = parseChangelog(changelogSrc)
const latest = entries[0] ?? null
/** 默认列出的版本数(含最新一版);旧版本只列标题,点开看条目 */
const SHOW_FIRST = 5
const showAll = ref(false)
const older = computed(() => entries.slice(1, showAll.value ? undefined : SHOW_FIRST))
const opened = ref(new Set<string>())

function toggleEntry(version: string) {
  const next = new Set(opened.value)
  if (next.has(version)) next.delete(version)
  else next.add(version)
  opened.value = next
}

const plain = (line: Inline[] | undefined) => (line ?? []).map((seg) => seg.text).join('')
/** 没写标题的版本(小修复)拿第一条当标题,正文里就不再重复它 */
const entryTitle = (e: ChangelogEntry) => e.title || plain(e.items[0])
const entryItems = (e: ChangelogEntry) => (e.title ? e.items : e.items.slice(1))
const hasDetail = (e: ChangelogEntry) => e.intro.length > 0 || entryItems(e).length > 0

const RichLine: FunctionalComponent<{ line: Inline[] }> = (props) =>
  props.line.map((seg) => (seg.kind === 'strong' ? h('strong', seg.text) : seg.kind === 'code' ? h('code', seg.text) : seg.text))

// ---------- 静态内容 ----------

const slogans = ['原图原画', '没有广告', '不用开会员', '删错能撤回']

const steps: { screen: PhoneScreen; n: string; title: string; items: [string, string][] }[] = [
  {
    screen: 'preview',
    n: '01',
    title: '上传与预览',
    items: [
      ['断了能续', '断网、关页面、锁屏之后接着传'],
      ['接着看', '换台设备，从上次看到的那一秒继续'],
      ['传输助手', '像聊天一样在电脑和手机之间传文件'],
      ['离线下载与压缩包', '给个链接后台下好，压缩包不下载就能看'],
    ],
  },
  {
    screen: 'activity',
    n: '02',
    title: '整理与找回',
    items: [
      ['动态与撤回', '单条、整批或按设备撤回'],
      ['按内容搜', 'PDF 和 Office 里的字也能搜到'],
      ['空间分析', '大文件、重复文件一眼看清，勾选就清'],
      ['订阅与剪藏', 'RSS、播客自动下载，网页存成 Markdown'],
    ],
  },
  {
    screen: 'share',
    n: '03',
    title: '分享与连接',
    items: [
      ['分享与收集', '带密码和期限，别人免注册也能传给你'],
      ['直链与图床', '一条永久地址，PicGo、Typora 直接传图'],
      ['WebDAV', 'Finder、资源管理器、rclone 都能挂载'],
      ['AI Agent', '经你授权，Claude 帮你整理和上传'],
    ],
  },
]
const activeScreen = ref<PhoneScreen>('preview')

const desktopExtras: [string, string][] = [
  ['整个文件夹下到电脑', '按原来的目录结构存好，断了接着下，下完逐个校验'],
  ['传输助手常驻', '托盘里点一下就是小窗，手机发来消息弹通知'],
  ['截图上传', '快捷键框选，传完链接已经在剪贴板里'],
  ['右键上传', '资源管理器里右键文件或文件夹，上传或发到传输助手'],
]

const appExtras: [string, string][] = [
  ['后台传输', '锁屏、断网、划掉 App 之后接着传'],
  ['系统分享', '相册里分享给 gopan，浏览器里分享网址就能剪藏'],
  ['系统文件', '微信等应用选文件时，能直接选云盘里的'],
  ['一键升级', 'App 内检查更新，下载后先校验再安装'],
]

// 电脑上给二维码:扫的是这一页而不是安装包,手机上能看到说明,微信扫了也有提示
const pageUrl = `${location.origin}/app`
const qr = qrPath(pageUrl)

function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// ---------- 滚动:功能区换屏、各块进场 ----------

const root = ref<HTMLElement>()
const observers: IntersectionObserver[] = []

function watchScroll() {
  const el = root.value
  if (!el || !('IntersectionObserver' in window)) return
  // 屏幕中线扫到哪一段,左边的手机就换成那一段的画面
  const story = new IntersectionObserver(
    (list) => {
      for (const e of list) if (e.isIntersecting) activeScreen.value = (e.target as HTMLElement).dataset.screen as PhoneScreen
    },
    { rootMargin: '-45% 0px -45% 0px' },
  )
  el.querySelectorAll('.step').forEach((s) => story.observe(s))
  observers.push(story)
  // 进场只淡入上移一次;系统设了减少动态就不做
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return
  el.classList.add('js-rv')
  const reveal = new IntersectionObserver(
    (list) => {
      for (const e of list) {
        if (!e.isIntersecting) continue
        e.target.classList.add('in')
        reveal.unobserve(e.target)
      }
    },
    { rootMargin: '0px 0px -6% 0px' },
  )
  el.querySelectorAll('.rv').forEach((r) => reveal.observe(r))
  observers.push(reveal)
}

const prevTitle = document.title
onMounted(async () => {
  document.title = 'gopan 云盘 · 安卓 App 与更新日志'
  watchScroll()
  // App「关于」里的「更新日志」带 #changelog 打开
  const id = location.hash.slice(1)
  if (id) {
    if (id === 'changelog') showAll.value = true
    // 安装包信息到了页面会变高(或少一段),等它到了再滚,免得停在半路
    await release.suspense().catch(() => undefined)
    await nextTick()
    document.getElementById(id)?.scrollIntoView({ block: 'start' })
  }
})
onBeforeUnmount(() => {
  document.title = prevTitle
  observers.forEach((o) => o.disconnect())
})
</script>

<template>
  <div ref="root" class="release gp-force-light">
    <div v-if="blockedInApp" class="inapp-tip" role="alert">
      {{ visitor.inAppBrowser === 'wechat' ? '微信' : 'QQ' }}里不能下载安装包：点右上角「···」，选「在浏览器打开」
      <span class="inapp-arrow" aria-hidden="true">↗</span>
    </div>

    <div class="navwrap">
      <nav class="pill">
        <a href="#top" class="pill-logo" @click.prevent="scrollTo('top')"><BrandLogo /></a>
        <a href="#features" class="hide-m" @click.prevent="scrollTo('features')">功能</a>
        <a v-if="showDesktop" href="#windows" class="hide-m" @click.prevent="scrollTo('windows')">Windows</a>
        <a v-if="canDownload" href="#android" class="hide-m" @click.prevent="scrollTo('android')">安卓 App</a>
        <a href="#changelog" @click.prevent="scrollTo('changelog')">更新日志</a>
        <router-link to="/drive" class="pill-cta">{{ webEntry }}</router-link>
      </nav>
    </div>

    <div id="top" class="wrap">
      <section class="gp-folder hero-folder">
        <a v-if="latest" href="#changelog" class="gp-folder-tab hero-tab" @click.prevent="scrollTo('changelog')">
          <i class="dot" />{{ latest.version }}
        </a>
        <div class="hero">
          <h1 class="rv">gopan 云盘</h1>
          <div class="hero-left">
            <div class="actions rv">
              <template v-if="heroDesktop">
                <a class="btn" :href="DESKTOP_EXE_URL">
                  <svg class="i" viewBox="0 0 24 24"><path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 20h14" /></svg>
                  下载 Windows 版
                </a>
                <router-link to="/drive" class="link">
                  {{ webEntry }}<svg class="i" viewBox="0 0 24 24"><path d="M5 12h14m-5-5 5 5-5 5" /></svg>
                </router-link>
              </template>
              <template v-else-if="canDownload">
                <span v-if="blockedInApp" class="btn is-disabled" aria-disabled="true">在浏览器打开后下载</span>
                <a v-else class="btn" :href="ANDROID_APK_URL">
                  <svg class="i" viewBox="0 0 24 24"><path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 20h14" /></svg>
                  下载安卓 App
                </a>
                <router-link to="/drive" class="link">
                  {{ webEntry }}<svg class="i" viewBox="0 0 24 24"><path d="M5 12h14m-5-5 5 5-5 5" /></svg>
                </router-link>
              </template>
              <router-link v-else to="/drive" class="btn">
                {{ webEntry }}<svg class="i" viewBox="0 0 24 24"><path d="M5 12h14m-5-5 5 5-5 5" /></svg>
              </router-link>
            </div>
            <p v-if="visitor.ios" class="hint">iPhone 和 iPad 没有 App：用 Safari 打开网页版，点「分享」→「添加到主屏幕」。</p>
            <p v-else-if="visitor.harmonyNext" class="hint">纯血鸿蒙装不了安卓安装包：用浏览器打开网页版，菜单里选「添加到桌面」。</p>
            <div v-if="visitor.desktop && canDownload" class="scan rv">
              <svg :viewBox="`0 0 ${qr.size} ${qr.size}`" shape-rendering="crispEdges" role="img" aria-label="本页二维码">
                <rect :width="qr.size" :height="qr.size" fill="#fff" />
                <path :d="qr.d" fill="#121212" />
              </svg>
              <span>手机扫码下载</span>
            </div>
          </div>
          <div class="hero-right">
            <ReleasePhone class="rise" :screens="['files']" active="files" />
          </div>
        </div>
      </section>
    </div>

    <section class="marquee" :aria-label="slogans.join('，')">
      <div class="mq-track" aria-hidden="true">
        <template v-for="copy in 2" :key="copy">
          <template v-for="(s, i) in slogans" :key="`${copy}-${i}`">
            <span :class="{ o: i % 2 === 1 }">{{ s }}</span><i />
          </template>
        </template>
      </div>
    </section>

    <div class="wrap">
      <section id="features" class="sec">
        <div class="rule sec-head rv">
          <h2>网盘该有的，<br />都做扎实了。</h2>
        </div>
        <div class="story">
          <div class="story-side">
            <div class="story-sticky">
              <ReleasePhone :screens="['preview', 'activity', 'share']" :active="activeScreen" />
            </div>
          </div>
          <div>
            <article v-for="s in steps" :key="s.screen" class="step" :data-screen="s.screen">
              <span class="big-n rv">{{ s.n }}</span>
              <h3 class="rv">{{ s.title }}</h3>
              <ul class="rv">
                <li v-for="[t, d] in s.items" :key="t"><b>{{ t }}</b><span>{{ d }}</span></li>
              </ul>
            </article>
          </div>
        </div>
      </section>

      <section v-if="showDesktop && desk" id="windows" class="sec">
        <div class="rule and">
          <div class="rv">
            <h2>装上桌面端，<br />电脑上也顺手。</h2>
            <ul class="plain">
              <li v-for="[t, d] in desktopExtras" :key="t"><b>{{ t }}</b><span>{{ d }}</span></li>
            </ul>
          </div>
          <div class="gp-folder file rv">
            <span class="gp-folder-tab file-tab">
              <svg class="i" viewBox="0 0 24 24"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /></svg>
              gopan-setup.exe
            </span>
            <div class="file-body">
              <div class="size"><b>{{ exeSize.num }}</b><span>{{ exeSize.unit }}</span></div>
              <dl class="mini">
                <div><dt>版本</dt><dd>{{ desk.version }}</dd></div>
                <div><dt>系统</dt><dd>Windows 10+</dd></div>
                <div><dt>发布</dt><dd>{{ dayLabel(desk.publishedAt) || '—' }}</dd></div>
              </dl>
              <a class="btn btn-inv" :href="DESKTOP_EXE_URL" data-test="download-exe">
                <svg class="i" viewBox="0 0 24 24"><path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 20h14" /></svg>
                下载安装包
              </a>
              <p class="file-note">安装包还没有代码签名：Windows 提示「已保护你的电脑」时，点「更多信息」→「仍要运行」。</p>
            </div>
          </div>
        </div>
      </section>

      <section v-if="canDownload" id="android" class="sec">
        <div class="rule and">
          <div class="rv">
            <h2>装上 App，<br />锁屏也照样在传。</h2>
            <ul class="plain">
              <li v-for="[t, d] in appExtras" :key="t"><b>{{ t }}</b><span>{{ d }}</span></li>
            </ul>
          </div>
          <div class="gp-folder file rv">
            <span class="gp-folder-tab file-tab">
              <svg class="i" viewBox="0 0 24 24"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /></svg>
              {{ rel?.apk || 'gopan.apk' }}
            </span>
            <div class="file-body">
              <template v-if="rel">
                <div class="size"><b>{{ apkSize.num }}</b><span>{{ apkSize.unit }}</span></div>
                <dl class="mini">
                  <div><dt>版本</dt><dd>{{ rel.versionName }}</dd></div>
                  <div><dt>系统</dt><dd>Android 8+</dd></div>
                  <div><dt>发布</dt><dd>{{ dayLabel(rel.publishedAt) || '—' }}</dd></div>
                </dl>
              </template>
              <span v-if="blockedInApp" class="btn btn-inv is-disabled" aria-disabled="true">在浏览器打开后下载</span>
              <a v-else class="btn btn-inv" :href="ANDROID_APK_URL" data-test="download-apk">
                <svg class="i" viewBox="0 0 24 24"><path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 20h14" /></svg>
                下载安装包
              </a>
            </div>
          </div>
        </div>
      </section>

      <section id="changelog" class="sec">
        <div class="rule sec-head rv">
          <h2>更新日志</h2>
        </div>
        <ol class="cl">
          <li v-if="latest" class="latest">
            <span class="v">{{ latest.version }}</span>
            <div class="body">
              <h3>{{ entryTitle(latest) }}</h3>
              <p v-for="(line, i) in latest.intro" :key="`p${i}`" class="intro"><RichLine :line="line" /></p>
              <ul>
                <li v-for="(line, i) in entryItems(latest)" :key="i"><RichLine :line="line" /></li>
              </ul>
            </div>
            <time>{{ dayLabel(latest.date) }}</time>
          </li>
          <li v-for="e in older" :key="e.version" :class="{ open: opened.has(e.version) }">
            <span class="v">{{ e.version }}</span>
            <div class="body">
              <button
                v-if="hasDetail(e)"
                type="button"
                class="cl-toggle"
                :aria-expanded="opened.has(e.version)"
                @click="toggleEntry(e.version)"
              >
                <span class="cl-title">{{ entryTitle(e) }}</span>
                <svg class="i chev" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6" /></svg>
              </button>
              <span v-else class="cl-title">{{ entryTitle(e) }}</span>
              <template v-if="opened.has(e.version)">
                <p v-for="(line, i) in e.intro" :key="`p${i}`" class="intro"><RichLine :line="line" /></p>
                <ul>
                  <li v-for="(line, i) in entryItems(e)" :key="i"><RichLine :line="line" /></li>
                </ul>
              </template>
            </div>
            <time>{{ dayLabel(e.date) }}</time>
          </li>
        </ol>
        <button v-if="!showAll && entries.length > SHOW_FIRST" type="button" class="link cl-more" @click="showAll = true">
          全部 {{ entries.length }} 个版本<svg class="i" viewBox="0 0 24 24"><path d="M5 12h14m-5-5 5 5-5 5" /></svg>
        </button>
      </section>

      <footer class="foot">
        <router-link to="/drive">{{ webEntry }}</router-link>
        <a v-if="canDownload" href="#android" @click.prevent="scrollTo('android')">安卓 App</a>
        <a href="#changelog" @click.prevent="scrollTo('changelog')">更新日志</a>
      </footer>
      <div class="bigmark" aria-hidden="true">gopan</div>
    </div>
  </div>
</template>

<style scoped>
.release {
  min-height: 100%;
  background: var(--el-bg-color);
  color: var(--gp-ink);
  font-size: 16px;
  line-height: 1.7;
  font-variant-numeric: tabular-nums;
  /* 横向不能滚;clip 不像 hidden 那样建滚动容器,功能区的 sticky 才有效 */
  overflow-x: clip;
}
/* 页面自己的基础重置;:where 不加权重,后面的类名规则照常生效 */
:where(.release, .release *) {
  box-sizing: border-box;
}
:where(.release a) {
  color: inherit;
  text-decoration: none;
}
:where(.release h1, .release h2, .release h3, .release p, .release ul, .release ol, .release dl, .release dd) {
  margin: 0;
  padding: 0;
}
:where(.release ul, .release ol) {
  list-style: none;
}
.wrap {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 32px;
}
section[id],
#top {
  scroll-margin-top: 96px;
}
.i {
  width: 18px;
  height: 18px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* 进场 */
.js-rv .rv {
  opacity: 0;
  transform: translateY(22px);
  transition: opacity 0.9s var(--gp-ease), transform 0.9s var(--gp-ease);
}
.js-rv .rv.in {
  opacity: 1;
  transform: none;
}

/* 微信 / QQ 里的提示 */
.inapp-tip {
  position: relative;
  padding: calc(12px + var(--sat)) 48px 12px 16px;
  background: var(--gp-ink);
  color: #fff;
  font-size: 14px;
  line-height: 1.5;
}
.inapp-arrow {
  position: absolute;
  top: calc(4px + var(--sat));
  right: 16px;
  font-size: 26px;
}

/* 悬浮导航:一颗黑色胶囊 */
.navwrap {
  position: sticky;
  top: 0;
  z-index: 60;
  display: flex;
  justify-content: center;
  height: 0;
}
.pill {
  display: flex;
  align-items: center;
  gap: 26px;
  height: 54px;
  margin-top: calc(16px + var(--sat));
  padding: 0 7px 0 20px;
  border-radius: 999px;
  background: rgba(18, 18, 18, 0.9);
  backdrop-filter: blur(14px) saturate(1.6);
  -webkit-backdrop-filter: blur(14px) saturate(1.6);
  box-shadow: 0 14px 34px -16px rgba(0, 0, 0, 0.55);
  color: rgba(255, 255, 255, 0.7);
  font-size: 14px;
  white-space: nowrap;
}
.pill a {
  transition: color 0.15s;
}
.pill a:hover {
  color: #fff;
}
.pill-logo {
  margin-right: 4px;
  color: #fff;
  font-size: 16px;
}
.pill-cta {
  display: inline-flex;
  align-items: center;
  height: 40px;
  padding: 0 18px;
  border-radius: 999px;
  background: #fff;
  color: var(--gp-ink);
  font-weight: 600;
}
.pill-cta:hover {
  background: #f0efe9;
  color: var(--gp-ink);
}

/* 首屏 */
.hero-folder {
  --gp-r: 34px;
  margin-top: 140px;
}
.hero-tab {
  height: 46px;
  padding: 0 24px;
  color: var(--gp-ink);
  font-size: 13.5px;
  font-weight: 700;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--gp-accent);
}
.hero {
  display: grid;
  grid-template-columns: 1fr 400px;
  grid-template-rows: auto 1fr;
  column-gap: 48px;
  min-height: 660px;
  padding: 78px 0 0 72px;
  overflow: hidden;
  border-radius: inherit;
}
h1 {
  grid-column: 1 / -1;
  padding-right: 72px;
  font-size: clamp(60px, 12.8vw, 184px);
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.058em;
  white-space: nowrap;
}
.hero-left {
  display: flex;
  flex-direction: column;
  padding: 76px 0 56px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 32px;
}
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  height: 56px;
  padding: 0 32px;
  border-radius: 999px;
  background: var(--gp-ink);
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  transition: background-color 0.2s, transform 0.2s var(--gp-ease), box-shadow 0.2s;
}
.btn:hover {
  background: #262626;
  box-shadow: 0 14px 28px -14px rgba(18, 18, 18, 0.7);
  transform: translateY(-2px);
}
.btn:active {
  box-shadow: none;
  transform: none;
}
.btn .i {
  width: 20px;
  height: 20px;
}
.btn.is-disabled {
  background: var(--gp-panel-2);
  box-shadow: none;
  color: var(--gp-ink-3);
  cursor: not-allowed;
  transform: none;
}
.link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: none;
  background: none;
  color: var(--gp-ink);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}
.link .i {
  transition: transform 0.2s var(--gp-ease);
}
.link:hover .i {
  transform: translateX(4px);
}
.hint {
  max-width: 420px;
  margin-top: 20px;
  color: var(--gp-ink-2);
  font-size: 15px;
}
.scan {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 56px;
  color: var(--gp-ink-2);
  font-size: 13.5px;
}
.scan svg {
  display: block;
  width: 78px;
  height: 78px;
  padding: 6px;
  border-radius: 12px;
  background: #fff;
}
.hero-right {
  position: relative;
}
.hero-right .phone {
  position: absolute;
  left: 24px;
  top: 40px;
}
.rise {
  animation: rise 1.3s var(--gp-ease) 0.3s both;
}
@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(160px);
  }
}

/* 标语带 */
.marquee {
  overflow: hidden;
  padding-top: 140px;
}
.mq-track {
  display: flex;
  align-items: center;
  width: max-content;
  font-size: clamp(44px, 7.4vw, 112px);
  font-weight: 800;
  line-height: 1.15;
  letter-spacing: -0.045em;
  white-space: nowrap;
  animation: mq 46s linear infinite;
}
.mq-track span,
.mq-track i {
  margin-right: 0.42em;
}
.mq-track .o {
  color: transparent;
  -webkit-text-stroke: 1.6px var(--gp-ink);
}
.mq-track i {
  width: 0.2em;
  height: 0.2em;
  flex: none;
  border-radius: 50%;
  background: var(--gp-accent);
}
@keyframes mq {
  to {
    transform: translateX(-50%);
  }
}

/* 区块 */
.sec {
  padding-top: 168px;
}
.rule {
  padding-top: 28px;
  border-top: 1px solid var(--gp-ink);
}
h2 {
  font-size: clamp(34px, 4.6vw, 64px);
  font-weight: 800;
  line-height: 1.1;
  letter-spacing: -0.05em;
}

/* 功能:左边手机固定,右边三段滚动 */
.story {
  display: grid;
  grid-template-columns: 400px 1fr;
  gap: 96px;
  margin-top: 24px;
}
.story-sticky {
  position: sticky;
  top: max(96px, calc(50vh - 340px));
  display: flex;
  justify-content: center;
}
.step {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 92vh;
  padding: 64px 0;
}
.big-n {
  color: var(--gp-accent);
  font-size: clamp(104px, 14vw, 210px);
  font-weight: 800;
  line-height: 0.82;
  letter-spacing: -0.075em;
}
.step h3 {
  margin-top: 28px;
  font-size: clamp(30px, 3.4vw, 46px);
  font-weight: 800;
  line-height: 1.15;
  letter-spacing: -0.045em;
}
.step ul {
  max-width: 580px;
  margin-top: 40px;
}
.step li,
.plain li {
  display: grid;
  grid-template-columns: 150px 1fr;
  gap: 16px;
  padding: 16px 0;
  border-top: 1px solid var(--gp-line);
  color: var(--gp-ink-2);
}
.step li b,
.plain li b {
  color: var(--gp-ink);
  font-weight: 650;
}

/* 安卓 / Windows:黑色的安装包文件 */
.and {
  display: grid;
  grid-template-columns: 1fr 470px;
  gap: 96px;
  align-items: start;
}
.plain {
  margin-top: 44px;
}
.plain li {
  grid-template-columns: 100px 1fr;
}
.file {
  --gp-r: 28px;
  --gp-tr: 16px;
  --gp-folder-bg: var(--gp-ink);
  margin-top: 66px;
  color: #fff;
}
.file-tab {
  gap: 8px;
  height: 42px;
  padding: 0 20px;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}
.file-tab .i {
  width: 15px;
  height: 15px;
  color: var(--gp-accent);
}
.file-body {
  padding: 26px 34px 32px;
}
.size {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.size b {
  font-size: 156px;
  font-weight: 800;
  line-height: 0.86;
  letter-spacing: -0.065em;
}
.size span {
  font-size: 34px;
  font-weight: 750;
  letter-spacing: -0.02em;
}
.mini {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin-top: 32px;
  border-top: 1px solid rgba(255, 255, 255, 0.14);
  border-bottom: 1px solid rgba(255, 255, 255, 0.14);
}
.mini div {
  padding: 14px 0;
}
.mini div + div {
  padding-left: 16px;
  border-left: 1px solid rgba(255, 255, 255, 0.14);
}
.mini dt {
  color: rgba(255, 255, 255, 0.55);
  font-size: 12px;
}
.mini dd {
  font-size: 15px;
  font-weight: 650;
}
.btn-inv {
  width: 100%;
  margin-top: 26px;
  background: #fff;
  color: var(--gp-ink);
}
.btn-inv:hover {
  background: #f0efe9;
  box-shadow: none;
}
.btn-inv.is-disabled {
  background: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.6);
}
.file-note {
  margin-top: 14px;
  color: rgba(255, 255, 255, 0.6);
  font-size: 12px;
  line-height: 1.6;
}

/* 更新日志 */
.cl {
  margin-top: 80px;
}
.cl > li {
  display: grid;
  grid-template-columns: 200px 1fr 140px;
  grid-template-areas: 'v body time';
  gap: 24px;
  padding: 26px 0;
  border-top: 1px solid var(--gp-line);
}
.v {
  grid-area: v;
  font-size: 22px;
  font-weight: 750;
  line-height: 1.3;
  letter-spacing: -0.03em;
}
.latest .v {
  color: var(--gp-accent);
}
.body {
  grid-area: body;
  min-width: 0;
}
.cl time {
  grid-area: time;
  color: var(--gp-ink-3);
  text-align: right;
}
.cl h3,
.cl-title {
  font-size: 18px;
  font-weight: 650;
  line-height: 1.6;
  letter-spacing: -0.01em;
}
.cl-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0;
  border: none;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.chev {
  color: var(--gp-ink-3);
  transition: transform 0.2s;
}
.open .chev {
  transform: rotate(180deg);
}
.intro {
  margin-top: 12px;
  color: var(--gp-ink-2);
}
.body ul {
  margin-top: 12px;
}
.body li {
  position: relative;
  padding: 4px 0 4px 18px;
  color: var(--gp-ink-2);
}
.body li::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 15px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--gp-ink-3);
}
.body strong {
  color: var(--gp-ink);
  font-weight: 650;
}
.body code {
  padding: 0 4px;
  border-radius: 4px;
  background: var(--gp-panel);
  font-size: 0.92em;
}
.cl-more {
  display: flex;
  width: 100%;
  padding-top: 24px;
  border-top: 1px solid var(--gp-line);
}

/* 页脚 */
.foot {
  display: flex;
  justify-content: flex-end;
  gap: 24px;
  margin-top: 168px;
  padding: 28px 0;
  border-top: 1px solid var(--gp-line);
  color: var(--gp-ink-3);
  font-size: 13.5px;
}
.foot a:hover {
  color: var(--gp-ink);
}
.bigmark {
  position: relative;
  z-index: -1;
  height: 0.86em;
  margin-top: -0.18em;
  overflow: hidden;
  color: var(--gp-panel);
  font-size: clamp(120px, 27vw, 392px);
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.065em;
  user-select: none;
}

@media (prefers-reduced-motion: reduce) {
  .mq-track,
  .rise {
    animation: none;
  }
  .btn,
  .link .i {
    transition: none;
  }
}
@media (max-width: 1100px) {
  .and {
    grid-template-columns: 1fr;
    gap: 0;
  }
  .file {
    max-width: 520px;
  }
  .story {
    grid-template-columns: 340px 1fr;
    gap: 56px;
  }
}
@media (max-width: 1023px) {
  .hero {
    grid-template-columns: 1fr 360px;
    padding-left: 48px;
  }
  .hero-right .phone {
    left: 0;
  }
  .step li {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
@media (max-width: 767px) {
  .wrap {
    padding: 0 16px;
  }
  .pill {
    gap: 16px;
    height: 50px;
    margin-top: calc(12px + var(--sat));
    padding-left: 16px;
    font-size: 13.5px;
  }
  .hide-m {
    display: none;
  }
  .pill-cta {
    height: 38px;
    padding: 0 16px;
  }
  .gp-folder {
    --gp-r: 24px;
    --gp-tr: 16px;
  }
  .hero-folder {
    margin-top: 118px;
  }
  .hero-tab {
    height: 40px;
    padding: 0 16px;
    font-size: 12.5px;
  }
  .hero {
    grid-template-columns: 1fr;
    min-height: 0;
    padding: 40px 24px 0;
  }
  h1 {
    padding-right: 0;
    font-size: 13.4vw;
  }
  .hero-left {
    padding: 44px 0 0;
  }
  .actions {
    flex-direction: column;
    align-items: stretch;
    gap: 18px;
  }
  .actions .link {
    justify-content: center;
  }
  .scan {
    display: none;
  }
  .hero-right {
    height: 420px;
  }
  .hero-right .phone {
    left: 50%;
    top: 44px;
    margin-left: -163px;
    scale: 0.92;
    transform-origin: top center;
  }
  .marquee {
    padding-top: 96px;
  }
  .sec {
    padding-top: 112px;
  }
  .story {
    display: block;
    margin-top: 0;
  }
  .story-side {
    display: none;
  }
  .step {
    min-height: 0;
    padding: 64px 0 0;
  }
  .step ul {
    margin-top: 28px;
  }
  .plain {
    margin-top: 32px;
  }
  .plain li {
    grid-template-columns: 1fr;
    gap: 2px;
  }
  .file {
    --gp-r: 22px;
    margin-top: 72px;
  }
  .file-body {
    padding: 22px 22px 26px;
  }
  .size b {
    font-size: 120px;
  }
  .mini div + div {
    padding-left: 12px;
  }
  .mini dd {
    font-size: 14px;
  }
  .cl {
    margin-top: 48px;
  }
  .cl > li {
    grid-template-columns: 1fr auto;
    grid-template-areas: 'v time' 'body body';
    gap: 4px 16px;
    padding: 20px 0;
  }
  .v {
    font-size: 19px;
  }
  .foot {
    justify-content: flex-start;
    margin-top: 112px;
  }
}
</style>
