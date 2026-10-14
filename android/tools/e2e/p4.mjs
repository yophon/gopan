// P4 全链路场景(管理端 / 系统「文件」里的 gopan / 检查更新 / 崩溃日志 / 开源许可 / 联网自动刷新):本地 gopan + 模拟器。
// 前置:node stack.mjs start(gopan 以 GOPAN_APP_RELEASE_DIR=.run/app-release 启动);
//       debug 或 releaseE2e 包用 -Pgopan.baseUrl=http://localhost:8090 -Pgopan.idIssuer=http://localhost:3040 构建并安装。
// 用密码账号 other 登录,先在临时库里把它提升为管理员(gopan admin promote)。
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { App } from './app.mjs';
import { Device } from './device.mjs';
import { GopanClient } from './gql.mjs';
import { APP_ID, GOPAN_HOST_BASE, PORTS, RUN_DIR, ensureRunDir, log, randHex, readStack, run, sleep } from './util.mjs';

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
    const crashes = id === 'K4' ? null : device.crashes();
    if (crashes) throw new Error(`App 崩溃:${crashes.slice(0, 600)}`);
    results.push({ name: `${id} ${name}`, ok: true, ms: Date.now() - t0 });
    log(`PASS ${id} ${name}`);
  } catch (e) {
    const shot = join(RUN_DIR, `p4-fail-${id}.png`);
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

function deviceSha(path) {
  const r = device.adb(['shell', `sha256sum '${path}' 2>/dev/null`], { check: false });
  if (r.status !== 0) return null;
  return (r.stdout || '').trim().split(/\s+/)[0] || null;
}

async function recover() {
  for (let i = 0; i < 6; i++) {
    if (!app.inForeground()) {
      device.back();
      await sleep(700);
      continue;
    }
    const t = device.dump();
    if (app.isMain(t) && !t.has({ desc: '返回' })) return;
    device.back();
    await sleep(700);
  }
  app.coldStart();
  await app.waitMain(30000).catch(() => {});
}

async function waitServer(fn, { timeout = 60000, interval = 1500, label }) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (last) return last;
    await sleep(interval);
  }
  throw new Error(`等待超时:${label}`);
}

async function ensureLoggedIn() {
  app.coldStart();
  const tree = await device.waitFor((t) => (app.isLogin(t) || app.isMain(t)) && t, { timeout: 30000, label: '启动' });
  if (app.isMain(tree)) return;
  await app.passwordLogin(stack.other.username, stack.other.password);
  await app.waitMain(30000);
}

/** 当前 tab 里退回根页面 */
async function backToTabRoot() {
  for (let i = 0; i < 5 && device.dump().has({ desc: '返回' }); i++) {
    device.back();
    await sleep(700);
  }
}

async function openAccountItem(title) {
  const sel = title instanceof RegExp ? { textMatches: title } : { text: title };
  await app.gotoTab('我的');
  await backToTabRoot();
  for (let i = 0; i < 8 && !device.dump().has(sel); i++) {
    app.scrollUp(400);
    await sleep(500);
  }
  await device.tap(sel);
}

// ---------------- 主机侧 ----------------

const host = new GopanClient();
await host.login(stack.other.username, stack.other.password);

/** 在本地临时库里把 other 提升为管理员(gopan admin promote,与生产自举首个管理员的方式相同) */
function promoteOther() {
  run(join(RUN_DIR, process.platform === 'win32' ? 'gopan-e2e.exe' : 'gopan-e2e'), ['admin', 'promote', stack.other.username], {
    env: { ...process.env, GOPAN_DB_URL: stack.dbUrl },
    timeout: 60000,
  });
}

// ---------------- 场景 ----------------

await scenario('K0', '登录,把 other 提升为管理员', async () => {
  promoteOther();
  const me = await host.me();
  expect(me.isAdmin, 'other 应已是管理员');
  device.clearData();
  await ensureLoggedIn();
});

const newUser = `p4u${RUN}`;
await scenario('K1', '管理端:建号、调配额、禁用 / 启用、重置密码,服务端立刻一致', async () => {
  await openAccountItem('管理端');
  await device.waitFor({ id: 'admin_list' }, { timeout: 20000, label: '管理页' });
  await device.tap({ id: 'admin_create' });
  await device.tap({ id: 'admin_new_username' });
  device.type(newUser);
  await device.tap({ id: 'admin_new_password' });
  device.type('pass-1234');
  const quota = device.dump().first({ id: 'admin_new_quota' });
  device.tapXY(quota.cx, quota.cy);
  await sleep(300);
  device.clearFocused(6);
  device.type('3');
  device.hideKeyboard();
  await device.tap({ id: 'admin_create_confirm' });
  const created = await waitServer(
    async () => (await host.q('{ adminUsers { id username quotaBytes disabled } }')).adminUsers.find((u) => u.username === newUser),
    { label: '服务端有新用户' },
  );
  expect(created.quotaBytes === 3 * 2 ** 30, `配额 3 GB:${created.quotaBytes}`);
  const probe = new GopanClient();
  await probe.login(newUser, 'pass-1234');

  // 调配额。测试库里每跑一次 p4 就多一个用户(没有删号接口),新用户那一行常在屏幕外:往上滚着找,
  // 停在屏幕下部时再滚一点(别点到底部导航);找到后再等它的状态(extra)
  const { h: screenH } = device.displaySize();
  const matchRow = (t) => t.find({ id: 'admin_user_row' }).find((r) => t.has({ text: newUser, within: r }));
  const userRow = async (extra) => {
    for (let i = 0; i < 12; i++) {
      const r = matchRow(device.dump());
      if (r && r.bounds.y2 < screenH * 0.75) break;
      app.scrollUp(r ? 250 : 450);
      await sleep(600);
    }
    return device.waitFor(
      (t) => {
        const r = matchRow(t);
        return r && (!extra || t.has({ ...extra, within: r })) ? r : null;
      },
      { timeout: 15000, label: `新用户那一行${extra ? JSON.stringify(extra) : ''}` },
    );
  };
  const row = await userRow();
  device.tapXY(row.cx, row.cy);
  await device.tap({ text: '调整配额' });
  const input = await device.waitFor({ id: 'admin_quota_input' }, { label: '配额输入框' });
  device.tapXY(input.cx, input.cy);
  await sleep(300);
  device.clearFocused(6);
  device.type('5');
  device.hideKeyboard();
  await device.tap({ id: 'admin_quota_save' });
  await waitServer(
    async () => (await host.q('{ adminUsers { username quotaBytes } }')).adminUsers.find((u) => u.username === newUser)?.quotaBytes === 5 * 2 ** 30,
    { label: '配额改成 5 GB' },
  );

  // 禁用 → 登录被拒;启用
  const row2 = await userRow({ textContains: '5.0 GB' });
  device.tapXY(row2.cx, row2.cy);
  await device.tap({ text: '禁用账号' });
  await device.tap({ id: 'admin_confirm' });
  await waitServer(async () => (await host.q('{ adminUsers { username disabled } }')).adminUsers.find((u) => u.username === newUser)?.disabled, {
    label: '已禁用',
  });
  let refused = false;
  try {
    await new GopanClient().login(newUser, 'pass-1234');
  } catch (e) {
    refused = e.code === 'ACCOUNT_DISABLED';
  }
  expect(refused, '禁用后登录应被拒(ACCOUNT_DISABLED)');
  const row3 = await userRow({ text: '已禁用' });
  device.tapXY(row3.cx, row3.cy);
  await device.tap({ text: '启用账号' });
  await device.tap({ id: 'admin_confirm' });
  await waitServer(async () => (await host.q('{ adminUsers { username disabled } }')).adminUsers.find((u) => u.username === newUser)?.disabled === false, {
    label: '已启用',
  });

  // 重置密码:新密码只显示一次,能登录
  await device.waitFor((t) => {
    const r = t.find({ id: 'admin_user_row' }).find((x) => t.has({ text: newUser, within: x }));
    return r && !t.has({ text: '已禁用', within: r });
  }, { timeout: 15000, label: '行里不再显示已禁用' });
  const row4 = await userRow();
  device.tapXY(row4.cx, row4.cy);
  await device.tap({ text: '重置密码' });
  await device.tap({ id: 'admin_confirm' });
  const pwd = (await device.waitFor({ id: 'admin_new_password_value' }, { timeout: 15000, label: '新密码' })).text.trim();
  expect(pwd.length >= 8, `新密码:${pwd}`);
  await new GopanClient().login(newUser, pwd);
  await device.tap({ id: 'admin_new_password_done' });
});

await scenario('K2', '管理端:有失败任务时可以重排', async () => {
  const before = (await host.q('{ adminOverview { taskCounts { status count } } }')).adminOverview.taskCounts.find((t) => t.status === 'failed')?.count ?? 0;
  if (before === 0) {
    log('本地栈没有失败任务,跳过重排(本地栈的 Office 转换服务不通,跑过 p3.mjs 后会有)');
    return;
  }
  if (!device.dump().has({ id: 'admin_list' })) await openAccountItem('管理端');
  app.pullToRefresh();
  await sleep(1500);
  await device.tap({ id: 'admin_retry_failed' });
  await device.waitFor({ textMatches: /^已重排 \d+ 个失败任务$/ }, { timeout: 15000, label: '重排提示' });
});

// ---- 检查更新 ----

const releaseDir = join(RUN_DIR, 'app-release');
await scenario('K3', '检查更新:站点发布了新版本 → 下载、校验 → 交给系统安装器', async () => {
  rmSync(releaseDir, { recursive: true, force: true });
  mkdirSync(releaseDir, { recursive: true });
  // 没发版:提示站点没有安装包
  await openAccountItem('检查更新');
  await device.waitFor({ textContains: '没有提供安卓安装包' }, { timeout: 15000, label: '未发版的提示' });
  await device.tap({ text: '关闭' });

  const apk = randomBytes(300 * 1024);
  const name = `gopan-9.9.9-${RUN}.apk`;
  writeFileSync(join(releaseDir, name), apk);
  writeFileSync(
    join(releaseDir, 'latest.json'),
    JSON.stringify({ versionCode: 999900, versionName: '9.9.9', apk: name, sha256: sha256(apk), size: apk.length, notes: `E2E ${RUN}`, publishedAt: new Date().toISOString() }),
  );
  // 首次安装用的固定链接跳到当前安装包
  const fixed = await fetch(`${GOPAN_HOST_BASE}/api/app/android/latest.apk`, { redirect: 'manual' });
  expect(fixed.status === 302 && fixed.headers.get('location') === `/api/app/android/${name}`, `latest.apk 跳转:${fixed.status} ${fixed.headers.get('location')}`);
  await device.tap({ textMatches: /^检查更新/ });
  await device.waitFor({ id: 'update_version' }, { timeout: 15000, label: '发现新版本' });
  expect(device.dump().has({ textContains: `E2E ${RUN}` }), '显示更新说明');
  await device.tap({ id: 'update_download' });
  await device.waitFor({ id: 'update_install' }, { timeout: 60000, label: '下载并校验完成' });
  await device.tap({ id: 'update_install' });
  // 前台换成系统安装器,或者第一次时的「安装未知应用」授权页
  const w = await waitServer(
    async () => {
      const f = device.focusedWindow();
      return f && !f.includes('null') && !f.startsWith(`${APP_ID}/`) ? f : null;
    },
    { timeout: 20000, interval: 1000, label: '系统安装器 / 授权页' },
  );
  log(`安装 → ${w}`);
  for (let i = 0; i < 4 && !app.inForeground(); i++) {
    device.back();
    await sleep(900);
  }
  if (device.dump().has({ id: 'update_dialog' })) await device.tap({ text: '以后再说' });
  // 「检查更新」那一项标出新版本
  await device.waitFor({ textContains: '有新版本 9.9.9' }, { timeout: 10000, label: '我的页上的新版本标记' });
  rmSync(releaseDir, { recursive: true, force: true });
});

// ---- 崩溃日志 ----

// 抹掉预签名签名与 token 由单测(CrashLogTest)覆盖:am crash 的堆栈里没有这些
await scenario('K4', '崩溃日志:进程崩溃后留下日志(版本、线程、堆栈),可以导出', async () => {
  app.coldStart();
  await app.waitMain(30000);
  // am crash:让 App 主线程抛一个未捕获异常
  device.adb(['shell', 'am', 'crash', APP_ID], { check: false });
  await sleep(3000);
  // 关掉系统的「已停止运行」对话框(有的话)
  const t = device.dump();
  const close = t.first({ text: 'Close app' }) || t.first({ text: 'Close' }) || t.first({ text: '关闭应用' });
  if (close) device.tapXY(close.clickableAncestor().cx, close.clickableAncestor().cy);
  await sleep(1000);
  app.coldStart();
  await app.waitMain(30000);
  await openAccountItem('崩溃日志');
  const row = await device.waitFor({ id: 'crash_row' }, { timeout: 15000, label: '崩溃记录' });
  device.tapXY(row.cx, row.cy);
  const text = await device.waitFor({ id: 'crash_text' }, { label: '日志全文' });
  expect(/gopan .*\(\d+\)/.test(text.text) && /线程 /.test(text.text), `日志头部:${text.text.slice(0, 200)}`);
  await device.tap({ text: '导出' });
  const w = await waitServer(
    async () => {
      const f = device.focusedWindow();
      return f && !f.includes('null') && !f.startsWith(`${APP_ID}/`) ? f : null;
    },
    { timeout: 15000, interval: 1000, label: '系统分享面板' },
  );
  log(`导出 → ${w}`);
  for (let i = 0; i < 4 && !app.inForeground(); i++) {
    device.back();
    await sleep(900);
  }
  if (device.dump().has({ id: 'crash_text' })) device.back();
  await sleep(500);
});

await scenario('K5', '开源许可:列出依赖,看许可证全文', async () => {
  await openAccountItem('开源许可');
  await device.waitFor({ id: 'licenses_list' }, { label: '开源许可' });
  // 列表比一屏长:往上滚到 OkHttp 那一行停在屏幕上部(别点到底部导航)
  const { h } = device.displaySize();
  for (let i = 0; i < 8; i++) {
    const n = device.dump().first({ textContains: 'OkHttp' });
    if (n && n.cy < h * 0.7) break;
    app.scrollUp(300);
    await sleep(500);
  }
  await device.tap({ textContains: 'OkHttp' });
  // 正文很长、在 SelectionContainer 里,uiautomator 不导出它的文字:认对话框标题与正文控件
  await device.waitFor({ id: 'license_text' }, { label: '许可证全文' });
  expect(device.dump().has({ text: 'Apache License 2.0' }), 'OkHttp 是 Apache License 2.0');
  device.screenshot(join(RUN_DIR, 'p4-license.png'));
  device.back();
  await sleep(500);
  // 传递依赖也在表里(在列表末尾)
  for (let i = 0; i < 8 && !device.dump().has({ textContains: 'Tink' }); i++) {
    app.scrollUp(400);
    await sleep(500);
  }
  expect(device.dump().has({ textContains: 'Tink' }), '传递依赖(Tink)也列出来了');
  device.back();
  await sleep(500);
});

// ---- 系统「文件」App 里的 gopan(DocumentsProvider) ----

const FILES_APP = 'com.google.android.documentsui/com.android.documentsui.files.FilesActivity';

/** 「文件」App 抽屉里点一个根:gopan(摘要是用户名)或 Downloads */
async function openRoot(which) {
  await device.tap({ desc: 'Show roots' });
  await sleep(1200);
  const t = device.dump();
  if (which === 'gopan') {
    const summary = t.first({ text: stack.other.username, id: 'android:id/summary' });
    expect(summary, '抽屉里应有 gopan(摘要是当前用户名)');
    device.tapXY(summary.cx, summary.cy - 18);
  } else {
    const item = t.find({ text: which, id: 'android:id/title' })[0];
    expect(item, `抽屉里应有 ${which}`);
    device.tapXY(item.cx, item.cy);
  }
  await sleep(2500);
}

/** 长按选中一个文件 → 选择栏「更多」→「Copy to…」 */
async function startCopy(name) {
  const f = await device.waitFor({ text: name }, { timeout: 20000, label: `文件 ${name}` });
  device.longPress(f, 900);
  await sleep(1500);
  const t = device.dump();
  const sel = t.first({ textMatches: /selected/ });
  const more = t.find({ desc: 'More options' }).find((x) => Math.abs(x.cy - sel.cy) < 40);
  device.tapXY(more.cx, more.cy);
  await sleep(1200);
  await device.tap({ text: 'Copy to…' });
  await sleep(2000);
}

await scenario('K7', '系统「文件」App:浏览 gopan、把 gopan 的文件复制出来、把本机文件复制进 gopan(进上传队列)', async () => {
  const folder = await host.createFolder(null, `0p4-${RUN}`);
  const outBytes = randomBytes(200 * 1024);
  const outName = `out-${RUN}.bin`;
  await host.upload(folder.id, outName, outBytes);
  const inBytes = randomBytes(150 * 1024);
  const inName = `in-${RUN}.bin`;
  const local = join(RUN_DIR, inName);
  writeFileSync(local, inBytes);
  device.adb(['push', local, `/sdcard/Download/${inName}`]);
  device.adb(['shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${inName}`]);
  try {
    device.adb(['shell', 'am', 'start', '-n', FILES_APP]);
    await sleep(2500);
    // 读:gopan → 下载
    await openRoot('gopan');
    await device.waitFor({ textMatches: /^Files from gopan/ }, { label: 'gopan 根目录' });
    await device.tap({ text: folder.name });
    await startCopy(outName);
    await openRoot('Downloads');
    await device.tap({ text: 'COPY' });
    await waitServer(async () => deviceSha(`/sdcard/Download/${outName}`) === sha256(outBytes), {
      timeout: 60000,
      label: '复制出来的文件 sha256 一致',
    });
    // 写:下载 → gopan(写完进上传队列)
    await openRoot('Downloads');
    await startCopy(inName);
    await openRoot('gopan');
    await device.tap({ text: folder.name });
    await sleep(1500);
    await device.tap({ text: 'COPY' });
    const node = await waitServer(
      async () => (await host.allChildren(folder.id)).items.find((n) => n.name === inName && n.size === inBytes.length),
      { timeout: 120000, label: '复制进 gopan 的文件传到服务端' },
    );
    const remote = await host.q('query($id:ID!){ node(id:$id){ sha256 } }', { id: node.id });
    expect(remote.node.sha256 === sha256(inBytes), 'sha256 一致');
  } finally {
    device.shell(`rm -f /sdcard/Download/${outName} /sdcard/Download/${inName}`);
    for (let i = 0; i < 4 && !app.inForeground(); i++) {
      device.back();
      await sleep(700);
    }
    await host.deleteNodes([folder.id]).catch(() => {});
    await host.purgeTrash().catch(() => {});
  }
});

// ---- 联网自动刷新 ----

await scenario('K6', '离线时显示缓存,系统网络恢复后自己刷新(不用下拉)', async () => {
  await app.gotoTab('文件');
  await backToTabRoot();
  device.removeReverse(PORTS.gopan);
  device.removeReverse(PORTS.minio);
  device.shell('cmd connectivity airplane-mode enable');
  try {
    app.coldStart();
    await app.waitMain(30000);
    await device.waitFor({ textMatches: /网络不可用/ }, { timeout: 20000, label: '离线提示条' });
  } finally {
    device.reverse(PORTS.gopan);
    device.reverse(PORTS.minio);
    device.shell('cmd connectivity airplane-mode disable');
  }
  // 认主界面在、提示条没了(只等提示条消失的话,被别的窗口盖住时也会「通过」)
  await device.waitFor((t) => app.isMain(t) && !t.has({ textMatches: /网络不可用/ }) && t, {
    timeout: 45000,
    label: '网络恢复后提示条自己消失',
  });
});

// ---------------- 收尾 ----------------

try {
  const u = (await host.q('{ adminUsers { id username } }')).adminUsers.find((x) => x.username === newUser);
  if (u) await host.q('mutation($id:ID!,$d:Boolean!){ adminSetDisabled(userId:$id, disabled:$d){ id } }', { id: u.id, d: true });
} catch (e) {
  log(`收尾失败(不影响结果):${e.message}`);
}

// ---------------- 汇总 ----------------

const failed = results.filter((r) => !r.ok);
console.log('\n==== P4 结果 ====');
for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}  (${(r.ms / 1000).toFixed(1)}s)${r.ok ? '' : `\n      ${r.error}\n      截图 ${r.shot}`}`);
console.log(`${results.length - failed.length}/${results.length} 通过`);
// 不用 process.exit():Windows 上 fetch 的连接还在关闭时退出会触发 libuv 断言(退出码变成 127)
process.exitCode = failed.length ? 1 : 0;
