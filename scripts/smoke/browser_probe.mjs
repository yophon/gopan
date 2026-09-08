// 浏览器级最小 e2e:真实浏览器走一遍注册表单 → 断言跳进 /drive。
// 用法:node browser_probe.mjs(GOPAN_URL 可指向任意实例,默认 127.0.0.1:8080)
//
// 存在的理由:API 级 smoke 再全,也测不出"请求发出之前"的前端断裂——
// graphql-request v7 的相对路径 Invalid URL 就在这里潜伏了六个里程碑,
// 直到第一次有人真用浏览器点注册才暴露。CDP 手搓,零 npm 依赖。
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { createServer } from 'node:http'

const BASE = process.env.GOPAN_URL ?? 'http://127.0.0.1:8080'
const PORT = 9223
let callback

let resolveCallback
const callbackReceived = new Promise((resolve) => { resolveCallback = resolve })
const callbackServer = createServer((req, res) => {
  resolveCallback(new URL(req.url, callback).toString())
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
  res.end('OAuth callback received')
})
await new Promise((resolve, reject) => {
  callbackServer.once('error', reject)
  callbackServer.listen(0, '127.0.0.1', resolve)
})
callback = `http://127.0.0.1:${callbackServer.address().port}/callback`

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

// Run against a production-mode instance too: development skips CSP, while
// production must allow the WebAssembly compilation used by hash-wasm uploads.
const wasm = await evalJS(`WebAssembly.compile(new Uint8Array([0,97,115,109,1,0,0,0]))
  .then(() => 'ok', e => e.message)`)
if (wasm !== 'ok') die('上传哈希所需的 WebAssembly 被阻止: ' + wasm)

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
if (url !== '/drive') die(`BROWSER PROBE FAILED:仍在 ${url},页面提示:${messages || '(无)'}`)

// Agent 凭证入口 → 五档 scope → 创建 API Key(明文只显示一次)
await evalJS(`document.querySelector('button[title="Agent API Key 与 OAuth"]')?.click()`)
await new Promise((r) => setTimeout(r, 600))
const dialogReady = await evalJS(`(() => {
  const dialog = [...document.querySelectorAll('.el-dialog')].find(d => d.textContent.includes('Agent 接入'))
  if (!dialog) return 'dialog missing'
  const scopes = dialog.querySelectorAll('.el-checkbox').length
  if (scopes !== 5) return 'scopes=' + scopes
  const input = dialog.querySelector('input[placeholder*="Codex"]')
  if (!input) return 'name input missing'
  input.value = 'browser-probe'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  return 'ok'
})()`)
if (dialogReady !== 'ok') die(`Agent 凭证弹窗失败:${dialogReady}`)
await new Promise((r) => setTimeout(r, 200))
const createClicked = await evalJS(`(() => {
  const dialog = [...document.querySelectorAll('.el-dialog')].find(d => d.textContent.includes('Agent 接入'))
  const button = [...dialog.querySelectorAll('button')].find(b => b.textContent.trim() === '创建')
  if (!button || button.disabled) return false
  button.click()
  return true
})()`)
if (!createClicked) die('API Key 创建按钮不可用')
await new Promise((r) => setTimeout(r, 1200))
const keyVisible = await evalJS(`document.querySelector('.created-value')?.textContent.includes('gopan_key_') ?? false`)
if (!keyVisible) die('API Key 创建后未显示明文')

// DCR → /oauth/authorize → 已登录用户授权确认页
const registration = await fetch(`${BASE}/oauth/register`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    client_name: 'Browser Probe Agent',
    redirect_uris: [callback],
    token_endpoint_auth_method: 'none',
  }),
}).then((r) => r.json())
if (!registration.client_id) die(`OAuth DCR 失败:${JSON.stringify(registration)}`)
const verifier = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~'
const challenge = createHash('sha256').update(verifier).digest('base64url')
const authorize = new URL(`${BASE}/oauth/authorize`)
authorize.search = new URLSearchParams({
  client_id: registration.client_id,
  redirect_uri: callback,
  response_type: 'code',
  scope: 'files:read files:download',
  state: 'browser-probe',
  code_challenge: challenge,
  code_challenge_method: 'S256',
})
await send('Page.navigate', { url: authorize.toString() })
await new Promise((r) => setTimeout(r, 2500))
const consent = await evalJS(`({
  path: location.pathname,
  title: document.querySelector('h1')?.textContent,
  permissions: [...document.querySelectorAll('.permission-row')].map(e => e.textContent.trim())
})`)
if (consent?.path !== '/oauth/consent' || consent?.title !== 'Browser Probe Agent' || consent?.permissions?.length !== 2) {
  die(`OAuth 授权页失败:${JSON.stringify(consent)}`)
}

const approved = await evalJS(`(() => {
  const button = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === '允许')
  if (!button) return false
  button.click()
  return true
})()`)
if (!approved) die('OAuth 允许按钮不可用')
const callbackURL = await Promise.race([
  callbackReceived,
  new Promise((resolve) => setTimeout(() => resolve(''), 3000)),
])
browser.kill()
callbackServer.close()
if (!callbackURL) die('OAuth 回调超时')
const callbackResult = new URL(callbackURL)
const code = callbackResult.searchParams.get('code')
if (!code || callbackResult.searchParams.get('state') !== 'browser-probe') {
  die(`OAuth 回调失败:${callbackURL}`)
}

const token = await fetch(`${BASE}/oauth/token`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    client_id: registration.client_id,
    redirect_uri: callback,
    code_verifier: verifier,
  }),
}).then((r) => r.json())
if (!token.access_token?.startsWith('gopan_oauth_') || !token.refresh_token) {
  die(`OAuth token 兑换失败:${JSON.stringify(token)}`)
}

const initialized = await fetch(`${BASE}/mcp`, {
  method: 'POST',
  headers: {
    Authorization: `Bearer ${token.access_token}`,
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
  },
  body: JSON.stringify({
    jsonrpc: '2.0', id: 1, method: 'initialize',
    params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'browser-probe', version: '1' } },
  }),
})
if (!initialized.ok) die(`OAuth MCP initialize 失败:${initialized.status}`)

console.log('BROWSER PROBE PASSED(API Key → OAuth code/token → MCP initialize)')
