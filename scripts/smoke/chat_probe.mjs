// 传输助手浏览器探针:真实浏览器走一遍 v2.5 的关键交互。
// 用法:node chat_probe.mjs(GOPAN_URL 可指向任意实例,默认 127.0.0.1:8080)
//
// 覆盖的是 API smoke 测不到的前端行为:
//   1. 连发两条文本都能上屏(v2.5 之前 isIdle 写错,第二条永远发不出);
//   2. 1000 条历史:首屏 200 条,滚到顶每次补 100 条直到"没有更早的消息了",
//      每次补齐后视口钉在原来那条消息上(scrollHeight - scrollTop 不变);
//   3. 轮询:另一端(这里用 API)发的消息 5 秒内出现。
// CDP 手搓,零 npm 依赖,与 browser_probe.mjs 同一套路。
import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = process.env.GOPAN_URL ?? 'http://127.0.0.1:8080'
const PORT = 9224
const TOTAL = 1000
const FIRST_PAGE = 200
const OLDER_PAGE = 100

const BROWSERS = [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
]
const bin = BROWSERS.find((p) => existsSync(p))
if (!bin) {
  console.error('未找到 Chromium 系浏览器,跳过浏览器探针')
  process.exit(0)
}

// ---------- 先用 API 准备数据:注册 + 灌 1000 条 ----------

async function gql(query, variables, token) {
  const res = await fetch(`${BASE}/query`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ query, variables }),
  })
  const body = await res.json()
  if (body.errors) throw new Error(JSON.stringify(body.errors))
  return body.data
}

const username = `probe_${Math.floor(Math.random() * 1e6).toString().padStart(6, '0')}`
const password = 'password123'
const reg = await gql(
  'mutation($u:String!,$p:String!){register(username:$u,password:$p){accessToken}}',
  { u: username, p: password },
)
const token = reg.register.accessToken
console.log(`== 注册 ${username} ==`)

const t0 = Date.now()
// 串行灌数据(并发会打乱 id 序,断言顺序就不成立);消息体带序号
let next = 0
await Promise.all(
  Array.from({ length: 1 }, async () => {
    for (;;) {
      const i = next++
      if (i >= TOTAL) return
      await gql('mutation($b:String){sendChatMessage(body:$b){id}}', { b: `历史消息 #${String(i).padStart(4, '0')}` }, token)
    }
  }),
)
console.log(`== 灌入 ${TOTAL} 条历史(${((Date.now() - t0) / 1000).toFixed(1)}s) ==`)

// ---------- 起浏览器 ----------

const profile = mkdtempSync(join(tmpdir(), 'gopan-chat-probe-'))
const browser = spawn(
  bin,
  ['--headless=new', '--disable-gpu', '--no-first-run', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'],
  { stdio: 'ignore' },
)
const die = (msg, code = 1) => {
  console.error(msg)
  browser.kill()
  process.exit(code)
}

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
await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 1, mobile: false })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const evalJS = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? JSON.stringify(r.exceptionDetails))
  return r.result?.value
}
async function waitFor(expr, timeoutMs, label) {
  const until = Date.now() + timeoutMs
  while (Date.now() < until) {
    if (await evalJS(expr)) return
    await sleep(200)
  }
  die(`超时:${label}`)
}

// ---------- 登录(走真实表单) ----------

await send('Page.navigate', { url: `${BASE}/login` })
await waitFor(`!!document.querySelector('input')`, 15000, '登录页未渲染')
// 配了 Yophon ID 的实例先显示 ID 登录按钮,要点「其他账号使用密码登录」才出密码表单
await evalJS(`[...document.querySelectorAll('button')].find((b) => /其他账号使用密码登录/.test(b.textContent))?.click(); true`)
await waitFor(`!!document.querySelector('.el-tab-pane input')`, 10000, '密码登录表单未渲染')
const filled = await evalJS(`(() => {
  const pane = document.querySelector('.el-tab-pane')
  const inputs = [...pane.querySelectorAll('input')]
  const u = inputs.find((i) => i.type !== 'password'), p = inputs.find((i) => i.type === 'password')
  if (!u || !p) return 'inputs=' + inputs.length
  const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
  set(u, ${JSON.stringify(username)}); set(p, ${JSON.stringify(password)})
  const btn = [...pane.querySelectorAll('button')].find((b) => b.textContent.trim() === '登录')
  if (!btn) return 'button missing'
  btn.click()
  return 'ok'
})()`)
if (filled !== 'ok') die(`填登录表单失败:${filled}`)
const loginUntil = Date.now() + 15000
while (!(await evalJS(`location.pathname.startsWith('/drive')`))) {
  if (Date.now() > loginUntil) {
    const hint = await evalJS(`[...document.querySelectorAll('.el-message, .el-form-item__error')].map((e) => e.textContent).join(' | ')`)
    die(`登录后未进入 /drive,仍在 ${await evalJS('location.pathname')},页面提示:${hint || '(无)'}`)
  }
  await sleep(200)
}
console.log('== 登录 OK ==')

// ---------- 传输助手:首屏 ----------

await send('Page.navigate', { url: `${BASE}/chat` })
const ROWS = `document.querySelectorAll('.message-list .message-row:not(.pending)').length`
await waitFor(`${ROWS} >= ${FIRST_PAGE}`, 20000, '首屏 200 条未渲染')
const firstCount = await evalJS(ROWS)
if (firstCount !== FIRST_PAGE) die(`首屏应恰好 ${FIRST_PAGE} 条,got ${firstCount}`)
const LIST = `document.querySelector('.message-list')`
const atBottom = await evalJS(`(() => { const l = ${LIST}; return l.scrollHeight - l.scrollTop - l.clientHeight < 80 })()`)
if (!atBottom) die('首屏应自动滚到底部')
const lastText = await evalJS(`[...document.querySelectorAll('.message-list .bubble-text')].pop().textContent`)
if (!/#0999/.test(lastText)) die(`底部应是最新一条(#0999),got ${lastText}`)
console.log('== 首屏 200 条 + 自动滚底 OK ==')

// ---------- 连发两条:第二条也要上屏(isIdle 回归) ----------

for (const body of ['探针消息一', '探针消息二']) {
  await evalJS(`(() => {
    const ta = document.querySelector('.composer textarea')
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    set.call(ta, ${JSON.stringify(body)}); ta.dispatchEvent(new Event('input', { bubbles: true }))
    ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
    return true
  })()`)
  await waitFor(
    `[...document.querySelectorAll('.message-list .message-row:not(.pending) .bubble-text')].some((e) => e.textContent === ${JSON.stringify(body)})`,
    10000,
    `「${body}」未上屏`,
  )
}
const sendBtnDisabled = await evalJS(`document.querySelector('.composer button[type="button"]:last-of-type, .composer .el-button--primary')?.disabled ?? null`)
console.log(`== 连发两条都上屏 OK(发送后按钮 disabled=${sendBtnDisabled},空草稿时为 true 属正常) ==`)

// ---------- 翻历史:滚到顶,每页 100,视口钉住 ----------

let loaded = await evalJS(ROWS)
let pages = 0
const t1 = Date.now()
while (true) {
  const done = await evalJS(`[...document.querySelectorAll('.message-list .hint')].some((e) => /没有更早/.test(e.textContent))`)
  if (done) break
  // 先滚到顶再取基线:钉视口的判据是「距底距离不变」,基线必须是触发加载那一刻的位置
  const before = await evalJS(`(() => { const l = ${LIST}; l.scrollTop = 0; const snap = { h: l.scrollHeight, top: l.scrollTop, rows: ${ROWS} }; l.dispatchEvent(new Event('scroll')); return snap })()`)
  const DONE = `[...document.querySelectorAll('.message-list .hint')].some((e) => /没有更早/.test(e.textContent))`
  await waitFor(`${ROWS} > ${before.rows} || ${DONE}`, 15000, `第 ${pages + 1} 页历史未加载(已 ${before.rows} 条)`)
  await sleep(150) // 等 nextTick 钉视口
  if ((await evalJS(ROWS)) === before.rows) break // 最后一次请求返回空页:到头了
  const after = await evalJS(`(() => { const l = ${LIST}; return { h: l.scrollHeight, top: l.scrollTop, rows: ${ROWS} } })()`)
  const added = after.rows - before.rows
  const drift = Math.abs(after.h - after.top - (before.h - before.top))
  if (drift > 4) die(`第 ${pages + 1} 页加载后视口跳动 ${drift}px(应钉在原消息上)`)
  const expectAdded = Math.min(OLDER_PAGE, TOTAL + 2 - before.rows)
  if (added !== expectAdded) die(`第 ${pages + 1} 页应补 ${expectAdded} 条,got ${added}`)
  pages++
  loaded = after.rows
  if (pages > 20) die('翻页次数异常')
}
if (loaded !== TOTAL + 2) die(`全部加载后应 ${TOTAL + 2} 条,got ${loaded}`)
const firstText = await evalJS(`document.querySelector('.message-list .bubble-text').textContent`)
if (!/#0000/.test(firstText)) die(`最早一条应是 #0000,got ${firstText}`)
console.log(`== 翻历史 ${pages} 页、每页 ${OLDER_PAGE} 条、视口无跳动、共 ${loaded} 条(${((Date.now() - t1) / 1000).toFixed(1)}s) ==`)

// ---------- 轮询:API 发一条,5 秒内出现 + 新消息浮标(用户在顶部) ----------

await gql('mutation($b:String){sendChatMessage(body:$b){id}}', { b: '来自另一端' }, token)
const t2 = Date.now()
await waitFor(
  `[...document.querySelectorAll('.message-list .bubble-text')].some((e) => e.textContent === '来自另一端')`,
  5000,
  '另一端发的消息 5 秒内未出现',
)
const fab = await evalJS(`document.querySelector('.new-msg-fab')?.textContent?.trim() ?? ''`)
if (!/1 条新消息/.test(fab)) die(`用户在顶部时应浮出「1 条新消息」,got「${fab}」`)
console.log(`== 轮询 ${((Date.now() - t2) / 1000).toFixed(1)}s 可见 + 新消息浮标 OK ==`)

// ---------- 搜索 ----------

await evalJS(`(() => {
  const inp = document.querySelector('.chat-header .search input')
  const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  set.call(inp, '#0042'); inp.dispatchEvent(new Event('input', { bubbles: true })); return true
})()`)
await waitFor(`${ROWS} === 1 && /#0042/.test(document.querySelector('.message-list .bubble-text').textContent)`, 8000, '搜索结果不符')
console.log('== 搜索 OK ==')

console.log('ALL CHAT PROBE PASSED')
browser.kill()
process.exit(0)
