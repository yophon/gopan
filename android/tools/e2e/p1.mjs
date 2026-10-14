// P1 全链路场景:本地账号中心 + 本地 gopan + 模拟器(debug 包需用 -Pgopan.baseUrl=http://localhost:8090
// -Pgopan.idIssuer=http://localhost:3040 构建并安装)。先 `node stack.mjs start`,再 `node p1.mjs`。
// 每个场景自己建立前置状态;失败时截图到 .run/,继续跑后面的场景,最后汇总。
import { join } from 'node:path';
import { App, T } from './app.mjs';
import { Chrome } from './chrome.mjs';
import { seedDrive, waitThumb, PNG_NAME } from './data.mjs';
import { Device } from './device.mjs';
import { GopanClient } from './gql.mjs';
import { idAdmin } from './stack.mjs';
import { RUN_DIR, ensureRunDir, log, readStack, sleep } from './util.mjs';

ensureRunDir();
const stack = readStack();
if (!stack) throw new Error('先运行 node stack.mjs start');
const device = new Device();
device.ensureOnline();
const app = new App(device);
const chrome = new Chrome(device);

const results = [];
// E2E_ONLY=D,H 只跑名字以这些编号开头的场景(场景之间有前后依赖,自己挑能独立跑的组合)
const ONLY = process.env.E2E_ONLY ? new Set(process.env.E2E_ONLY.split(',')) : null;
async function scenario(name, fn) {
  if (ONLY && !ONLY.has(name.split(' ')[0])) return;
  const t0 = Date.now();
  try {
    device.clearCrashes();
    await fn();
    const crashes = device.crashes();
    if (crashes) throw new Error(`App 崩溃:${crashes.slice(0, 400)}`);
    results.push({ name, ok: true, ms: Date.now() - t0 });
    log(`PASS ${name}`);
  } catch (e) {
    const shot = join(RUN_DIR, `fail-${results.length + 1}.png`);
    try { device.screenshot(shot); } catch {}
    results.push({ name, ok: false, ms: Date.now() - t0, error: String(e?.message || e), shot });
    log(`FAIL ${name}:${e?.message || e}`);
  }
}

function expect(cond, message) {
  if (!cond) throw new Error(message);
}

const host = new GopanClient();

/** 登录页 → 主界面:点 Yophon ID → Chrome 里填 demo 账号 → 回到 App */
async function yophonIdLogin() {
  device.clearData();
  app.coldStart();
  await app.waitLogin();
  await device.tap(T.idLogin);
  const { url } = await chrome.loginIdPage({ username: stack.demo.username, password: stack.demo.password });
  const ret = decodeURIComponent(new URL(url).searchParams.get('return') || '');
  const q = new URL(ret, 'http://x').searchParams;
  expect(q.get('client_id') === 'gopan-android', `client_id=${q.get('client_id')}`);
  expect(q.get('redirect_uri') === 'xyz.yophon.gopan:/oauthredirect', `redirect_uri=${q.get('redirect_uri')}`);
  expect(q.get('code_challenge_method') === 'S256', 'PKCE 方法不是 S256');
  expect(/^[A-Za-z0-9_-]{43}$/.test(q.get('code_challenge') || ''), 'code_challenge 格式不对');
  expect((q.get('scope') || '').split(' ').includes('offline_access'), `scope=${q.get('scope')}`);
  expect(!q.has('prompt'), `不应带 prompt:${q.get('prompt')}`);
  await app.waitMain(60000);
}

async function logoutFromAccountTab() {
  await app.gotoTab('我的');
  for (let i = 0; i < 6 && !device.dump().has({ text: '退出登录' }); i++) {
    app.scrollUp(500);
    await sleep(500);
  }
  await device.tap({ text: '退出登录' });
  await device.waitFor({ text: '退出' }, { label: '退出确认' });
  const btn = device.dump().find({ text: '退出' }).sort((a, b) => b.cy - a.cy)[0];
  device.tapXY(btn.clickableAncestor().cx, btn.clickableAncestor().cy);
  await app.waitLogin(20000);
}

async function ensurePasswordSession(username, password) {
  app.coldStart();
  const tree = await device.waitFor((t) => (app.isLogin(t) || app.isMain(t)) && t, { timeout: 30000, label: '启动' });
  if (app.isMain(tree)) {
    await app.gotoTab('我的');
    if (device.dump().has({ text: username })) return;
    await logoutFromAccountTab();
  }
  await app.passwordLogin(username, password);
  await app.waitMain(30000);
}

async function snackbar(pattern, timeout = 10000) {
  return device.waitFor({ textMatches: pattern }, { timeout, label: `提示 ${pattern}` });
}

/** 在文件页列表里找到某一行(必要时向下翻) */
async function findRow(name, maxScroll = 8) {
  for (let i = 0; i <= maxScroll; i++) {
    const tree = device.dump();
    const hit = tree.first({ text: name, filter: (n) => n.cy > 84 });
    if (hit) return hit;
    app.scrollUp();
    await sleep(500);
  }
  throw new Error(`列表里找不到 ${name}`);
}

async function rowAction(name, action) {
  await findRow(name);
  await app.openRowMenu(name);
  await device.tap({ text: action });
}

// ---------------- 场景 ----------------

await scenario('A Yophon ID 原生登录(PKCE / scope / redirect 校验)', async () => {
  await yophonIdLogin();
  await app.gotoTab('我的');
  await device.waitFor({ text: stack.owner.username }, { label: '「我的」显示绑定账号' });
});

await scenario('B 冷启动保持登录', async () => {
  app.coldStart();
  await app.waitMain(30000);
});

await scenario('C 账号中心撤销设备即时生效', async () => {
  await app.gotoTab('文件');
  idAdmin('revoke-all', { userId: stack.demo.sub });
  await sleep(500);
  // 下拉刷新若正好赶上一次还没回来的静默刷新会并进那一次(那次在撤销之前已经通过了校验):隔几秒再拉一次
  let out = false;
  for (let i = 0; i < 6 && !out; i++) {
    app.pullToRefresh();
    out = !!(await app.waitLogin(5000).catch(() => null));
  }
  if (!out) await app.waitLogin(10000);
  app.coldStart();
  await app.waitLogin(20000);
});

await scenario('D 密码登录:错误密码 / 绑定账号必须用 ID / 正确登录', async () => {
  app.coldStart();
  await app.waitLogin();
  await app.passwordLogin(stack.other.username, 'wrong-password');
  await device.waitFor({ text: T.badCredentials }, { label: '用户名或密码错误' });
  await app.passwordLogin(stack.owner.username, 'whatever-123');
  await device.waitFor({ text: T.idRequired }, { label: '请使用 Yophon ID 登录' });
  await app.passwordLogin(stack.other.username, stack.other.password);
  await app.waitMain(30000);
});

let seeded = null;
await scenario('E0 主机侧造数据(205 个文件夹、a/b/c、两张 PNG 含秒传)', async () => {
  await host.login(stack.other.username, stack.other.password);
  // 往次 E2 留下的 e2e-* 与别的套件的 0p*- 工作目录:清掉,不然新建的目录会排到首屏底部被浮动按钮挡住
  const leftover = (await host.allChildren(null)).items.filter((n) => n.kind === 'FOLDER' && /^(e2e-|0p[234]-)/.test(n.name)).map((n) => n.id);
  if (leftover.length) {
    await host.deleteNodes(leftover);
    await host.purgeTrash();
  }
  const existing = await host.childByName(null, 'a');
  if (existing) {
    seeded = { a: existing, pic: await host.childByName(null, PNG_NAME) };
  } else {
    seeded = await seedDrive(host);
  }
  await waitThumb(host, seeded.pic.id);
});

await scenario('E1 列表、分页、缩略图', async () => {
  await ensurePasswordSession(stack.other.username, stack.other.password);
  await app.gotoTab('文件');
  app.pullToRefresh();
  // a 应在列表前部(别的套件留下的 0p*- 目录排在它前面时往下滚一点)
  let seenA = false;
  for (let i = 0; i < 6 && !seenA; i++) {
    seenA = !!(await device.waitFor({ text: 'a' }, { timeout: 3000, label: '列表前部有 a' }).catch(() => null));
    if (!seenA) {
      app.scrollUp(300);
      await sleep(500);
    }
  }
  expect(seenA, '列表前部应有文件夹 a');
  // 文件夹在前(a, f-001..f-205),图片在最后,要翻到第二页才出现
  let found = false;
  for (let i = 0; i < 70 && !found; i++) {
    const { w } = device.displaySize();
    device.swipe(w / 2, 690, w / 2, 160, 120);
    await sleep(350);
    found = device.dump().has({ text: PNG_NAME });
  }
  expect(found, `翻页后没有出现 ${PNG_NAME}`);
  await sleep(1500);
  device.screenshot(join(RUN_DIR, 'p1-thumbnail.png'));
  await app.scrollToTop();
});

await scenario('E2 新建 → 重命名 → 删除 → 回收站还原(操作表收起后页面仍可点)', async () => {
  await app.gotoTab('文件');
  const name = `e2e-${Date.now() % 100000}`;
  await device.tap({ desc: '新建' });
  await device.tap({ text: '新建文件夹' });
  await device.waitFor({ text: '创建' }, { label: '新建文件夹对话框' });
  await app.fill(0, name);
  await device.tap({ text: '创建' });
  await snackbar(new RegExp(`已创建「${name}」`));
  expect(await host.childByName(null, name), '服务端没有新文件夹');

  await rowAction(name, '重命名');
  await device.waitFor({ text: '确定' }, { label: '重命名对话框' });
  await app.fill(0, `${name}-r`);
  await device.tap({ text: '确定' });
  await snackbar(/已重命名/);
  expect(await host.childByName(null, `${name}-r`), '服务端没有改名');

  await rowAction(`${name}-r`, '删除');
  await device.waitFor({ textMatches: /移到回收站/ }, { label: '删除确认' });
  const del = device.dump().find({ text: '删除' }).sort((a, b) => b.cy - a.cy)[0];
  device.tapXY(del.clickableAncestor().cx, del.clickableAncestor().cy);
  await snackbar(/移到回收站/);

  // 顶栏溢出菜单(列表每行的按钮描述也叫「更多」,只取顶栏里的)
  await device.tap({ desc: '更多', filter: (n) => n.cy < 84 });
  await device.tap({ text: '回收站' });
  await device.waitFor({ text: `${name}-r` }, { label: '回收站里有它' });
  await app.openRowMenu(`${name}-r`).catch(async () => {
    // 回收站行没有「详情」作为菜单标志时,退回到按名字长按
    const n = device.dump().first({ text: `${name}-r` });
    device.longPress(n);
  });
  await device.tap({ text: '还原' });
  await snackbar(/已还原/);
  expect(await host.childByName(null, `${name}-r`), '服务端没有还原');
  // 还原后页面必须还能点:返回键回到文件页
  device.back();
  await device.waitFor({ desc: '新建' }, { timeout: 10000, label: '返回文件页(页面没有被隐藏面板挡住)' });
});

await scenario('E3 搜索 → 所在目录', async () => {
  app.coldStart();
  await app.waitMain(30000);
  await device.tap({ desc: '搜索' });
  await device.waitFor({ text: '搜索文件名' }, { label: '搜索框' });
  device.type('note');
  await device.waitFor({ text: 'note.txt' }, { timeout: 15000, label: '搜索结果' });
  await app.openRowMenu('note.txt');
  await device.tap({ text: '所在目录' });
  await device.waitFor({ text: PNG_NAME }, { timeout: 15000, label: '进入 a/(有 pic.png 与 note.txt)' });
  expect(device.dump().has({ text: 'note.txt' }), '所在目录里没有 note.txt');
});

await scenario('F 外部改名下拉刷新可见(含面包屑)', async () => {
  const a = await host.childByName(null, 'a');
  app.coldStart();
  await app.waitMain(30000);
  await app.scrollToTop();
  await device.tap({ text: 'a' });
  await device.waitFor({ text: 'b' }, { label: '进入 a' });
  await host.rename(a.id, 'a-ext');
  try {
    app.pullToRefresh();
    await device.waitFor({ text: 'a-ext' }, { timeout: 15000, label: '标题/面包屑更新为 a-ext' });
  } finally {
    await host.rename(a.id, 'a');
  }
  device.back();
});

await scenario('G 离线冷启动显示缓存与提示条', async () => {
  device.removeReverse(8090);
  device.removeReverse(9009);
  try {
    app.coldStart();
    await app.waitMain(30000);
    await device.waitFor({ textMatches: /网络不可用/ }, { timeout: 20000, label: '离线提示条' });
    expect(device.dump().has({ text: 'a' }), '离线时没有显示缓存的列表');
    device.screenshot(join(RUN_DIR, 'p1-offline.png'));
  } finally {
    device.reverse(8090);
    device.reverse(9009);
  }
  // adb reverse 恢复后隧道要一会儿才通:下拉一次没连上会再显示缓存,隔几秒再拉
  let back = false;
  for (let i = 0; i < 6 && !back; i++) {
    await sleep(1000);
    app.pullToRefresh();
    back = !!(await device.waitGone({ textMatches: /网络不可用/ }, { timeout: 5000, label: '联网后提示条消失' }).catch(() => null));
  }
  if (!back) await device.waitGone({ textMatches: /网络不可用/ }, { timeout: 10000, label: '联网后提示条消失' });
});

/** 「我的」页往下翻到某一项(页面比一屏长) */
async function scrollAccountTo(sel) {
  for (let i = 0; i < 6 && !device.dump().has(sel); i++) {
    app.scrollUp(400);
    await sleep(500);
  }
}

await scenario('H 深色主题', async () => {
  await app.gotoTab('我的');
  await scrollAccountTo({ textMatches: /^主题/ });
  await device.tap({ textMatches: /^主题/ });
  await device.tap({ text: '深色' });
  await sleep(1200);
  device.screenshot(join(RUN_DIR, 'p1-dark.png'));
  await device.tap({ textMatches: /^主题/ });
  await device.tap({ text: '跟随系统' });
});

await scenario('J 退出登录后冷启动仍是登录页', async () => {
  await logoutFromAccountTab();
  app.coldStart();
  await app.waitLogin(20000);
});

// ---------------- 汇总 ----------------
const failed = results.filter((r) => !r.ok);
log('');
for (const r of results) log(`${r.ok ? 'PASS' : 'FAIL'} ${r.name}(${(r.ms / 1000).toFixed(1)}s)${r.ok ? '' : ` — ${r.error} [${r.shot}]`}`);
log(`${results.length - failed.length}/${results.length} 通过`);
process.exit(failed.length ? 1 : 0);
