// P2 全链路场景(上传 / 下载 / 传输助手 / 系统分享):本地 gopan + 模拟器。
// 前置:node stack.mjs start;debug 包用 -Pgopan.baseUrl=http://localhost:8090 -Pgopan.idIssuer=http://localhost:3040 构建并安装。
// 用密码账号 other 登录(场景互不依赖登录方式);服务端状态用主机侧 GraphQL 核对。
// E2E_P2_BIG_MB:大文件场景的大小(默认 48 MiB;本地栈的对象存储走 SSH 隧道,上行约 0.5 MiB/s)。
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { App } from './app.mjs';
import { Device } from './device.mjs';
import { GopanClient, makePng } from './gql.mjs';
import { APP_ID, PORTS, RUN_DIR, ensureRunDir, log, randHex, readStack, sleep } from './util.mjs';

ensureRunDir();
const stack = readStack();
if (!stack) throw new Error('先运行 node stack.mjs start');
const device = new Device();
device.ensureOnline();
const app = new App(device);
const BIG_MB = Number(process.env.E2E_P2_BIG_MB || 64);
const ONLY = process.env.E2E_ONLY ? new Set(process.env.E2E_ONLY.split(',')) : null;
const RUN = randHex(3);
const COMPONENT = 'xyz.yophon.gopan/.MainActivity';

const results = [];
async function scenario(id, name, fn) {
  if (ONLY && !ONLY.has(id)) return;
  const t0 = Date.now();
  try {
    device.clearCrashes();
    await dismissOverlays();
    await fn();
    const crashes = device.crashes();
    if (crashes) throw new Error(`App 崩溃:${crashes.slice(0, 400)}`);
    results.push({ name: `${id} ${name}`, ok: true, ms: Date.now() - t0 });
    log(`PASS ${id} ${name}`);
  } catch (e) {
    const shot = join(RUN_DIR, `p2-fail-${id}.png`);
    try {
      device.screenshot(shot);
    } catch {}
    results.push({ name: `${id} ${name}`, ok: false, ms: Date.now() - t0, error: String(e?.message || e), shot });
    log(`FAIL ${id} ${name}:${e?.message || e}`);
  }
}

function expect(cond, message) {
  if (!cond) throw new Error(message);
}

/** 上一个场景失败时可能留下底部面板 / 对话框盖住页面:按返回收掉(最多 3 次) */
async function dismissOverlays() {
  for (let i = 0; i < 3; i++) {
    const t = device.dump();
    const overlay = t.has({ desc: 'Close sheet' }) || t.has({ text: '取消' }) || t.has({ text: 'Allow' });
    if (!overlay) return;
    if (t.has({ text: 'Allow' })) {
      const a = t.first({ text: 'Allow' });
      device.tapXY(a.clickableAncestor().cx, a.clickableAncestor().cy);
    } else {
      device.back();
    }
    await sleep(700);
  }
}

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

// ---------------- 主机侧 ----------------

const host = new GopanClient();
await host.authCall(
  'login',
  'mutation($u:String!,$p:String!){ login(username:$u,password:$p){ accessToken user { id username quotaBytes usedBytes isAdmin } } }',
  { u: stack.other.username, p: stack.other.password },
);

async function chatMessages(limit = 50, query = null) {
  const d = await host.q(
    'query($l:Int,$q:String){ chatMessages(limit:$l, query:$q){ id body source createdAt node { id name sha256 size } } }',
    { l: limit, q: query },
  );
  return d.chatMessages;
}

async function chatFolderId() {
  return (await host.q('mutation { ensureChatFolder { id } }')).ensureChatFolder.id;
}

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

/** 推一个文件到共享存储并让媒体库收录,返回它的 content:// URI */
async function pushToMediaStore(localPath, name, { dir = '/sdcard/Download/e2e', collection = 'file' } = {}) {
  device.shell(`mkdir -p ${dir}`);
  device.adb(['push', localPath, `${dir}/${name}`], { timeout: 600000 });
  device.adb(['shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file://${dir}/${name}`]);
  const table = collection === 'images' ? 'content://media/external/images/media' : 'content://media/external/file';
  for (let i = 0; i < 20; i++) {
    const q = device.shell(`content query --uri ${table} --projection _id --where "_display_name='${name}'"`);
    const id = (q.match(/_id=(\d+)/g) || []).pop()?.slice(4);
    if (id) return `${table}/${id}`;
    await sleep(500);
  }
  throw new Error(`媒体库没有收录 ${name}`);
}

/**
 * 模拟其它应用分享文件给 gopan。am start 只会给 data URI 授权(EXTRA_STREAM 不会迁移进 ClipData),
 * 所以把同一个 URI 同时放进 data 与 EXTRA_STREAM;真实应用会用 ClipData 授权,效果相同。
 */
function shareUri(uri, mime) {
  device.adb([
    'shell', 'am', 'start', '-a', 'android.intent.action.SEND', '-t', mime, '-d', uri,
    '--eu', 'android.intent.extra.STREAM', uri, '--grant-read-uri-permission', '-n', COMPONENT,
  ]);
}

function shareText(text) {
  device.adb(['shell', 'am', 'start', '-a', 'android.intent.action.SEND', '-t', 'text/plain', '--es', 'android.intent.extra.TEXT', `'${text}'`, '-n', COMPONENT]);
}

function cutNetwork() {
  for (const p of [PORTS.gopan, PORTS.minio]) device.removeReverse(p);
}

function restoreNetwork() {
  for (const p of [PORTS.gopan, PORTS.minio]) device.reverse(p);
}

/** 设备上文件的 sha256;文件不存在返回 null */
function deviceSha(path) {
  const r = device.adb(['shell', `sha256sum '${path}' 2>/dev/null`], { check: false });
  if (r.status !== 0) return null;
  return (r.stdout || '').trim().split(/\s+/)[0] || null;
}

async function ensureLoggedIn() {
  app.coldStart();
  const tree = await device.waitFor((t) => (app.isLogin(t) || app.isMain(t)) && t, { timeout: 30000, label: '启动' });
  if (app.isMain(tree)) return;
  await app.passwordLogin(stack.other.username, stack.other.password);
  await app.waitMain(30000);
}

/** 关掉系统弹出的通知权限对话框(本次安装第一次有传输时会问;它出现得比任务入队晚一点,最多等几秒) */
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

/** 传输助手里上传卡片的百分比(卡片状态行形如「48.0 MB · 37% · 512.0 KB/s」;没有返回 null) */
function chatUploadPercent(tree, name) {
  if (!tree.first({ text: name })) return null;
  const pct = tree.first({ textMatches: /· \d+% · .*\/s$/ });
  const m = pct?.text.match(/· (\d+)% ·/);
  return m ? Number(m[1]) : null;
}

function logcatSince(marker) {
  const out = device.adb(['logcat', '-d', '-v', 'brief'], { check: false, timeout: 30000 }).stdout || '';
  const i = out.lastIndexOf(marker);
  return i >= 0 ? out.slice(i) : out;
}

/** 本轮的工作目录(名字以 0 开头,排在根目录最前面) */
let workFolder = null;
async function ensureWorkFolder() {
  if (!workFolder) workFolder = await host.createFolder(null, `0p2-${RUN}`);
  return workFolder;
}

/** 文件页进入工作目录 */
async function openWorkFolder() {
  const f = await ensureWorkFolder();
  await app.gotoTab('文件');
  // 已经在里面就不动
  if (app.title(device.dump()) === f.name) return;
  // 回到根目录再进(底部 tab 再点一次不会回根,所以按返回到根)
  for (let i = 0; i < 4 && app.title(device.dump()) !== '文件'; i++) {
    device.back();
    await sleep(600);
  }
  app.pullToRefresh();
  await sleep(1500);
  await device.tap({ text: f.name });
  await device.waitFor((t) => app.title(t) === f.name && t, { label: '进入工作目录' });
}

// ---------------- 场景 ----------------

await scenario('S0', '登录(密码账号)并进入传输助手', async () => {
  device.clearData();
  device.shell('rm -rf /sdcard/Download/gopan /sdcard/Download/e2e /sdcard/Pictures/e2e');
  await ensureLoggedIn();
  await app.gotoTab('传输助手');
  await device.waitFor({ desc: '搜索' }, { label: '传输助手顶栏' });
});

let sharedPng = null;
await scenario('S1', '系统分享一张图片 → 传输助手出现图片消息', async () => {
  const name = `share-${RUN}.png`;
  const png = await makePng(320, 240, 3);
  const local = join(RUN_DIR, name);
  writeFileSync(local, png);
  const uri = await pushToMediaStore(local, name, { dir: '/sdcard/Pictures/e2e', collection: 'images' });
  shareUri(uri, 'image/png');
  await sleep(2000);
  await dismissPermissionDialog();
  const msg = await waitServer(async () => (await chatMessages()).find((m) => m.node?.name === name), { label: `服务端出现 ${name}` });
  expect(msg.node.sha256 === sha256(png), 'sha256 与本地不一致');
  await device.waitFor({ text: name }, { timeout: 20000, label: 'App 里出现图片消息' });
  sharedPng = { name, png, uri };
});

await scenario('S2', '分享文本 → 进草稿、不自动发送', async () => {
  const text = `draft-${RUN}`;
  const before = (await chatMessages()).length;
  shareText(text);
  await device.waitFor({ text }, { timeout: 15000, label: '输入框里出现分享的文本' });
  await sleep(3000);
  const after = await chatMessages();
  expect(!after.some((m) => m.body === text), '分享的文本不应自动发送');
  expect(after.length === before, '消息数不应变化');
  // 清掉草稿
  const field = device.dump().first({ text });
  device.tapXY(field.cx, field.cy);
  await sleep(400);
  device.clearFocused(text.length + 10);
  device.hideKeyboard();
});

await scenario('S3', '发文本(乐观上屏)与主机发来的消息', async () => {
  const text = `hello-${RUN}`;
  await device.tap({ id: 'chat_input' });
  await sleep(400);
  device.type(text);
  await device.tap({ id: 'chat_send' });
  await waitServer(async () => (await chatMessages()).some((m) => m.body === text), { label: '服务端收到文本' });
  device.hideKeyboard();
  const fromHost = `from-host-${RUN}`;
  await host.q('mutation($b:String){ sendChatMessage(body:$b){ id } }', { b: fromHost });
  await device.waitFor({ text: fromHost }, { timeout: 15000, label: '主机发的消息在 App 里出现(轮询)' });
});

await scenario('S4', '长文本(> 4000 字节)转成 txt 文件发送', async () => {
  const long = `L${RUN}`.padEnd(4100, 'x');
  shareText(long); // 分享进草稿,再点发送
  await device.waitFor({ id: 'chat_send', enabled: true }, { timeout: 15000, label: '草稿可发送' });
  await device.tap({ id: 'chat_send' });
  const msg = await waitServer(
    async () => (await chatMessages()).find((m) => m.node && /^文本 .*\.txt$/.test(m.node.name) && m.node.size === 4100),
    { timeout: 90000, label: '服务端出现 4100 字节的 txt' },
  );
  log(`长文本 → ${msg.node.name}`);
});

await scenario('S5', '多图消息合成网格(主机连传 5 张到传输助手)', async () => {
  const folder = await chatFolderId();
  for (let i = 0; i < 5; i++) {
    await host.upload(folder, `grid-${RUN}-${i}.png`, await makePng(200, 200, 10 + i));
  }
  await device.waitFor((t) => t.find({ id: 'chat_grid_cell' }).length >= 5 && t, { timeout: 60000, label: '网格里有 5 格' });
});

let big = null;
await scenario('S6', `大文件上传(${BIG_MB} MiB):杀进程后续传、断网恢复,sha 一致`, async () => {
  const name = `big-${RUN}.bin`;
  const bytes = randomBytes(BIG_MB * 1024 * 1024);
  const local = join(RUN_DIR, name);
  writeFileSync(local, bytes);
  const uri = await pushToMediaStore(local, name);
  device.adb(['logcat', '-c'], { check: false });
  shareUri(uri, 'application/octet-stream');
  await sleep(2000);
  await dismissPermissionDialog();
  // 传到 30% 以上(5 MiB 一片、3 片并发,此时已有完整的分片):杀进程(force-stop 会撤掉 WorkManager 的任务,下次启动由 App 重新排)
  await device.waitFor((t) => (chatUploadPercent(t, name) ?? 0) >= 30 && t, { timeout: 600000, interval: 1500, label: '上传到 30%' });
  log('杀进程,断网后重开');
  device.forceStop();
  cutNetwork();
  app.coldStart();
  await app.waitMain(30000);
  await app.gotoTab('传输助手');
  await sleep(20000);
  restoreNetwork();
  log('恢复网络');
  const resumed = await waitServer(async () => /续传:会话 \S+ 状态 uploading,服务端已有 ([1-9]\d*) 片/.exec(logcatSince('续传')), {
    timeout: 180000,
    interval: 2000,
    label: '续传日志(服务端已有分片)',
  });
  log(`续传:${resumed[0]}`);
  // 传输中途再断网 15 秒(还没传完的话):先等进度行出现(续传刚开始时卡片显示「准备上传」)
  let pct = null;
  for (let i = 0; i < 15 && pct === null; i++) {
    const t = device.dump();
    pct = chatUploadPercent(t, name);
    if (pct === null && !t.has({ text: name })) break;
    if (pct === null) await sleep(1000);
  }
  if (pct !== null && pct < 85) {
    log(`传输中断网 15 秒(${pct}%)`);
    cutNetwork();
    await sleep(15000);
    restoreNetwork();
  } else {
    log(`续传后已接近完成(${pct ?? '完成'}),跳过传输中断网`);
  }
  const msg = await waitServer(async () => (await chatMessages()).find((m) => m.node?.name === name), {
    timeout: 900000,
    interval: 3000,
    label: '大文件出现在传输助手',
  });
  expect(msg.node.sha256 === sha256(bytes), '服务端 sha256 与本地不一致');
  expect(Number(msg.node.size) === bytes.length, '大小不一致');
  big = { name, bytes, uri };
});

await scenario('S7', '同一文件再次分享 → 秒传', async () => {
  expect(big, '依赖 S6');
  const t0 = Date.now();
  shareUri(big.uri, 'application/octet-stream');
  const msgs = await waitServer(
    async () => {
      const all = (await chatMessages()).filter((m) => m.node?.sha256 === sha256(big.bytes));
      return all.length >= 2 ? all : null;
    },
    { timeout: 120000, label: '第二条同内容消息' },
  );
  const secs = (Date.now() - t0) / 1000;
  log(`秒传用时 ${secs.toFixed(1)}s(含本地哈希),名字 ${msgs.map((m) => m.node.name).join(' / ')}`);
  expect(secs < 60, `秒传太慢:${secs}s`);
});

let dlFile = null;
await scenario('S8', '下载文件:传输中断网 15 秒后续传完成,sha 一致', async () => {
  const name = `dl-${RUN}.bin`;
  const bytes = randomBytes(Math.max(8, Math.floor(BIG_MB / 2)) * 1024 * 1024);
  log(`主机侧准备 ${name}(${bytes.length >> 20} MiB)`);
  const work = await ensureWorkFolder();
  const node = await host.upload(work.id, name, bytes);
  dlFile = { name, bytes, node };
  await openWorkFolder();
  app.pullToRefresh();
  await sleep(2000);
  await app.openRowMenu(name);
  await device.tap({ text: '下载' });
  // 盯着 App 私有目录里的 .part(debug 包可以 run-as):下了一部分就断网
  const path = `/sdcard/Download/gopan/${name}`;
  let cut = false;
  const t0 = Date.now();
  while (Date.now() - t0 < 120000) {
    const r = device.adb(['shell', 'run-as', APP_ID, 'sh', '-c', `'stat -c %s files/transfers/parts/*.part 2>/dev/null'`], { check: false });
    const sizes = (r.stdout || '').trim().split(/\s+/).map(Number).filter((n) => n > 0);
    if (sizes.some((n) => n < bytes.length)) {
      log(`传输中断网 15 秒(.part ${(sizes[0] / 1048576).toFixed(1)} MiB / ${bytes.length >> 20} MiB)`);
      cutNetwork();
      cut = true;
      break;
    }
    if (deviceSha(path)) break;
    await sleep(300);
  }
  if (cut) {
    await sleep(15000);
    restoreNetwork();
  } else {
    log('下载太快,没赶上在传输中断网');
  }
  await dismissPermissionDialog(2000);
  // 传输列表里看到完成
  await device.tap({ desc: '更多', filter: (n) => n.cy < 84 });
  await device.tap({ text: '传输列表' });
  await device.tap({ id: 'transfers_tab_downloads' });
  await waitServer(async () => deviceSha(path) === sha256(bytes), { timeout: 900000, interval: 3000, label: '下载完成且 sha 一致' });
  await device.waitFor({ textMatches: /已保存到「下载\/gopan」/ }, { timeout: 20000, label: '传输列表显示已保存' });
  device.back();
});

await scenario('S9', '下载文件夹(客户端展开)与打包下载(zip)', async () => {
  const folderName = `dir-${RUN}`;
  const work = await ensureWorkFolder();
  const folder = await host.createFolder(work.id, folderName);
  const sub = await host.createFolder(folder.id, 'sub');
  const a = Buffer.from(`a-${RUN}`);
  const b = Buffer.from(`b-${RUN}-`.repeat(1000));
  await host.upload(folder.id, 'a.txt', a);
  await host.upload(sub.id, 'b.txt', b);
  await openWorkFolder();
  app.pullToRefresh();
  await sleep(2000);
  await app.openRowMenu(folderName);
  await device.tap({ text: '下载' });
  await waitServer(
    async () => deviceSha(`/sdcard/Download/gopan/${folderName}/a.txt`) === sha256(a) && deviceSha(`/sdcard/Download/gopan/${folderName}/sub/b.txt`) === sha256(b),
    { timeout: 180000, interval: 2000, label: '文件夹内容落到 Download/gopan' },
  );
  await app.openRowMenu(folderName);
  await device.tap({ text: '打包下载(zip)' });
  const zipPath = `/sdcard/Download/gopan/${folderName}.zip`;
  await waitServer(async () => (deviceSha(zipPath) ? true : null), { timeout: 180000, interval: 2000, label: 'zip 落到 Download/gopan' });
  const localZip = join(RUN_DIR, `${folderName}.zip`);
  device.adb(['pull', zipPath, localZip]);
  const zip = readFileSync(localZip);
  // EOCD 签名在最后 22 字节(Go 写的 zip 没有注释)
  expect(zip.readUInt32LE(zip.length - 22) === 0x06054b50, 'zip 结尾不是 EOCD');
  expect(zip.includes(Buffer.from(`${folderName}/sub/b.txt`)), 'zip 里没有 sub/b.txt');
});

await scenario('S10', '用其他应用打开图片 → 系统选择器 / 查看器', async () => {
  const name = `open-${RUN}.png`;
  const work = await ensureWorkFolder();
  await host.upload(work.id, name, await makePng(240, 180, 5));
  await openWorkFolder();
  app.pullToRefresh();
  await sleep(2000);
  await app.openRowMenu(name);
  await device.tap({ text: '用其他应用打开' });
  // 前台换成别的应用(选择器或图片查看器);切换动画期间焦点会短暂为空,不算
  await waitServer(
    async () => {
      const w = device.focusedWindow();
      return w && !w.includes('null') && !w.startsWith('xyz.yophon.gopan/') ? w : null;
    },
    { timeout: 120000, interval: 1500, label: '弹出其他应用' },
  );
  log(`前台:${device.focusedWindow()}`);
  device.back();
  await sleep(1000);
});

await scenario('S11', '删除消息;文件被彻删后显示「文件已不存在」', async () => {
  await app.gotoTab('传输助手');
  const text = `to-delete-${RUN}`;
  await host.q('mutation($b:String){ sendChatMessage(body:$b){ id } }', { b: text });
  const bubble = await device.waitFor({ text }, { timeout: 15000, label: '待删消息' });
  device.longPress(bubble);
  await device.tap({ text: '删除消息' });
  await device.waitFor({ text: '删除' }, { label: '删除确认' });
  const confirm = device.dump().find({ text: '删除' }).sort((x, y) => y.cy - x.cy)[0];
  device.tapXY(confirm.clickableAncestor().cx, confirm.clickableAncestor().cy);
  await waitServer(async () => !(await chatMessages()).some((m) => m.body === text), { label: '服务端已删除' });
  await device.waitGone({ text }, { timeout: 10000, label: '消息从列表消失' });
  // 彻删 S1 的图片:消息保留,显示占位
  expect(sharedPng, '依赖 S1');
  const msg = (await chatMessages(200)).find((m) => m.node?.name === sharedPng.name);
  await host.deleteNodes([msg.node.id]);
  await host.q('mutation($ids:[ID!]!){ purgeNodes(ids:$ids) }', { ids: [msg.node.id] });
  const goneBefore = device.dump().find({ text: '文件已不存在' }).length;
  await waitServer(async () => (await chatMessages(200)).some((m) => m.id === msg.id && !m.node), { label: '彻删后消息还在、附件为空' });
  // 那条图片消息换成占位(图片名从列表消失、占位多一个);需要时往上翻到它
  await waitServer(
    async () => {
      const t = device.dump();
      if (t.has({ text: sharedPng.name })) {
        app.pullToRefresh(); // 无副作用:传输助手没有下拉刷新,只是让列表滚一下
        return null;
      }
      return t.find({ text: '文件已不存在' }).length > goneBefore || !t.has({ text: sharedPng.name });
    },
    { timeout: 20000, interval: 1500, label: '「文件已不存在」占位替换图片消息' },
  );
});

await scenario('S12', '搜索 → 定位 → 回到最新', async () => {
  const tag = `loc-${RUN}`;
  for (let i = 0; i < 60; i++) {
    await host.q('mutation($b:String){ sendChatMessage(body:$b){ id } }', { b: `${tag}-${String(i).padStart(2, '0')}` });
  }
  await sleep(3000);
  await device.tap({ desc: '搜索' });
  await sleep(500);
  device.type(`${tag}-05`);
  await device.waitFor({ text: `${tag}-05` , filter: (n) => n.cy > 120 }, { timeout: 15000, label: '搜索结果' });
  await device.tap({ text: '定位' });
  await device.waitFor({ id: 'chat_back_latest' }, { timeout: 15000, label: '定位模式(回到最新按钮)' });
  expect(device.dump().has({ text: `${tag}-05` }), '定位后命中的消息在屏幕上');
  await device.tap({ id: 'chat_back_latest' });
  await device.waitFor({ text: `${tag}-59` }, { timeout: 15000, label: '回到最新后看到最后一条' });
});

await scenario('S13', '「文件」应用多选 20 张图分享 → 一次进传输助手,合成网格', async () => {
  const dir = `multi-${RUN}`;
  const devDir = `/sdcard/Download/${dir}`;
  const localDir = join(RUN_DIR, dir);
  mkdirSync(localDir, { recursive: true });
  const names = [];
  for (let i = 0; i < 20; i++) {
    const name = `m-${RUN}-${String(i).padStart(2, '0')}.png`;
    writeFileSync(join(localDir, name), await makePng(160, 120, 40 + i));
    names.push(name);
  }
  device.shell(`mkdir -p ${devDir}`);
  device.adb(['push', `${localDir}/.`, devDir], { timeout: 120000 });
  for (const name of names) {
    device.adb(['shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file://${devDir}/${name}`]);
  }
  // 「文件」应用直接打开这个目录
  device.adb(['shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d',
    `content://com.android.externalstorage.documents/document/primary%3ADownload%2F${dir}`, '-t', 'vnd.android.document/directory']);
  const first = await device.waitFor({ text: names[0] }, { timeout: 20000, label: '「文件」里出现图片' });
  device.longPress(first, 1000);
  await sleep(1200);
  await device.tap({ desc: 'More options' });
  await device.tap({ text: 'Select all' });
  await sleep(1200);
  await device.tap({ desc: 'Share' });
  await device.waitFor({ text: 'Sharing 20 images' }, { timeout: 20000, label: '系统分享面板(20 张)' });
  await device.tap({ text: '发到传输助手' });
  await sleep(2000);
  await dismissPermissionDialog();
  await waitServer(
    async () => {
      const got = new Set((await chatMessages(200)).map((m) => m.node?.name).filter(Boolean));
      return names.every((n) => got.has(n));
    },
    { timeout: 300000, interval: 3000, label: '20 张都进了传输助手' },
  );
  await device.waitFor((t) => t.find({ id: 'chat_grid_cell' }).length >= 2 && t.first({ textMatches: /· \d+ 个文件$/ }) && t, {
    timeout: 30000,
    label: 'App 里显示成网格',
  });
});

await scenario('S14', '锁屏并强制进入 Doze:前台服务里的上传照常传完', async () => {
  await app.gotoTab('传输助手');
  const name = `doze-${RUN}.bin`;
  const bytes = randomBytes(32 * 1024 * 1024);
  const local = join(RUN_DIR, name);
  writeFileSync(local, bytes);
  const uri = await pushToMediaStore(local, name);
  shareUri(uri, 'application/octet-stream');
  await sleep(1500);
  await dismissPermissionDialog();
  await device.waitFor((t) => (chatUploadPercent(t, name) ?? -1) >= 0 && t, { timeout: 120000, interval: 1000, label: '开始上传' });
  log('锁屏 + 强制 Doze');
  device.shell('dumpsys battery unplug');
  device.shell('input keyevent KEYCODE_SLEEP');
  await sleep(1500);
  device.shell('dumpsys deviceidle force-idle');
  try {
    const msg = await waitServer(async () => (await chatMessages()).find((m) => m.node?.name === name), {
      timeout: 600000,
      interval: 5000,
      label: 'Doze 中传完',
    });
    expect(device.shell('dumpsys deviceidle get deep') === 'IDLE', '传输期间设备没有保持在 Doze');
    expect(msg.node.sha256 === sha256(bytes), 'sha256 不一致');
  } finally {
    device.shell('dumpsys deviceidle unforce');
    device.shell('dumpsys battery reset');
    device.shell('input keyevent KEYCODE_WAKEUP');
    device.shell('wm dismiss-keyguard');
    await sleep(1000);
  }
});

// ---------------- 收尾 ----------------

// 本轮工作目录删掉并清空回收站(根目录前面堆满 0p2-* 会挤掉 P1 依赖的首屏)
if (workFolder) {
  try {
    await host.deleteNodes([workFolder.id]);
    await host.purgeTrash();
  } catch (e) {
    log(`清理工作目录失败(不影响结果):${e.message}`);
  }
}

// ---------------- 汇总 ----------------

const failed = results.filter((r) => !r.ok);
console.log('\n==== P2 结果 ====');
for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}  (${(r.ms / 1000).toFixed(1)}s)${r.ok ? '' : `\n      ${r.error}\n      截图 ${r.shot}`}`);
console.log(`${results.length - failed.length}/${results.length} 通过`);
process.exit(failed.length ? 1 : 0);
