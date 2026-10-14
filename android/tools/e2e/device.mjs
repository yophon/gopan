// adb 封装:默认只认模拟器(序列号 emulator-*),uiautomator dump 解析、查找、点按、输入、等待、截图、崩溃日志。
// 零依赖。所有命令显式带 -s <serial>;绝不 kill-server(会断开其他已连接的设备)。
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { APP_ID, log, resolveAdb, sleep } from './util.mjs';

export class DeviceError extends Error {}

/** uiautomator 节点:属性 + 子节点 + 父节点 */
export class UiNode {
  constructor(attrs, parent) {
    this.attrs = attrs;
    this.parent = parent;
    this.children = [];
    this.text = attrs.text || '';
    this.desc = attrs['content-desc'] || '';
    this.id = attrs['resource-id'] || '';
    this.cls = attrs.class || '';
    this.pkg = attrs.package || '';
    this.clickable = attrs.clickable === 'true';
    this.enabled = attrs.enabled !== 'false';
    this.checked = attrs.checked === 'true';
    this.selected = attrs.selected === 'true';
    this.focused = attrs.focused === 'true';
    this.scrollable = attrs.scrollable === 'true';
    this.password = attrs.password === 'true';
    const m = (attrs.bounds || '').match(/\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);
    this.bounds = m ? { x1: +m[1], y1: +m[2], x2: +m[3], y2: +m[4] } : { x1: 0, y1: 0, x2: 0, y2: 0 };
  }

  get cx() {
    return Math.round((this.bounds.x1 + this.bounds.x2) / 2);
  }

  get cy() {
    return Math.round((this.bounds.y1 + this.bounds.y2) / 2);
  }

  get width() {
    return this.bounds.x2 - this.bounds.x1;
  }

  get height() {
    return this.bounds.y2 - this.bounds.y1;
  }

  /** 自身与全部后代(先序) */
  *walk() {
    yield this;
    for (const c of this.children) yield* c.walk();
  }

  /** 最近的可点击祖先(含自身) */
  clickableAncestor() {
    for (let n = this; n; n = n.parent) if (n.clickable) return n;
    return this;
  }

  /** 自身与后代的全部文本(按先序拼接),便于断言一行里的内容 */
  allText() {
    return [...this.walk()].map((n) => [n.text, n.desc]).flat().filter(Boolean);
  }

  toString() {
    const b = this.bounds;
    return `${this.cls.split('.').pop()}${this.text ? ` text="${this.text}"` : ''}${this.desc ? ` desc="${this.desc}"` : ''}${
      this.id ? ` id="${this.id}"` : ''
    } [${b.x1},${b.y1}][${b.x2},${b.y2}]`;
  }
}

/** UI 树快照:find/has 接受选择器对象 */
export class UiTree {
  constructor(root, xml) {
    this.root = root;
    this.xml = xml;
    this.nodes = root ? [...root.walk()].filter((n) => n.attrs.bounds) : [];
  }

  find(sel) {
    return this.nodes.filter((n) => matches(n, sel));
  }

  first(sel) {
    return this.nodes.find((n) => matches(n, sel)) || null;
  }

  has(sel) {
    return this.nodes.some((n) => matches(n, sel));
  }

  /** 所有非空文本(调试用) */
  texts() {
    return this.nodes.map((n) => n.text || n.desc).filter(Boolean);
  }

  packages() {
    return [...new Set(this.nodes.map((n) => n.pkg).filter(Boolean))];
  }
}

/**
 * 选择器:字符串 = text 精确匹配;对象字段(全部满足):
 * text / textContains / textMatches(RegExp) / desc / descContains / descMatches / id / idContains / cls / pkg /
 * clickable / enabled / checked / selected / scrollable / password / within(UiNode) / filter(fn)
 */
export function matches(n, sel) {
  if (typeof sel === 'string') return n.text === sel;
  if (sel instanceof RegExp) return sel.test(n.text);
  if (sel.text !== undefined && n.text !== sel.text) return false;
  if (sel.textContains !== undefined && !n.text.includes(sel.textContains)) return false;
  if (sel.textMatches !== undefined && !sel.textMatches.test(n.text)) return false;
  if (sel.desc !== undefined && n.desc !== sel.desc) return false;
  if (sel.descContains !== undefined && !n.desc.includes(sel.descContains)) return false;
  if (sel.descMatches !== undefined && !sel.descMatches.test(n.desc)) return false;
  if (sel.textOrDesc !== undefined && n.text !== sel.textOrDesc && n.desc !== sel.textOrDesc) return false;
  if (sel.id !== undefined && n.id !== sel.id && !n.id.endsWith(`:id/${sel.id}`)) return false;
  if (sel.idContains !== undefined && !n.id.includes(sel.idContains)) return false;
  if (sel.cls !== undefined && !n.cls.endsWith(sel.cls)) return false;
  if (sel.pkg !== undefined && n.pkg !== sel.pkg) return false;
  for (const k of ['clickable', 'enabled', 'checked', 'selected', 'scrollable', 'password', 'focused']) {
    if (sel[k] !== undefined && n[k] !== sel[k]) return false;
  }
  if (sel.within) {
    const w = sel.within.bounds;
    if (n.cx < w.x1 || n.cx > w.x2 || n.cy < w.y1 || n.cy > w.y2) return false;
  }
  if (sel.filter && !sel.filter(n)) return false;
  return true;
}

function decodeEntities(s) {
  return s.replace(/&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g, (_, e) => {
    if (e[0] === '#') return String.fromCodePoint(e[1] === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10));
    return { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" }[e];
  });
}

/** uiautomator 的 XML 很规整:<node a="b" ...> / </node> / <node .../>;手写小解析器 */
export function parseUiXml(xml) {
  const top = new UiNode({}, null);
  const stack = [top];
  const re = /<node\b([^>]*?)(\/?)>|<\/node>/g;
  let m;
  while ((m = re.exec(xml))) {
    if (m[0].startsWith('</')) {
      if (stack.length > 1) stack.pop();
      continue;
    }
    const attrs = {};
    const ar = /([\w:-]+)="([^"]*)"/g;
    let a;
    while ((a = ar.exec(m[1]))) attrs[a[1]] = decodeEntities(a[2]);
    const parent = stack[stack.length - 1];
    const node = new UiNode(attrs, parent);
    parent.children.push(node);
    if (m[2] !== '/') stack.push(node);
  }
  return new UiTree(top, xml);
}

export class Device {
  constructor({ serial = process.env.E2E_SERIAL || 'emulator-5554', adb = resolveAdb() } = {}) {
    if (!/^emulator-\d+$/.test(serial) && process.env.E2E_ALLOW_PHYSICAL !== '1') {
      throw new DeviceError(`拒绝在非模拟器设备 ${serial} 上运行(确需真机请设 E2E_ALLOW_PHYSICAL=1)`);
    }
    this.serial = serial;
    this.adbPath = adb;
    this.lastTree = null;
  }

  /** adb -s <serial> ...args;返回 {status, stdout, stderr} */
  adb(args, { check = true, timeout = 60000, encoding = 'utf8', input } = {}) {
    const r = spawnSync(this.adbPath, ['-s', this.serial, ...args], {
      encoding,
      timeout,
      input,
      windowsHide: true,
      maxBuffer: 64 * 1024 * 1024,
    });
    if (r.error) throw new DeviceError(`adb ${args.join(' ')} 失败:${r.error.message}`);
    if (check && r.status !== 0) {
      const out = encoding ? `${r.stdout || ''}${r.stderr || ''}`.trim() : '';
      throw new DeviceError(`adb ${args.join(' ')} 退出码 ${r.status}:${out.slice(-1000)}`);
    }
    return r;
  }

  shell(cmd, opts) {
    return (this.adb(['shell', cmd], opts).stdout || '').trim();
  }

  ensureOnline() {
    const state = this.adb(['get-state'], { check: false });
    if ((state.stdout || '').trim() !== 'device') throw new DeviceError(`设备 ${this.serial} 不在线:${(state.stderr || '').trim()}`);
  }

  // ---- 端口 ----

  reverse(port) {
    this.adb(['reverse', `tcp:${port}`, `tcp:${port}`]);
  }

  removeReverse(port) {
    this.adb(['reverse', '--remove', `tcp:${port}`], { check: false });
  }

  reverseList() {
    return (this.adb(['reverse', '--list'], { check: false }).stdout || '').trim();
  }

  forward(local, remote) {
    this.adb(['forward', local, remote]);
  }

  removeForward(local) {
    this.adb(['forward', '--remove', local], { check: false });
  }

  // ---- UI 树 ----

  /** uiautomator dump;Compose 动画(转圈)可能让它拿不到 idle,重试几次 */
  dump({ retries = 4 } = {}) {
    let lastErr = '';
    for (let i = 0; i <= retries; i++) {
      const r = this.adb(['shell', 'uiautomator dump /sdcard/e2e-ui.xml'], { check: false, timeout: 30000 });
      const out = `${r.stdout || ''}${r.stderr || ''}`;
      if (r.status === 0 && /dumped to/i.test(out)) {
        const xml = this.adb(['exec-out', 'cat /sdcard/e2e-ui.xml'], { timeout: 30000 }).stdout || '';
        if (xml.includes('<hierarchy')) {
          this.lastTree = parseUiXml(xml);
          return this.lastTree;
        }
        lastErr = 'empty xml';
      } else {
        lastErr = out.trim();
      }
      sleepSync(400);
    }
    throw new DeviceError(`uiautomator dump 失败:${lastErr}`);
  }

  find(sel, tree = this.dump()) {
    return tree.find(sel);
  }

  first(sel, tree = this.dump()) {
    return tree.first(sel);
  }

  /** 轮询直到选择器出现;sel 可为函数(tree => 值) */
  async waitFor(sel, { timeout = 20000, interval = 600, label } = {}) {
    const deadline = Date.now() + timeout;
    let tree;
    for (;;) {
      try {
        tree = this.dump();
        const hit = typeof sel === 'function' ? sel(tree) : tree.first(sel);
        if (hit) return hit;
      } catch (e) {
        if (!(e instanceof DeviceError)) throw e;
      }
      if (Date.now() > deadline) {
        const seen = tree ? tree.texts().slice(0, 40).join(' | ') : '(无 UI 树)';
        throw new DeviceError(`等待超时(${timeout}ms):${label || describe(sel)};当前可见:${seen}`);
      }
      await sleep(interval);
    }
  }

  /** 轮询直到选择器消失 */
  async waitGone(sel, { timeout = 20000, interval = 600, label } = {}) {
    return this.waitFor((t) => (typeof sel === 'function' ? !sel(t) : !t.has(sel)), { timeout, interval, label: `消失:${label || describe(sel)}` });
  }

  // ---- 输入 ----

  tapXY(x, y) {
    this.shell(`input tap ${Math.round(x)} ${Math.round(y)}`);
  }

  /** 点节点(或选择器第一个匹配)的中心 */
  async tap(target, opts = {}) {
    const node = target instanceof UiNode ? target : await this.waitFor(target, opts);
    this.tapXY(node.cx, node.cy);
    return node;
  }

  longPress(node, ms = 900) {
    this.shell(`input swipe ${node.cx} ${node.cy} ${node.cx} ${node.cy} ${ms}`);
  }

  swipe(x1, y1, x2, y2, ms = 400) {
    this.shell(`input swipe ${Math.round(x1)} ${Math.round(y1)} ${Math.round(x2)} ${Math.round(y2)} ${ms}`);
  }

  /** input text 只支持 ASCII;空格转 %s,shell 特殊字符转义 */
  type(text) {
    if (/[^\x20-\x7e]/.test(text)) throw new DeviceError(`input text 只支持 ASCII:${text}`);
    const escaped = text.replace(/ /g, '%s').replace(/([\\'"`$&|;<>()*?!#~{}[\]])/g, '\\$1');
    this.shell(`input text ${escaped}`);
  }

  key(...codes) {
    this.shell(`input keyevent ${codes.join(' ')}`);
  }

  back() {
    this.key('KEYCODE_BACK');
  }

  /** 清空当前聚焦输入框:光标移到末尾后连删 */
  clearFocused(maxChars = 40) {
    this.key('KEYCODE_MOVE_END');
    this.shell(`input keyevent ${Array(maxChars).fill('KEYCODE_DEL').join(' ')}`);
  }

  hideKeyboard() {
    const ime = this.shell('dumpsys input_method | grep -E "mInputShown|mIsInputViewShown"');
    if (/mInputShown=true|mIsInputViewShown=true/.test(ime)) this.back();
  }

  // ---- 应用 / 系统 ----

  isInstalled(pkg = APP_ID) {
    return this.shell(`pm list packages ${pkg}`).split(/\r?\n/).some((l) => l.trim() === `package:${pkg}`);
  }

  install(apk) {
    const r = this.adb(['install', '-r', '-t', apk], { check: false, timeout: 180000 });
    return { ok: r.status === 0 && /Success/.test(r.stdout), out: `${r.stdout}${r.stderr}`.trim() };
  }

  uninstall(pkg = APP_ID) {
    return this.adb(['uninstall', pkg], { check: false }).stdout.trim();
  }

  clearData(pkg = APP_ID) {
    return this.shell(`pm clear ${pkg}`);
  }

  forceStop(pkg = APP_ID) {
    this.shell(`am force-stop ${pkg}`);
  }

  launch(pkg = APP_ID, activity = '.MainActivity') {
    return this.shell(`am start -W -n ${pkg}/${activity}`);
  }

  pidOf(pkg = APP_ID) {
    const out = this.shell(`pidof ${pkg}`, { check: false });
    return out ? Number(out.split(/\s+/)[0]) : null;
  }

  /** 当前获得焦点的窗口,如 xyz.yophon.gopan/xyz.yophon.gopan.MainActivity */
  focusedWindow() {
    const out = this.shell('dumpsys window | grep -E "mCurrentFocus|mFocusedApp"', { check: false });
    const m = out.match(/mCurrentFocus=Window\{\S+ \S+ ([^}\s]+)\}/);
    return m ? m[1] : out.split(/\r?\n/)[0] || '';
  }

  screenshot(path) {
    mkdirSync(dirname(path), { recursive: true });
    const r = this.adb(['exec-out', 'screencap -p'], { encoding: null, timeout: 30000 });
    writeFileSync(path, r.stdout);
    return path;
  }

  /** 清空崩溃缓冲区,之后 crashes() 只看新的 */
  clearCrashes() {
    this.adb(['logcat', '-b', 'crash', '-c'], { check: false });
  }

  /** 崩溃缓冲区里属于 pkg 的 FATAL / ANR 片段(空字符串表示没有) */
  crashes(pkg = APP_ID) {
    const crash = this.adb(['logcat', '-d', '-b', 'crash'], { check: false }).stdout || '';
    const blocks = crash.split(/(?=^.*FATAL EXCEPTION)/m).filter((b) => /FATAL EXCEPTION/.test(b) && b.includes(pkg));
    return blocks.join('\n').trim();
  }

  /** 应用日志(按 pid 过滤),排错用 */
  appLog(pkg = APP_ID, lines = 300) {
    const pid = this.pidOf(pkg);
    const args = ['logcat', '-d', '-t', String(lines)];
    if (pid) args.push(`--pid=${pid}`);
    return this.adb(args, { check: false }).stdout || '';
  }

  setAirplane(on) {
    this.shell(`cmd connectivity airplane-mode ${on ? 'enable' : 'disable'}`);
  }

  getSetting(ns, key) {
    return this.shell(`settings get ${ns} ${key}`);
  }

  putSetting(ns, key, value) {
    this.shell(`settings put ${ns} ${key} ${value}`);
  }

  /** 关自动旋转并转到 rotation(0 竖屏,1 横屏) */
  setRotation(rotation) {
    this.putSetting('system', 'accelerometer_rotation', 0);
    this.putSetting('system', 'user_rotation', rotation);
  }

  displaySize() {
    const out = this.shell('wm size');
    const m = out.match(/Override size: (\d+)x(\d+)/) || out.match(/Physical size: (\d+)x(\d+)/);
    return m ? { w: +m[1], h: +m[2] } : { w: 390, h: 844 };
  }
}

function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function describe(sel) {
  if (typeof sel === 'string') return `text="${sel}"`;
  if (sel instanceof RegExp) return String(sel);
  if (typeof sel === 'function') return sel.name || 'predicate';
  return JSON.stringify(sel, (k, v) => (v instanceof RegExp ? String(v) : k === 'within' ? undefined : v));
}

export { log };
