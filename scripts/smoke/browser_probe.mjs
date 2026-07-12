// 浏览器级最小 e2e:真实浏览器走一遍注册表单 → 断言跳进 /drive。
// 用法:node browser_probe.mjs(GOPAN_URL 可指向任意实例,默认 127.0.0.1:8080)
//
// 存在的理由:API 级 smoke 再全,也测不出"请求发出之前"的前端断裂——
// graphql-request v7 的相对路径 Invalid URL 就在这里潜伏了六个里程碑,
// 直到第一次有人真用浏览器点注册才暴露。CDP 手搓,零 npm 依赖。
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'

const BASE = process.env.GOPAN_URL ?? 'http://127.0.0.1:8080'
const PORT = 9223

const BROWSERS = [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
  '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
]
const bin = BROWSERS.find((p) => existsSync(p))
if (!bin) {
  console.error('未找到 Chromium 系浏览器,跳过浏览器探针')
  process.exit(0)
}

const browser = spawn(bin, [
  '--headless=new', '--disable-gpu', '--no-first-run',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=/tmp/gopan-probe-${Date.now()}`,
  'about:blank',
], { stdio: 'ignore' })

const die = (msg, code = 1) => {
  console.error(msg)
  browser.kill()
  process.exit(code)
}

// 等 CDP 就绪
let version = null
for (let i = 0; i < 60 && !version; i++) {
  version = await fetch(`http://127.0.0.1:${PORT}/json/version`).then((r) => r.json()).catch(() => null)
  if (!version) await new Promise((r) => setTimeout(r, 500))
}
if (!version) die('CDP 未就绪')

const tab = await (await fetch(`http://127.0.0.1:${PORT}/json/new`, { method: 'PUT' })).json()
const ws = new WebSocket(tab.webSocketDebuggerUrl)
let id = 0
const pending = new Map()
function send(method, params = {}) {
  return new Promise((resolve) => {
    const mid = ++id
    pending.set(mid, resolve)
    ws.send(JSON.stringify({ id: mid, method, params }))
  })
}
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data)
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg.result ?? msg.error)
    pending.delete(msg.id)
  }
}
await new Promise((r) => (ws.onopen = r))
await send('Page.enable')
await send('Runtime.enable')
await send('Page.navigate', { url: `${BASE}/login` })
await new Promise((r) => setTimeout(r, 3000))

const evalJS = async (expr) =>
  (await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.value

// 切注册 tab → 填表单(dispatch input 让 v-model 生效)→ 提交
await evalJS(`[...document.querySelectorAll('.el-tabs__item')].find(t => t.textContent.includes('注册'))?.click()`)
await new Promise((r) => setTimeout(r, 600))
const filled = await evalJS(`(() => {
  const inputs = [...document.querySelectorAll('.el-tab-pane')[1].querySelectorAll('input')]
  if (inputs.length < 3) return 'inputs=' + inputs.length
  const vals = ['probe_' + (Date.now() % 1000000), 'password123', 'password123']
  inputs.forEach((el, i) => {
    el.value = vals[i]
    el.dispatchEvent(new Event('input', { bubbles: true }))
  })
  return 'ok'
})()`)
if (filled !== 'ok') die(`填表失败:${filled}`)
await evalJS(`[...document.querySelectorAll('.el-tab-pane')[1].querySelectorAll('button')].find(b => b.textContent.includes('注册'))?.click()`)
await new Promise((r) => setTimeout(r, 3000))

const url = await evalJS('location.pathname')
const messages = await evalJS(`[...document.querySelectorAll('.el-message, .el-form-item__error')].map(e => e.textContent).join(' | ')`)
browser.kill()

if (url === '/drive') {
  console.log('BROWSER PROBE PASSED(注册 → /drive)')
  process.exit(0)
}
die(`BROWSER PROBE FAILED:仍在 ${url},页面提示:${messages || '(无)'}`)
