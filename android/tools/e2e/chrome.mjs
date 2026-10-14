// 用 CDP 驱动模拟器里的 Chrome(含 Custom Tab):adb forward tcp:9333 → localabstract:chrome_devtools_remote。
// Node 24 自带 WebSocket,零依赖。首次打开 Chrome 的欢迎页/同步提示是原生界面,用 uiautomator 点掉。
import { ID_ISSUER, PORTS, log, sleep } from './util.mjs';

const CHROME_PKG = 'com.android.chrome';

/** Chrome 首次运行可能出现的按钮(英文/中文系统都覆盖);按顺序尝试 */
const FIRST_RUN_BUTTONS = [
  'Use without an account',
  'Accept & continue',
  'Accept & Continue',
  'Continue',
  'No thanks',
  'No, thanks',
  'Not now',
  'Got it',
  'Skip',
  'Done',
  '不登录,直接使用',
  '不登录直接使用',
  '接受并继续',
  '继续',
  '不用了',
  '不,谢谢',
  '暂时不要',
  '知道了',
  '跳过',
];

export class CdpSession {
  constructor(ws) {
    this.ws = ws;
    this.nextId = 1;
    this.pending = new Map();
    this.events = [];
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString('utf8'));
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(`CDP ${msg.error.code}: ${msg.error.message}`));
        else resolve(msg.result);
      } else if (msg.method) {
        this.events.push(msg);
      }
    });
    ws.addEventListener('close', () => {
      for (const { reject } of this.pending.values()) reject(new Error('CDP 连接已关闭'));
      this.pending.clear();
    });
  }

  static async open(wsUrl, timeout = 10000) {
    const ws = new WebSocket(wsUrl);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`CDP 连接超时:${wsUrl}`)), timeout);
      ws.addEventListener('open', () => (clearTimeout(timer), resolve()));
      ws.addEventListener('error', (e) => (clearTimeout(timer), reject(new Error(`CDP 连接失败:${e.message || wsUrl}`))));
    });
    return new CdpSession(ws);
  }

  send(method, params = {}, timeout = 15000) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`CDP ${method} 超时`));
      }, timeout);
      this.pending.set(id, {
        resolve: (v) => (clearTimeout(timer), resolve(v)),
        reject: (e) => (clearTimeout(timer), reject(e)),
      });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  /** Runtime.evaluate,返回值按值取回;userGesture 让点击/跳转算作用户手势 */
  async eval(expression, { userGesture = false, awaitPromise = true } = {}) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise, userGesture });
    if (r.exceptionDetails) throw new Error(`页面脚本异常:${r.exceptionDetails.exception?.description || r.exceptionDetails.text}`);
    return r.result?.value;
  }

  close() {
    try {
      this.ws.close();
    } catch {
      /* ignore */
    }
  }
}

export class Chrome {
  constructor(device, { port = PORTS.cdp } = {}) {
    this.device = device;
    this.port = port;
  }

  forward() {
    this.device.forward(`tcp:${this.port}`, 'localabstract:chrome_devtools_remote');
  }

  unforward() {
    this.device.removeForward(`tcp:${this.port}`);
  }

  /** 页面型 target 列表;Chrome 没在跑时返回 [] */
  async targets() {
    try {
      const res = await fetch(`http://127.0.0.1:${this.port}/json/list`, { signal: AbortSignal.timeout(3000) });
      const list = await res.json();
      return list.filter((t) => t.type === 'page');
    } catch {
      return [];
    }
  }

  async connect(target) {
    return CdpSession.open(`ws://127.0.0.1:${this.port}/devtools/page/${target.id}`);
  }

  /** 前台是不是 Chrome(Custom Tab 也算) */
  inForeground() {
    return this.device.focusedWindow().startsWith(`${CHROME_PKG}/`);
  }

  /** 点掉 Chrome 的首次运行界面;返回点过的按钮文本 */
  dismissFirstRun() {
    const tapped = [];
    for (let i = 0; i < 6; i++) {
      if (!this.inForeground()) break;
      const tree = this.device.dump();
      const btn = FIRST_RUN_BUTTONS.map((t) => tree.first({ text: t, pkg: CHROME_PKG })).find(Boolean);
      if (!btn) break;
      log(`Chrome 首次运行:点「${btn.text}」`);
      this.device.tapXY(btn.clickableAncestor().cx, btn.clickableAncestor().cy);
      tapped.push(btn.text);
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1200);
    }
    return tapped;
  }

  /**
   * 等账号中心页面出现在 Chrome 里(期间顺手处理首次运行界面)。
   * 返回 { target, firstRun:[...] };pathPrefix 如 '/login'。
   */
  async waitForIdPage(pathPrefix = '/', { timeout = 45000 } = {}) {
    const deadline = Date.now() + timeout;
    const firstRun = [];
    this.forward();
    for (;;) {
      const targets = await this.targets();
      const target = targets.find((t) => t.url.startsWith(ID_ISSUER + pathPrefix));
      if (target) return { target, firstRun };
      if (this.inForeground()) firstRun.push(...this.dismissFirstRun());
      if (Date.now() > deadline) {
        throw new Error(`Chrome 里没有出现 ${ID_ISSUER}${pathPrefix}(当前 targets:${targets.map((t) => t.url).join(', ') || '无'})`);
      }
      await sleep(700);
      this.forward();
    }
  }

  /**
   * 在账号中心登录页(/login?return=/authorize?...)填账号密码并提交。
   * 返回登录页 URL(其中 return 参数就是 App 发起的授权请求)。
   */
  async loginIdPage({ username, password }, { timeout = 45000 } = {}) {
    const { target, firstRun } = await this.waitForIdPage('/login', { timeout });
    const session = await this.connect(target);
    try {
      const formReady = `!!document.querySelector('input[autocomplete="username"]') && !!document.querySelector('input[type="password"]')`;
      const deadline = Date.now() + 20000;
      while (!(await session.eval(formReady).catch(() => false))) {
        if (Date.now() > deadline) throw new Error('账号中心登录表单没有出现');
        await sleep(400);
      }
      const url = await session.eval('location.href');
      const fill = `(() => {
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
        const put = (el, v) => { el.focus(); setter.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); };
        const u = document.querySelector('input[autocomplete="username"]');
        const p = document.querySelector('input[type="password"]');
        put(u, ${JSON.stringify(username)});
        put(p, ${JSON.stringify(password)});
        return u.value === ${JSON.stringify(username)} && p.value.length === ${password.length};
      })()`;
      if (!(await session.eval(fill))) throw new Error('填写账号中心登录表单失败');
      // 用户手势提交:随后 location.assign(/authorize) → 302 到 xyz.yophon.gopan:/oauthredirect,Chrome 才会交给 App
      const submitted = await session.eval(
        `(() => { const b = [...document.querySelectorAll('form button[type="submit"]')][0]; if (!b) return false; b.click(); return true; })()`,
        { userGesture: true },
      );
      if (!submitted) throw new Error('找不到账号中心登录按钮');
      return { url, firstRun };
    } finally {
      session.close();
    }
  }

  /** 账号中心页面上的错误提示(登录失败时排错用) */
  async idPageText() {
    const t = (await this.targets()).find((x) => x.url.startsWith(ID_ISSUER));
    if (!t) return null;
    const s = await this.connect(t);
    try {
      return await s.eval('document.body ? document.body.innerText.slice(0, 1500) : ""');
    } finally {
      s.close();
    }
  }
}
