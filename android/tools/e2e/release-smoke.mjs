// release 包冒烟(连生产,不登录):冷启动到登录页 → 点「使用 Yophon ID 登录」→ Chrome 打开生产账号中心的登录页,
// 授权请求的 client_id / redirect_uri / PKCE 正确 → 返回。用法:node release-smoke.mjs <app-release.apk>
// 结束时卸载 release 包,不把生产包留在模拟器上。
import { App, T } from './app.mjs';
import { CdpSession } from './chrome.mjs';
import { Device } from './device.mjs';
import { log, sleep } from './util.mjs';

const apk = process.argv[2];
if (!apk) throw new Error('用法:node release-smoke.mjs <app-release.apk>');
const PROD_ID = 'https://id.979687.xyz';
const device = new Device();
const app = new App(device);

device.uninstall();
device.install(apk);
try {
  device.clearCrashes();
  app.coldStart();
  await app.waitLogin(30000);
  log('登录页 OK');
  await device.tap(T.idLogin);
  device.forward('tcp:9333', 'localabstract:chrome_devtools_remote');
  let target = null;
  for (let i = 0; i < 60 && !target; i++) {
    const list = await fetch('http://127.0.0.1:9333/json/list').then((r) => r.json()).catch(() => []);
    target = list.find((t) => t.type === 'page' && t.url.startsWith(PROD_ID));
    if (!target) await sleep(1000);
  }
  if (!target) throw new Error('Chrome 里没有打开生产账号中心');
  const session = await CdpSession.open(`ws://127.0.0.1:9333/devtools/page/${target.id}`);
  let href = '';
  for (let i = 0; i < 20; i++) {
    href = await session.eval('location.href');
    if (href.includes('/login')) break;
    await sleep(500);
  }
  session.close();
  const ret = decodeURIComponent(new URL(href).searchParams.get('return') || '');
  const q = new URL(ret, PROD_ID).searchParams;
  const checks = {
    loginPage: href.startsWith(`${PROD_ID}/login`),
    clientId: q.get('client_id') === 'gopan-android',
    redirect: q.get('redirect_uri') === 'xyz.yophon.gopan:/oauthredirect',
    pkce: q.get('code_challenge_method') === 'S256',
    offline: (q.get('scope') || '').split(' ').includes('offline_access'),
  };
  log(JSON.stringify(checks));
  device.back();
  await sleep(1500);
  const crashes = device.crashes();
  if (crashes) throw new Error(`崩溃:${crashes.slice(0, 300)}`);
  if (!Object.values(checks).every(Boolean)) throw new Error('授权请求不符合预期');
  log('RELEASE SMOKE PASSED');
} finally {
  device.removeForward('tcp:9333');
  device.uninstall();
}
