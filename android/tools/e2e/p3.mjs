// P3 全链路场景(预览 / 分享 / 账户 / 设备 / 主题):本地 gopan + 模拟器。
// 前置:node stack.mjs start;debug 包用 -Pgopan.baseUrl=http://localhost:8090 -Pgopan.idIssuer=http://localhost:3040 构建并安装。
// 用密码账号 other 登录;测试文件由主机侧 GraphQL 上传到根目录下的 0p3-<随机> 工作目录。
import { spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { App } from './app.mjs';
import { waitThumb } from './data.mjs';
import { Device } from './device.mjs';
import { GopanClient, makePng } from './gql.mjs';
import { gbkText, makePdf, makeWav } from './media.mjs';
import { APP_ID, RUN_DIR, ensureRunDir, log, randHex, readStack, sleep } from './util.mjs';

ensureRunDir();
const stack = readStack();
if (!stack) throw new Error('先运行 node stack.mjs start');
const device = new Device();
device.ensureOnline();
const app = new App(device);
const ONLY = process.env.E2E_ONLY ? new Set(process.env.E2E_ONLY.split(',')) : null;
const RUN = randHex(3);

const results = [];
async function scenario(id, name, fn) {
  if (ONLY && !ONLY.has(id)) return;
  const t0 = Date.now();
  try {
    device.clearCrashes();
    await fn();
    const crashes = device.crashes();
    if (crashes) throw new Error(`App 崩溃:${crashes.slice(0, 600)}`);
    results.push({ name: `${id} ${name}`, ok: true, ms: Date.now() - t0 });
    log(`PASS ${id} ${name}`);
  } catch (e) {
    const shot = join(RUN_DIR, `p3-fail-${id}.png`);
    try {
      device.screenshot(shot);
    } catch {}
    results.push({ name: `${id} ${name}`, ok: false, ms: Date.now() - t0, error: String(e?.message || e), shot });
    log(`FAIL ${id} ${name}:${e?.message || e}`);
    await recover();
  }
}

function expect(cond, message) {
  if (!cond) throw new Error(message);
}

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

/** 失败后回到主界面(各 tab 的根页面):最多按 6 次返回,还不行就冷启动 */
async function recover() {
  for (let i = 0; i < 6; i++) {
    const t = device.dump();
    if (app.isMain(t) && !t.has({ id: 'preview_root' }) && !t.has({ desc: '返回' })) return;
    device.back();
    await sleep(700);
  }
  app.coldStart();
  await app.waitMain(30000).catch(() => {});
}

// ---------------- 主机侧 ----------------

const host = new GopanClient();
await host.authCall(
  'login',
  'mutation($u:String!,$p:String!){ login(username:$u,password:$p){ accessToken user { id username } } }',
  { u: stack.other.username, p: stack.other.password },
);

async function waitServer(fn, { timeout = 120000, interval = 2000, label }) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (last) return last;
    await sleep(interval);
  }
  throw new Error(`等待超时:${label}`);
}

// ---------------- 设备侧 ----------------

async function ensureLoggedIn() {
  app.coldStart();
  const tree = await device.waitFor((t) => (app.isLogin(t) || app.isMain(t)) && t, { timeout: 30000, label: '启动' });
  if (app.isMain(tree)) return;
  await app.passwordLogin(stack.other.username, stack.other.password);
  await app.waitMain(30000);
}

function deviceSha(path) {
  const r = device.adb(['shell', `sha256sum '${path}' 2>/dev/null`], { check: false });
  if (r.status !== 0) return null;
  return (r.stdout || '').trim().split(/\s+/)[0] || null;
}

/** 关掉系统的通知权限对话框(本次安装第一次有传输时会问;出现得比任务入队晚一点,最多等几秒) */
async function dismissPermissionDialog(waitMs = 6000) {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    const t = device.dump();
    const allow = t.first({ text: 'Allow' }) || t.first({ text: '允许' });
    if (allow) {
      device.tapXY(allow.clickableAncestor().cx, allow.clickableAncestor().cy);
      await sleep(800);
      return;
    }
    await sleep(700);
  }
}

/**
 * 双击:两次点按放进同一条 shell 命令。每次 input tap 约 40 ms,两次之间若不到 40 ms(双击最小间隔)
 * 第二下会被当成抖动吞掉,所以中间停 120 ms(仍在 300 ms 的双击判定时间内)。
 */
function doubleTap(x, y) {
  const p = `${Math.round(x)} ${Math.round(y)}`;
  device.shell(`input tap ${p}; sleep 0.12; input tap ${p}`);
}

function screenCenter() {
  const { w, h } = device.displaySize();
  return { x: w / 2, y: h / 2 };
}

/** 预览页:左滑 = 下一个 */
function swipePage(next = true) {
  const { w, h } = device.displaySize();
  if (next) device.swipe(w * 0.85, h * 0.55, w * 0.15, h * 0.55, 250);
  else device.swipe(w * 0.15, h * 0.55, w * 0.85, h * 0.55, 250);
}

function previewTitle(tree = device.dump()) {
  return tree.first({ id: 'preview_title' })?.text ?? null;
}

function previewSubtitle(tree = device.dump()) {
  return tree.first({ id: 'preview_subtitle' })?.text ?? '';
}

async function waitPreview(name) {
  return device.waitFor((t) => previewTitle(t) === name && t, { timeout: 20000, label: `预览打开 ${name}` });
}

/** 顶栏被收起时点一下屏幕中间呼出来 */
async function showChrome() {
  if (device.dump().has({ id: 'preview_title' })) return;
  const c = screenCenter();
  device.tapXY(c.x, c.y);
  await device.waitFor({ id: 'preview_title' }, { timeout: 5000, label: '顶栏出现' });
}

async function closePreview() {
  await showChrome().catch(() => {});
  await device.tap({ id: 'preview_back' });
  await device.waitGone({ id: 'preview_root' }, { timeout: 10000, label: '退出预览' });
}

/** 媒体会话状态:PLAYING / PAUSED / …(没有本 App 的会话返回 null) */
function sessionState() {
  const out = device.shell('dumpsys media_session');
  const i = out.indexOf(`package=${APP_ID}`);
  if (i < 0) return null;
  const m = out.slice(i, i + 3000).match(/state=PlaybackState \{state=(\w+)/) || out.slice(i, i + 3000).match(/state=(\w+)\(\d+\)/);
  return m ? m[1] : 'UNKNOWN';
}

// ---------------- 测试文件 ----------------

let work = null;
const files = {};

async function uploadFixtures() {
  work = await host.createFolder(null, `0p3-${RUN}`);
  const put = async (name, bytes) => {
    files[name] = { bytes, node: await host.upload(work.id, name, bytes) };
  };
  await put('a1.png', await makePng(1200, 900, 21));
  await put('a2.png', await makePng(800, 600, 22));
  await put('tone.wav', makeWav(90, 440, 8000)); // 90 秒:离开再回来的途中不能放完
  await put('doc.pdf', makePdf(3, `gopan ${RUN}`));
  await put('note.txt', Buffer.from(Array.from({ length: 40 }, (_, i) => `第 ${i + 1} 行 你好 gopan`).join('\n') + '\n'));
  await put('gbk.txt', gbkText());
  await put('big.log', Buffer.from(Array.from({ length: 12000 }, (_, i) => `log line ${String(i).padStart(6, '0')} ${'x'.repeat(48)}`).join('\n')));
  await put('report.docx', randomBytes(2048));
  await put('data.bin', randomBytes(4096));
  // 视频:模拟器录 6 秒屏(H.264 MP4)。静止画面编码器几乎不出帧、时长不到 1 秒,所以录的同时来回滑动屏幕
  device.shell('rm -f /sdcard/Download/p3-clip.mp4');
  const recorder = spawn(device.adbPath, ['-s', device.serial, 'shell', 'screenrecord --time-limit 6 --size 640x360 /sdcard/Download/p3-clip.mp4'], {
    windowsHide: true,
    stdio: 'ignore',
  });
  const recorded = new Promise((resolve) => recorder.on('exit', resolve));
  await sleep(500);
  const { w } = device.displaySize();
  for (let i = 0; i < 8; i++) {
    device.swipe(w / 2, i % 2 ? 300 : 650, w / 2, i % 2 ? 650 : 300, 300);
    await sleep(300);
  }
  await recorded;
  const local = join(RUN_DIR, `p3-clip-${RUN}.mp4`);
  device.adb(['pull', '/sdcard/Download/p3-clip.mp4', local]);
  device.shell('rm -f /sdcard/Download/p3-clip.mp4');
  await put('clip.mp4', readFileSync(local));
  await waitThumb(host, files['a1.png'].node.id);
  log(`测试文件:${Object.keys(files).join(' ')}(视频 ${files['clip.mp4'].bytes.length} 字节)`);
}

/** 文件页进入工作目录 */
async function openWorkFolder() {
  await app.gotoTab('文件');
  if (app.title(device.dump()) === work.name) return;
  for (let i = 0; i < 4 && app.title(device.dump()) !== '文件'; i++) {
    device.back();
    await sleep(600);
  }
  app.pullToRefresh();
  await sleep(1500);
  await device.tap({ text: work.name });
  await device.waitFor((t) => app.title(t) === work.name && t, { label: '进入工作目录' });
}

/** 工作目录列表里找到某一行(先看当前屏,没有就回到顶部再一屏屏往下找) */
async function scrollToRow(name) {
  await openWorkFolder();
  const find = () => device.dump().first({ text: name, filter: (n) => n.cy > 120 });
  let node = find();
  if (!node) {
    const { w } = device.displaySize();
    for (let i = 0; i < 3; i++) {
      device.swipe(w / 2, 250, w / 2, 700, 120);
      await sleep(400);
    }
    for (let i = 0; i < 6 && !(node = find()); i++) {
      app.scrollUp(300);
      await sleep(600);
    }
  }
  if (!node) throw new Error(`列表里找不到 ${name}`);
  return node;
}

/** 在工作目录列表里点开一个文件:先看当前屏,没有就回到顶部再一屏屏往下找 */
async function openFile(name) {
  await openWorkFolder();
  const find = () => device.dump().first({ text: name, filter: (n) => n.cy > 120 });
  let node = find();
  if (!node) {
    const { w } = device.displaySize();
    for (let i = 0; i < 3; i++) {
      device.swipe(w / 2, 250, w / 2, 700, 120);
      await sleep(400);
    }
    for (let i = 0; i < 6 && !(node = find()); i++) {
      app.scrollUp(300);
      await sleep(600);
    }
  }
  if (!node) throw new Error(`列表里找不到 ${name}`);
  device.tapXY(node.cx, node.cy);
  return waitPreview(name);
}

// ---------------- 场景:预览 ----------------

await scenario('V0', '登录并准备测试文件', async () => {
  device.clearData();
  device.shell('rm -rf /sdcard/Download/gopan');
  await uploadFixtures();
  await ensureLoggedIn();
  await openWorkFolder();
  await device.waitFor({ text: 'a1.png' }, { timeout: 15000, label: '工作目录里的文件' });
});

await scenario('V1', '图片:点开预览、放大时不翻页、左右翻、单击收起顶栏、返回列表', async () => {
  await openFile('a1.png');
  await device.waitFor({ id: 'preview_image' }, { label: '图片' });
  const sub = previewSubtitle();
  expect(/^1 \/ 10 · /.test(sub), `位置应为 1 / 10:${sub}`);
  // 等大图加载完再双击
  await sleep(2500);
  const c = screenCenter();
  doubleTap(c.x, c.y);
  await sleep(900);
  swipePage(true);
  await sleep(1200);
  expect(previewTitle() === 'a1.png' || !device.dump().has({ id: 'preview_title' }), '放大时左右滑不应翻页');
  await showChrome();
  expect(previewTitle() === 'a1.png', `放大时左右滑不应翻页(现在是 ${previewTitle()})`);
  doubleTap(c.x, c.y); // 复位
  await sleep(900);
  await showChrome();
  swipePage(true);
  await device.waitFor((t) => previewTitle(t) === 'a2.png' && t, { timeout: 8000, label: '左滑到 a2.png' });
  expect(/^2 \/ 10 · /.test(previewSubtitle()), `位置应为 2 / 10:${previewSubtitle()}`);
  swipePage(false);
  await device.waitFor((t) => previewTitle(t) === 'a1.png' && t, { timeout: 8000, label: '右滑回 a1.png' });
  // 单击收起顶栏(系统栏一起收),再单击呼出
  await sleep(800);
  device.tapXY(c.x, c.y);
  await device.waitGone({ id: 'preview_title' }, { timeout: 5000, label: '顶栏收起' });
  // 系统第一次进沉浸模式会弹「Viewing full screen」说明,点掉
  await sleep(800);
  const gotIt = device.dump().first({ text: 'Got it' });
  if (gotIt) {
    device.tapXY(gotIt.clickableAncestor().cx, gotIt.clickableAncestor().cy);
    await sleep(600);
  }
  device.tapXY(c.x, c.y);
  await device.waitFor({ id: 'preview_title' }, { timeout: 5000, label: '顶栏呼出' });
  await closePreview();
  const t = device.dump();
  expect(app.isMain(t), '返回后应回到带底部导航的列表');
  expect(app.title(t) === work.name, `返回后应在工作目录:${app.title(t)}`);
});

await scenario('V2', '视频:流式播放(时长读出来),退出即释放', async () => {
  await openFile('clip.mp4');
  // PlayerView 是安卓 View,Compose 的 testTag 不出现在无障碍树里:认它自己的控件 id
  await device.waitFor({ idContains: 'exo_' }, { label: '视频播放器' });
  await sleep(2500);
  // 呼出播放控件读时长
  let duration = null;
  for (let i = 0; i < 5 && !duration; i++) {
    const t = device.dump();
    const d = t.first({ id: 'exo_duration' });
    if (d && /\d+:\d\d/.test(d.text) && d.text !== '00:00') duration = d.text;
    else {
      const c = screenCenter();
      device.tapXY(c.x, c.y);
      await sleep(800);
    }
  }
  expect(duration, '播放器应读出视频时长(地址可播)');
  log(`视频时长 ${duration}`);
  const errors = device.adb(['logcat', '-d', '-s', 'ExoPlayerImplInternal:E'], { check: false }).stdout || '';
  expect(!/Playback error/.test(errors), `播放出错:${errors.slice(-400)}`);
  await closePreview();
});

await scenario('V3', '音频:自动播放、离开预览后台继续、回来可暂停', async () => {
  await openFile('tone.wav');
  await device.waitFor({ id: 'preview_audio' }, { label: '音频页' });
  await device.waitFor({ desc: '暂停' }, { timeout: 20000, label: '自动播放(按钮变成暂停)' });
  await waitServer(async () => sessionState() === 'PLAYING', { timeout: 15000, interval: 1000, label: '媒体会话 PLAYING' });
  await closePreview();
  await sleep(4000);
  expect(sessionState() === 'PLAYING', `离开预览后应继续播放:${sessionState()}`);
  const notif = device.shell('dumpsys notification --noredact');
  expect(notif.includes(`pkg=${APP_ID}`) && /MediaStyle|media3|category=transport/.test(notif), '应有媒体通知');
  // 回到这首:还是在放的这首(不从头开始),点暂停
  await openFile('tone.wav');
  await device.waitFor({ desc: '暂停' }, { timeout: 10000, label: '回来仍在播放' });
  await device.tap({ desc: '暂停' });
  await device.waitFor({ desc: '播放' }, { timeout: 8000, label: '已暂停' });
  await waitServer(async () => sessionState() === 'PAUSED', { timeout: 10000, interval: 1000, label: '媒体会话 PAUSED' });
  await closePreview();
});

await scenario('V4', 'PDF:逐页渲染、页码、点页全屏放大', async () => {
  await openFile('doc.pdf');
  await device.waitFor({ id: 'preview_pdf_page' }, { timeout: 30000, label: 'PDF 页面' });
  await device.waitFor({ id: 'preview_pdf_indicator', text: '1 / 3' }, { label: '页码 1 / 3' });
  await device.tap({ id: 'preview_pdf_page' });
  await device.waitFor({ id: 'preview_pdf_zoom' }, { label: '单页全屏' });
  expect(device.dump().has({ text: '1 / 3' }), '单页全屏显示页码');
  device.back();
  await device.waitGone({ id: 'preview_pdf_zoom' }, { label: '关闭单页' });
  expect(device.dump().has({ id: 'preview_pdf' }), '关闭单页后仍在 PDF 预览');
  await closePreview();
});

await scenario('V5', '文本:UTF-8 中文、GBK 老文件、700 KB 日志分段读(512 KB 一段)', async () => {
  await openFile('note.txt');
  await device.waitFor({ textContains: '第 1 行 你好 gopan' }, { label: 'UTF-8 文本' });
  await closePreview();
  await openFile('gbk.txt');
  await device.waitFor({ textContains: '中文老文件 GBK' }, { label: 'GBK 文本按 GB18030 解码' });
  await closePreview();
  await openFile('big.log');
  await device.waitFor({ textContains: 'log line 000000' }, { label: '大文本显示开头' });
  // 第一段 512 KB:开头那条显示「已显示 512.0 KB / 共 …」,点「继续加载」读第二段,读完那条消失
  const banner = await device.waitFor({ id: 'preview_text_more_top' }, { label: '只读了一部分的提示' });
  const t0 = device.dump();
  expect(t0.has({ textContains: '已显示 512.0 KB / 共', within: banner }), '提示里有已读 / 总大小');
  await device.tap({ id: 'preview_text_load_more_top' });
  await device.waitGone({ id: 'preview_text_more_top' }, { timeout: 20000, label: '第二段读完' });
  expect(device.dump().has({ textContains: 'log line 000000' }), '开头仍在');
  expect(!device.dump().has({ id: 'preview_text_load_more' }), '读完后不再有「继续加载」');
  await closePreview();
});

await scenario('V6', 'Office:转换不出来时给出「用其他应用打开」,失败可重试', async () => {
  // 本地栈:没配 Gotenberg 时是 UNAVAILABLE;配了但转不了(假文件 / 转换服务不通)时是 FAILED
  await openFile('report.docx');
  const panel = await device.waitFor(
    (t) => (t.has({ id: 'preview_office_unavailable' }) && 'unavailable') || (t.has({ id: 'preview_office_failed' }) && 'failed'),
    { timeout: 60000, label: 'Office 提示(未开启 / 转换失败)' },
  );
  log(`Office:${panel}`);
  expect(device.dump().has({ text: '用其他应用打开' }), '有「用其他应用打开」');
  if (panel === 'failed') {
    await device.tap({ text: '重试转换' });
    await device.waitFor((t) => t.has({ id: 'preview_office_converting' }) || t.has({ id: 'preview_office_failed' }), {
      timeout: 10000,
      label: '重试后进入转换中(或很快又失败)',
    });
    await device.waitFor({ id: 'preview_office_failed' }, { timeout: 90000, label: '重试后的结果' });
  }
  await closePreview();
});

await scenario('V7', '不支持的类型:提示并可直接下载', async () => {
  await openFile('data.bin');
  await device.waitFor({ id: 'preview_unsupported' }, { label: '不支持预览' });
  await device.tap({ text: '下载' });
  await dismissPermissionDialog();
  const want = sha256(files['data.bin'].bytes);
  await waitServer(async () => deviceSha('/sdcard/Download/gopan/data.bin') === want, {
    timeout: 60000,
    interval: 2000,
    label: 'Download/gopan/data.bin 下载完成且 sha 一致',
  });
  await closePreview();
});

await scenario('V8', '传输助手:点图片进预览,左右翻会话里的文件,返回不跳到底', async () => {
  const folder = (await host.q('mutation { ensureChatFolder { id } }')).ensureChatFolder.id;
  const n1 = `chat-${RUN}-1.png`;
  const n2 = `chat-${RUN}-2.png`;
  await host.upload(folder, n1, await makePng(300, 200, 31));
  await sleep(1500);
  await host.upload(folder, n2, await makePng(300, 200, 32));
  await app.gotoTab('传输助手');
  // 两张相隔不到 2 分钟,合成网格;点第二张
  const cell = await device.waitFor(
    (t) => {
      const cells = t.find({ id: 'chat_grid_cell' });
      return cells.length >= 2 && cells;
    },
    { timeout: 30000, label: '网格出现' },
  );
  const last = cell.sort((a, b) => b.cy - a.cy || b.cx - a.cx)[0];
  device.tapXY(last.cx, last.cy);
  await waitPreview(n2);
  const sub = previewSubtitle();
  expect(/^\d+ \/ \d+ · /.test(sub), `传输助手的预览应可左右翻:${sub}`);
  swipePage(false);
  await device.waitFor((t) => previewTitle(t) === n1 && t, { timeout: 8000, label: `右滑到上一张 ${n1}` });
  await closePreview();
  await device.waitFor({ id: 'chat_input' }, { label: '回到传输助手' });
});

await scenario('V9', '搜索结果点文件进预览', async () => {
  await app.gotoTab('文件');
  for (let i = 0; i < 4 && app.title(device.dump()) !== '文件'; i++) {
    device.back();
    await sleep(600);
  }
  await device.tap({ desc: '搜索' });
  await sleep(600);
  device.type('a2.png');
  const hit = await device.waitFor({ text: 'a2.png', cls: 'TextView' }, { timeout: 15000, label: '搜索结果' });
  device.tapXY(hit.cx, hit.cy);
  await waitPreview('a2.png');
  await device.waitFor({ id: 'preview_image' }, { label: '图片' });
  await closePreview();
  device.back();
  await sleep(600);
});

// ---------------- 场景:分享 ----------------

const SHARE_INFO = 'query($t:String!){ shareInfo(token:$t){ name needPassword expired } }';
const VERIFY = 'mutation($t:String!,$p:String!){ verifySharePassword(token:$t,password:$p){ accessToken } }';

async function myShares() {
  return (await host.q('{ myShares { id token hasPassword expiresAt node { name } visitStats { verifyCount downloadCount } } }')).myShares;
}

/** 前台切到了别的应用(系统选择器、浏览器);返回窗口名 */
async function waitForeignWindow(label, timeout = 20000) {
  return waitServer(
    async () => {
      const w = device.focusedWindow();
      return w && !w.includes('null') && !w.startsWith(`${APP_ID}/`) ? w : null;
    },
    { timeout, interval: 1000, label },
  );
}

async function backToApp() {
  for (let i = 0; i < 4 && !app.inForeground(); i++) {
    device.back();
    await sleep(900);
  }
  if (!app.inForeground()) {
    device.launch();
    await sleep(1500);
  }
}

let sharedToken = null;
await scenario('H1', '文件「分享」:7 天 + 密码 → 链接与密码;访客要密码、错的被拒;「发送」拉起系统分享面板', async () => {
  await scrollToRow('doc.pdf');
  await app.openRowMenu('doc.pdf');
  // 操作表里的「分享」(整行宽的可点项),不是底部导航的「分享」tab
  await device.tap({ text: '分享', filter: (n) => (n.clickableAncestor()?.width ?? 0) > 300 });
  await device.waitFor({ id: 'share_create' }, { label: '分享对话框' });
  await device.tap({ id: 'share_expiry_DAYS_7' });
  await device.tap({ id: 'share_password_switch' });
  const field = await device.waitFor({ id: 'share_password' }, { label: '密码框(预填随机码)' });
  device.tapXY(field.cx, field.cy);
  await sleep(400);
  device.clearFocused(12);
  device.type('e2e123');
  device.hideKeyboard();
  await sleep(400);
  await device.tap({ id: 'share_create' });
  const urlNode = await device.waitFor({ id: 'share_url' }, { timeout: 15000, label: '分享链接' });
  const url = urlNode.text;
  expect(/^http:\/\/localhost:8090\/s\/[\w-]+$/.test(url), `链接格式:${url}`);
  expect(device.dump().has({ id: 'share_password_value', textContains: 'e2e123' }), '显示密码');
  const token = url.split('/s/')[1];
  const s = (await myShares()).find((x) => x.token === token);
  expect(s && s.hasPassword && s.node.name === 'doc.pdf' && s.expiresAt, `服务端有这条分享:${JSON.stringify(s)}`);
  const days = (Date.parse(s.expiresAt) - Date.now()) / 86400000;
  expect(days > 6.9 && days < 7.1, `有效期约 7 天:${days.toFixed(2)}`);

  const guest = new GopanClient();
  const info = (await guest.q(SHARE_INFO, { t: token })).shareInfo;
  expect(info.needPassword && !info.expired && info.name === 'doc.pdf', `访客看到的信息:${JSON.stringify(info)}`);
  let wrongAccepted = true;
  try {
    await guest.q(VERIFY, { t: token, p: 'wrong-pass' });
  } catch {
    wrongAccepted = false;
  }
  expect(!wrongAccepted, '错误密码应被拒绝');
  const v = await guest.q(VERIFY, { t: token, p: 'e2e123' });
  expect(v.verifySharePassword.accessToken, '正确密码拿到访客 token');

  await device.tap({ id: 'share_send' });
  log(`发送 → ${await waitForeignWindow('系统分享面板')}`);
  await backToApp();
  if (device.dump().has({ id: 'share_url' })) await device.tap({ text: '关闭' });
  sharedToken = token;
});

await scenario('H2', '分享页:访问计数、访问记录、「打开链接」交给浏览器、取消分享后链接失效', async () => {
  expect(sharedToken, '依赖 H1 建的分享');
  await app.gotoTab('分享');
  await device.waitFor({ id: 'share_row' }, { timeout: 15000, label: '分享列表' });
  app.pullToRefresh();
  await sleep(2000);
  const findRow = (t) =>
    t.find({ id: 'share_row' }).find((r) => t.has({ text: 'doc.pdf', within: r }) && t.has({ textContains: '访问 1', within: r }));
  const row = await device.waitFor(findRow, { timeout: 15000, label: 'doc.pdf 的分享行显示「访问 1」' });
  device.tapXY(row.cx, row.cy);
  await device.tap({ text: '访问记录' });
  await device.waitFor({ id: 'visit_row' }, { timeout: 15000, label: '访问明细' });
  expect(device.dump().has({ textContains: '打开分享' }), '明细里有一次「打开分享」');
  device.back();
  await device.waitFor({ id: 'share_row' }, { label: '回到分享列表' });

  device.tapXY(findRow(device.dump()).cx, findRow(device.dump()).cy);
  await device.tap({ text: '打开链接' });
  log(`打开链接 → ${await waitForeignWindow('浏览器')}`);
  await backToApp();
  await device.waitFor({ id: 'share_row' }, { label: '回到分享列表' });

  const again = findRow(device.dump());
  device.tapXY(again.cx, again.cy);
  await device.tap({ text: '取消分享' });
  await device.tap({ id: 'shares_revoke_confirm' });
  await waitServer(async () => !(await myShares()).some((x) => x.token === sharedToken), { timeout: 15000, label: '服务端没有这条分享了' });
  const gone = await new GopanClient().raw(SHARE_INFO, { t: sharedToken });
  expect(gone.errors?.length || gone.data?.shareInfo?.expired, `取消后链接应失效:${JSON.stringify(gone)}`);
});

await scenario('H3', '预览里「分享链接」:永久、无密码', async () => {
  await openFile('a2.png');
  await device.tap({ id: 'preview_more' });
  await device.tap({ id: 'preview_share' });
  await device.tap({ id: 'share_expiry_FOREVER' });
  await device.tap({ id: 'share_create' });
  const urlNode = await device.waitFor({ id: 'share_url' }, { timeout: 15000, label: '分享链接' });
  const token = urlNode.text.split('/s/')[1];
  const s = (await myShares()).find((x) => x.token === token);
  expect(s && !s.hasPassword && s.expiresAt === null && s.node.name === 'a2.png', `服务端:${JSON.stringify(s)}`);
  const info = (await new GopanClient().q(SHARE_INFO, { t: token })).shareInfo;
  expect(!info.needPassword, '无密码分享');
  await device.tap({ text: '关闭' });
  await closePreview();
});

// ---------------- 场景:账户 ----------------

/** 当前 tab 里退回根页面(子页面顶栏左上角有「返回」) */
async function backToTabRoot() {
  for (let i = 0; i < 5 && device.dump().has({ desc: '返回' }); i++) {
    device.back();
    await sleep(700);
  }
}

/** 「我的」页里的一项:[title] 可以是文字或正则 */
async function openAccountItem(title) {
  const sel = title instanceof RegExp ? { textMatches: title } : { text: title };
  await app.gotoTab('我的');
  await backToTabRoot();
  for (let i = 0; i < 6 && !device.dump().has(sel); i++) {
    app.scrollUp(400);
    await sleep(500);
  }
  await device.tap(sel);
}

await scenario('A1', '登录设备:本机是「gopan 安卓 App」;吊销另一台设备后它刷新即被登出', async () => {
  const victim = new GopanClient(undefined, { userAgent: `Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0 e2e-${RUN}` });
  await victim.login(stack.other.username, stack.other.password);
  await openAccountItem('登录设备');
  await device.waitFor({ text: '本机' }, { timeout: 15000, label: '本机标记' });
  const t0 = device.dump();
  const mine = t0.find({ id: 'device_row' }).find((r) => t0.has({ text: '本机', within: r }));
  expect(mine && t0.has({ textContains: 'gopan 安卓 App', within: mine }), '本机识别为 gopan 安卓 App');
  // 刚登录的那台排在本机后面(按最近活动),有「吊销」按钮
  const target = (t) =>
    t.find({ id: 'device_row' }).find((r) => t.has({ text: 'Firefox · Linux', within: r }) && t.has({ id: 'device_revoke', within: r }));
  const row = await device.waitFor(target, { timeout: 15000, label: 'Firefox · Linux 那一行' });
  const btn = device.dump().find({ id: 'device_revoke' }).find((b) => b.cy >= row.bounds.y1 && b.cy <= row.bounds.y2);
  device.tapXY(btn.cx, btn.cy);
  await device.tap({ id: 'security_revoke_confirm' });
  await device.waitFor({ text: '已吊销该设备' }, { timeout: 10000, label: '吊销提示' });
  let refreshed = true;
  try {
    await victim.refresh();
  } catch (e) {
    refreshed = false;
    expect(e.code === 'UNAUTHENTICATED', `被吊销的设备刷新应是 UNAUTHENTICATED:${e.message}`);
  }
  expect(!refreshed, '被吊销的设备不能再刷新登录');
  device.back();
  await sleep(600);
});

function basic(user, pass) {
  return `Basic ${Buffer.from(`${user}:${pass}`).toString('base64')}`;
}

async function davStatus(user, pass) {
  const res = await fetch(`http://127.0.0.1:8090/dav/`, {
    method: 'PROPFIND',
    headers: { authorization: basic(user, pass), depth: '0', host: 'localhost:8090' },
    signal: AbortSignal.timeout(20000),
  });
  await res.arrayBuffer();
  return res.status;
}

await scenario('A2', 'WebDAV 应用密码:生成后只显示一次,能挂载;吊销后立即失效', async () => {
  await openAccountItem(/^应用密码/);
  await device.waitFor({ id: 'app_password_name' }, { timeout: 15000, label: '应用密码页' });
  expect(device.dump().has({ text: 'http://localhost:8090/dav' }), '显示 WebDAV 地址');
  await device.tap({ id: 'app_password_name' });
  await sleep(300);
  const name = `nas-${RUN}`;
  device.type(name);
  device.hideKeyboard();
  await device.tap({ id: 'app_password_create' });
  const secret = (await device.waitFor({ id: 'app_password_secret' }, { timeout: 15000, label: '新应用密码' })).text.trim();
  expect(secret.length >= 16, `应用密码:${secret}`);
  const ok = await davStatus(stack.other.username, secret);
  expect(ok === 207, `用应用密码 PROPFIND 应是 207:${ok}`);
  await device.tap({ text: '我已保存' });
  await device.waitGone({ id: 'app_password_secret' }, { label: '明文收起' });
  const t = device.dump();
  const row = t.find({ id: 'app_password_row' }).find((r) => t.has({ text: name, within: r }));
  expect(row, '列表里有这台设备');
  const btn = t.find({ id: 'app_password_revoke' }).find((b) => b.cy >= row.bounds.y1 && b.cy <= row.bounds.y2);
  device.tapXY(btn.cx, btn.cy);
  await device.tap({ id: 'security_revoke_confirm' });
  await waitServer(async () => (await davStatus(stack.other.username, secret)) === 401, { timeout: 15000, label: '吊销后 401' });
  device.back();
  await sleep(600);
});

async function mcpStatus(token) {
  const res = await fetch('http://127.0.0.1:8090/mcp', {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      host: 'localhost:8090',
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'gopan-e2e', version: '1' } },
    }),
    signal: AbortSignal.timeout(20000),
  });
  await res.arrayBuffer();
  return res.status;
}

await scenario('A3', 'Agent 接入:建 API Key(明文只显示一次)能连 MCP;设目录范围;吊销后 401', async () => {
  await openAccountItem('Agent 接入');
  await device.waitFor({ id: 'mcp_copy' }, { timeout: 15000, label: 'Agent 接入页' });
  expect(device.dump().has({ text: 'http://localhost:8090/mcp' }), '显示 MCP 地址');
  await device.tap({ id: 'agent_tab_API_KEYS' });
  await device.tap({ id: 'agent_key_name' });
  await sleep(300);
  const name = `ci-${RUN}`;
  device.type(name);
  device.hideKeyboard();
  await device.tap({ id: 'scope_files:download' });
  await device.tap({ id: 'agent_key_create' });
  const token = (await device.waitFor({ id: 'agent_key_secret' }, { timeout: 15000, label: '新 API Key' })).text.trim();
  expect(/^gopan_(key|mcp)_/.test(token), `Key 前缀:${token.slice(0, 12)}`);
  const ok = await mcpStatus(token);
  expect(ok !== 401 && ok < 500, `用新 Key 连 MCP 应通过认证:${ok}`);
  const keys = (await host.q('{ mcpAPIKeys { id name scopes } }')).mcpAPIKeys;
  const k = keys.find((x) => x.name === name);
  expect(k && [...k.scopes].sort().join(',') === 'files:download,files:read', `服务端的权限:${JSON.stringify(k)}`);
  await device.tap({ text: '我已保存' });

  // 目录范围:限定到本轮工作目录
  const findRow = (t) => t.find({ id: 'agent_key_row' }).find((r) => t.has({ text: name, within: r }));
  // 新 Key 在列表底部;管理员账号(跑过 p4 之后的 other)页面上多出「管理员 MCP」一段,这一行常在屏幕外:往上滚着找到它的范围按钮
  const rootEditIn = (t, r) => t.find({ id: 'agent_root_edit' }).find((b) => b.cy >= r.bounds.y1 && b.cy <= r.bounds.y2);
  let row = null;
  for (let i = 0; i < 8 && !row; i++) {
    const t = device.dump();
    const r = findRow(t);
    if (r && rootEditIn(t, r)) row = r;
    else {
      app.scrollUp(300);
      await sleep(700);
    }
  }
  expect(row, 'Key 那一行');
  const edit = rootEditIn(device.dump(), row);
  device.tapXY(edit.cx, edit.cy);
  await sleep(600);
  device.type(`/${work.name}`);
  await device.tap({ text: '保存范围' });
  await waitServer(
    async () => (await host.q('{ mcpAccessRoots { credentialId rootName } }')).mcpAccessRoots.some((r) => r.credentialId === k.id && r.rootName === work.name),
    { timeout: 15000, label: '服务端记下目录范围' },
  );
  // 那一行在列表底部,范围按钮可能被底部导航挡住:往上滚一点再看
  row = null;
  for (let i = 0; i < 6 && !row; i++) {
    const t = device.dump();
    const r = findRow(t);
    if (r && t.has({ text: `目录范围:${work.name}`, within: r }) && t.has({ id: 'agent_key_row_revoke', within: r })) row = r;
    else {
      app.scrollUp(250);
      await sleep(700);
    }
  }
  expect(row, '行里显示新范围');

  // 吊销
  const revoke = device.dump().find({ id: 'agent_key_row_revoke' }).find((b) => b.cy >= row.bounds.y1 && b.cy <= row.bounds.y2);
  device.tapXY(revoke.cx, revoke.cy);
  await device.tap({ id: 'security_revoke_confirm' });
  await waitServer(async () => (await mcpStatus(token)) === 401, { timeout: 15000, label: '吊销后 MCP 401' });
  device.back();
  await sleep(600);
});

await scenario('T1', '动态取色开关:关掉后冷启动仍是关', async () => {
  await app.gotoTab('我的');
  await backToTabRoot();
  for (let i = 0; i < 6 && !device.dump().has({ id: 'account_dynamic_color' }); i++) {
    app.scrollUp(400);
    await sleep(500);
  }
  const sw = await device.waitFor({ id: 'account_dynamic_color' }, { label: '动态取色开关' });
  expect(sw.checked, '默认开');
  device.tapXY(sw.cx, sw.cy);
  await sleep(1200);
  device.screenshot(join(RUN_DIR, 'p3-static-color.png'));
  app.coldStart();
  await app.waitMain(30000);
  await app.gotoTab('我的');
  for (let i = 0; i < 6 && !device.dump().has({ id: 'account_dynamic_color' }); i++) {
    app.scrollUp(400);
    await sleep(500);
  }
  const after = await device.waitFor({ id: 'account_dynamic_color' }, { label: '动态取色开关' });
  expect(!after.checked, '冷启动后仍是关');
  device.tapXY(after.cx, after.cy); // 恢复
  await sleep(800);
});

// ---------------- 场景:平板 ----------------

await scenario('W1', '平板宽度:侧边导航栏;文件页点文件在右侧栏预览,列表留在左边', async () => {
  const before = device.shell('wm size');
  const density = device.shell('wm density');
  const origSize = (before.match(/Override size: (\d+x\d+)/) || before.match(/Physical size: (\d+x\d+)/))[1];
  const origDensity = (density.match(/Override density: (\d+)/) || density.match(/Physical density: (\d+)/))[1];
  try {
    device.shell('wm size 1280x800');
    device.shell('wm density 160');
    await sleep(2500);
    await device.waitFor({ id: 'main_rail' }, { timeout: 15000, label: '侧边导航栏' });
    expect(!device.dump().has({ id: 'preview_root' }), '还没打开预览');
    // 文件页 → 工作目录 → 点文件
    const tree = device.dump();
    const files = tree.find({ text: '文件' }).sort((a, b) => a.cx - b.cx)[0];
    device.tapXY(files.cx, files.cy);
    await sleep(800);
    if (!device.dump().has({ text: work.name })) {
      for (let i = 0; i < 4; i++) {
        device.back();
        await sleep(600);
        if (device.dump().has({ text: work.name })) break;
      }
    }
    if (!device.dump().has({ text: 'a1.png' })) {
      await device.tap({ text: work.name });
      await device.waitFor({ text: 'a1.png' }, { label: '工作目录' });
    }
    await device.tap({ text: 'a1.png', cls: 'TextView' });
    await device.waitFor({ id: 'preview_pane' }, { timeout: 10000, label: '右侧栏预览' });
    await device.waitFor((t) => previewTitle(t) === 'a1.png' && t, { label: '侧栏标题 a1.png' });
    expect(device.dump().has({ text: 'note.txt' }), '列表仍在左边');
    // 点另一个文件:侧栏换过去
    await device.tap({ text: 'note.txt', cls: 'TextView' });
    await device.waitFor((t) => previewTitle(t) === 'note.txt' && t, { label: '侧栏换成 note.txt' });
    await device.waitFor({ textContains: '第 1 行 你好 gopan' }, { label: '侧栏里的文本' });
    // 关闭侧栏
    await device.tap({ id: 'preview_back' });
    await device.waitGone({ id: 'preview_pane' }, { label: '侧栏收起' });
  } finally {
    device.shell(`wm size ${origSize}`);
    device.shell(`wm density ${origDensity}`);
    await sleep(2000);
  }
  await app.waitMain(15000);
});

// ---------------- 收尾 ----------------

// 本轮工作目录删掉并清空回收站:不然一次次跑下来根目录前面全是 0p3-*,P1 的「首屏有 a」就看不到了
if (work) {
  try {
    await host.deleteNodes([work.id]);
    await host.purgeTrash();
  } catch (e) {
    log(`清理工作目录失败(不影响结果):${e.message}`);
  }
}

// ---------------- 汇总 ----------------

const failed = results.filter((r) => !r.ok);
console.log('\n==== P3 结果 ====');
for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}  (${(r.ms / 1000).toFixed(1)}s)${r.ok ? '' : `\n      ${r.error}\n      截图 ${r.shot}`}`);
console.log(`${results.length - failed.length}/${results.length} 通过`);
process.exit(failed.length ? 1 : 0);
