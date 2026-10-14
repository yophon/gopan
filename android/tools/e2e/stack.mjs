#!/usr/bin/env node
// 本地全链路栈:账号中心(yophon-id 开发模式隔离实例)+ gopan(当前仓库构建的 Windows 二进制)+ adb reverse。
// 用法:node stack.mjs start [--rebuild] | stop | status
// 运行数据全在 .run/(gitignore):stack.json(账号、密码、user id、端口)、*.pid、*.log、id.db、gopan-e2e.exe。
// 只连临时数据:Postgres 127.0.0.1:5439/gopan_e2e、MinIO 127.0.0.1:9009/gopan-e2e(都是服务器上的一次性容器经 SSH 隧道)。
import { spawn } from 'node:child_process';
import { closeSync, cpSync, existsSync, openSync, readFileSync, renameSync, rmSync, statSync, writeFileSync, writeSync } from 'node:fs';
import { basename, join, resolve as resolvePath } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Device } from './device.mjs';
import { GopanClient, GqlError } from './gql.mjs';
import {
  GOPAN_BASE,
  GOPAN_HOST_BASE,
  ID_HOST_BASE,
  ID_ISSUER,
  ID_SRC,
  IS_WIN,
  PORTS,
  REPO_ROOT,
  RUN_DIR,
  STACK_JSON,
  ensureRunDir,
  httpOk,
  log,
  pidAlive,
  processImage,
  processStartTime,
  randHex,
  randSecret,
  readJson,
  resolveBun,
  resolveGo,
  run,
  systemEnv,
  waitUntil,
  writeJson,
} from './util.mjs';

const GOPAN_EXE = join(RUN_DIR, IS_WIN ? 'gopan-e2e.exe' : 'gopan-e2e');
const DB_URL = process.env.E2E_DB_URL || 'postgres://gopan:gopan@127.0.0.1:5439/gopan_e2e?sslmode=disable';
const S3_ENDPOINT = process.env.E2E_S3_ENDPOINT || '127.0.0.1:9009';
const S3_BUCKET = 'gopan-e2e';
const USER_PASSWORD = 'password123';

const ID_FILES = {
  db: join(RUN_DIR, 'id.db'),
  key: join(RUN_DIR, 'id-key.json'),
  clients: join(RUN_DIR, 'clients.json'),
  dotenv: join(RUN_DIR, '.env'), // 不必存在:只为让 DOTENV_CONFIG_PATH 指向隔离目录
};

// ---------------- 进程记录:.run/<name>.pid = PID / 启动时刻 / 映像名 ----------------

const pidFile = (name) => join(RUN_DIR, `${name}.pid`);

function readPid(name) {
  try {
    const [pid, startTime, image] = readFileSync(pidFile(name), 'utf8').split(/\r?\n/);
    return { pid: Number(pid), startTime: startTime || null, image: image || null };
  } catch {
    return null;
  }
}

/** 记录里的进程是否仍是我们起的那个(PID 在、映像名与启动时刻都对得上) */
function ownProcess(name) {
  const rec = readPid(name);
  if (!rec || !pidAlive(rec.pid)) return null;
  const image = processImage(rec.pid);
  if (rec.image && image && image.toLowerCase() !== rec.image.toLowerCase()) return null;
  if (rec.startTime) {
    const now = processStartTime(rec.pid);
    if (now && now !== rec.startTime) return null;
  }
  return rec;
}

function spawnDetached(name, exe, args, { cwd, env }) {
  const fd = openSync(join(RUN_DIR, `${name}.log`), 'a');
  writeSync(fd, `\n==== ${new Date().toISOString()} ${basename(exe)} ${args.join(' ')}\n`);
  const child = spawn(exe, args, { cwd, env, detached: true, stdio: ['ignore', fd, fd], windowsHide: true });
  child.unref();
  closeSync(fd);
  const startTime = processStartTime(child.pid) || '';
  writeFileSync(pidFile(name), `${child.pid}\n${startTime}\n${basename(exe)}\n`);
  log(`${name} 已启动 pid=${child.pid}`);
  return child.pid;
}

/** 只结束 .pid 记录且身份核对无误的进程 */
async function stopProcess(name) {
  const rec = readPid(name);
  if (!rec) return false;
  const own = ownProcess(name);
  if (!own) {
    log(`${name}: pid ${rec.pid} 已不在或已不是本脚本起的进程,只清理记录`);
    rmSync(pidFile(name), { force: true });
    return false;
  }
  try {
    process.kill(own.pid);
  } catch (e) {
    log(`${name}: 结束 pid ${own.pid} 失败:${e.message}`);
  }
  await waitUntil(() => !pidAlive(own.pid), { timeout: 15000, interval: 300, label: `${name} 退出` }).catch(() => {});
  rmSync(pidFile(name), { force: true });
  log(`${name} 已停止(pid ${own.pid})`);
  return true;
}

/** 端口刚被释放时可能还要等一下;等不到空闲才算被占 */
async function waitPortFree(port, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (await portBusy(port)) {
    if (Date.now() > deadline) throw new Error(`端口 ${port} 被别的进程占用`);
    await new Promise((r) => setTimeout(r, 500));
  }
}

async function portBusy(port) {
  const net = await import('node:net');
  return new Promise((resolve) => {
    const s = net.connect({ host: '127.0.0.1', port });
    s.setTimeout(1000);
    s.on('connect', () => (s.destroy(), resolve(true)));
    s.on('error', () => resolve(false));
    s.on('timeout', () => (s.destroy(), resolve(false)));
  });
}

// ---------------- gopan 二进制 ----------------

/** 影响 gopan 二进制的源码(server/、web/)最后一次提交的时刻;这两处有未提交改动时返回 Infinity(必须重建) */
function serverSourceTime() {
  const dirty = run('git', ['status', '--porcelain', '--', 'server', 'web', ':!server/cmd/gopan/dist'], { cwd: REPO_ROOT }).stdout.trim();
  if (dirty) return Infinity;
  return Number(run('git', ['log', '-1', '--format=%ct', '--', 'server', 'web'], { cwd: REPO_ROOT }).stdout.trim()) * 1000;
}

async function ensureBinary(state, { rebuild }) {
  if (!rebuild && existsSync(GOPAN_EXE) && statSync(GOPAN_EXE).mtimeMs > serverSourceTime()) {
    log(`复用 ${basename(GOPAN_EXE)}(比 HEAD 里 server/、web/ 的最后提交新)`);
    return false;
  }
  // 正在跑的 exe 在 Windows 上被锁,先停
  await stopProcess('gopan');
  log('构建网页端:web → pnpm build');
  run('pnpm build', [], { cwd: join(REPO_ROOT, 'web'), shell: true, timeout: 600000 });
  const distDst = join(REPO_ROOT, 'server/cmd/gopan/dist');
  cpSync(join(REPO_ROOT, 'web/dist'), distDst, { recursive: true, force: true });
  log('构建 gopan(CGO_ENABLED=0)');
  const env = { ...process.env, CGO_ENABLED: '0' };
  if (!env.GOPROXY) env.GOPROXY = 'https://goproxy.cn,direct';
  try {
    run(resolveGo(), ['build', '-o', GOPAN_EXE, './cmd/gopan'], { cwd: join(REPO_ROOT, 'server'), env, timeout: 900000 });
  } finally {
    run('git', ['checkout', '--', 'server/cmd/gopan/dist/.gitkeep'], { cwd: REPO_ROOT, check: false });
  }
  const dirty = run('git', ['status', '--porcelain', '--', 'server/cmd/gopan/dist'], { cwd: REPO_ROOT }).stdout.trim();
  if (dirty) throw new Error(`server/cmd/gopan/dist 残留 git 改动:\n${dirty}`);
  state.binaryBuiltAt = new Date().toISOString();
  log(`已构建 ${GOPAN_EXE}`);
  return true;
}

// ---------------- 账号中心 ----------------

function idEnv() {
  return {
    ...systemEnv(),
    NODE_ENV: 'development',
    HOST: '127.0.0.1',
    PORT: String(PORTS.id),
    ID_ISSUER,
    ID_DB_PATH: ID_FILES.db,
    ID_KEY_PATH: ID_FILES.key,
    ID_CLIENTS_FILE: ID_FILES.clients,
    DOTENV_CONFIG_PATH: ID_FILES.dotenv,
  };
}

function idClients(secret) {
  return [
    {
      id: 'gopan',
      name: 'gopan',
      secret,
      redirectUris: [`${GOPAN_BASE}/api/auth/id/callback`],
      mobileEntry: `${GOPAN_BASE}/api/auth/id/mobile`,
    },
    { id: 'gopan-android', name: 'gopan 安卓', redirectUris: ['xyz.yophon.gopan:/oauthredirect'], delegates: ['gopan'] },
  ];
}

const idReady = () => httpOk(`${ID_HOST_BASE}/.well-known/openid-configuration`);

/** 用账号中心自带的管理脚本(stdin 传 JSON,不进命令行) */
export function idAdmin(command, payload) {
  const r = run(resolveBun(), ['--no-env-file', 'scripts/admin.ts', command], {
    cwd: ID_SRC,
    env: idEnv(),
    input: JSON.stringify(payload),
    timeout: 60000,
  });
  return r.stdout.trim();
}

async function ensureIdServer(state) {
  if (!existsSync(join(ID_SRC, 'src/index.ts'))) throw new Error(`找不到账号中心源码:${ID_SRC}(可设 E2E_ID_SRC)`);
  if (!existsSync(join(ID_SRC, 'frontend/dist/index.html'))) throw new Error(`账号中心前端未构建:${ID_SRC}/frontend/dist`);
  let secretChanged = false;
  if (!state.idClientSecret) {
    state.idClientSecret = randSecret(32);
    secretChanged = true;
  }
  const clientsJson = JSON.stringify(idClients(state.idClientSecret), null, 2) + '\n';
  const oldClients = existsSync(ID_FILES.clients) ? readFileSync(ID_FILES.clients, 'utf8') : '';
  if (oldClients !== clientsJson) {
    writeFileSync(ID_FILES.clients, clientsJson);
    secretChanged = true;
  }
  // 库在但 stack.json 没有 demo 密码(记录丢了):旧库挪开重建,保证账号可用
  if (!state.demo && (existsSync(ID_FILES.db) || existsSync(ID_FILES.key))) {
    await stopProcess('id');
    const stamp = Date.now();
    for (const f of [ID_FILES.db, `${ID_FILES.db}-wal`, `${ID_FILES.db}-shm`, ID_FILES.key]) {
      if (existsSync(f)) renameSync(f, `${f}.orphan-${stamp}`);
    }
    log('账号中心旧库没有对应记录,已挪开重建');
  }
  let running = ownProcess('id') && (await idReady());
  if (running && secretChanged) {
    log('clients.json 变了,重启账号中心');
    await stopProcess('id');
    running = false;
  }
  if (!running) {
    await stopProcess('id');
    await waitPortFree(PORTS.id);
    spawnDetached('id', resolveBun(), ['--no-env-file', 'src/index.ts'], { cwd: ID_SRC, env: idEnv() });
    await waitUntil(idReady, { timeout: 60000, interval: 500, label: '账号中心就绪(/.well-known/openid-configuration)' });
  } else {
    log('复用正在运行的账号中心');
  }
  if (!state.demo) {
    const password = `demo-${randSecret(12)}`;
    const out = idAdmin('create-user', { username: 'demo', name: 'Demo', password });
    const created = JSON.parse(out.slice(out.indexOf('{')));
    state.demo = { username: 'demo', password, sub: created.id };
    log(`账号中心 demo 用户已建:sub=${created.id}`);
  }
}

// ---------------- gopan ----------------

function gopanEnv(state, withId) {
  const base = Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.toUpperCase().startsWith('GOPAN_')));
  const env = {
    ...base,
    GOPAN_LISTEN: `127.0.0.1:${PORTS.gopan}`,
    GOPAN_PPROF_LISTEN: `127.0.0.1:${PORTS.pprof}`,
    GOPAN_DB_URL: DB_URL,
    GOPAN_S3_ENDPOINT: S3_ENDPOINT,
    GOPAN_S3_PUBLIC_ENDPOINT: `localhost:${PORTS.minio}`,
    GOPAN_S3_BUCKET: S3_BUCKET,
    GOPAN_JWT_SECRET: state.jwtSecret,
    GOPAN_DEV_MODE: 'true',
    GOPAN_GOTENBERG_URL: 'http://127.0.0.1:1',
  };
  if (withId) {
    Object.assign(env, {
      GOPAN_ID_ISSUER: ID_ISSUER,
      GOPAN_ID_ORIGIN: GOPAN_BASE,
      GOPAN_ID_CLIENT_ID: 'gopan',
      GOPAN_ID_SECRET: state.idClientSecret,
      GOPAN_ID_SUBJECT: state.demo.sub,
      GOPAN_ID_USER_ID: state.owner.id,
    });
  }
  return env;
}

const gopanReady = () => httpOk(`${GOPAN_HOST_BASE}/healthz`, 5000);

async function startGopan(state, withId) {
  await waitPortFree(PORTS.gopan);
  await waitPortFree(PORTS.pprof);
  spawnDetached('gopan', GOPAN_EXE, [], { cwd: RUN_DIR, env: gopanEnv(state, withId) });
  await waitUntil(gopanReady, { timeout: 120000, interval: 1000, label: 'gopan /healthz' }).catch((e) => {
    const tail = readFileSync(join(RUN_DIR, 'gopan.log'), 'utf8').split(/\r?\n/).slice(-15).join('\n');
    throw new Error(`${e.message}\n--- gopan.log ---\n${tail}`);
  });
  state.gopanMode = withId ? 'id' : 'plain';
}

/** owner 绑定是否生效:owner 用密码登录应得 ID_REQUIRED */
async function ownerBound(state) {
  try {
    await new GopanClient().login(state.owner.username, 'not-the-password');
    return 'LOGGED_IN';
  } catch (e) {
    return e instanceof GqlError ? e.code : `ERR ${e.message}`;
  }
}

async function ensureGopan(state, { binaryRebuilt }) {
  if (!state.jwtSecret) state.jwtSecret = randSecret(32);
  const needUsers = !state.owner || !state.other;
  const rec = ownProcess('gopan');
  if (rec && !needUsers && !binaryRebuilt && state.gopanMode === 'id' && (await gopanReady())) {
    log('复用正在运行的 gopan(已绑定 Yophon ID)');
  } else {
    await stopProcess('gopan');
    if (needUsers) {
      log('gopan 先以无 ID 配置启动,注册 owner / other');
      await startGopan(state, false);
      const suffix = randHex(3);
      const c1 = new GopanClient();
      const owner = await c1.register(`owner_${suffix}`, USER_PASSWORD);
      const c2 = new GopanClient();
      const other = await c2.register(`other_${suffix}`, USER_PASSWORD);
      state.owner = { username: owner.username, password: USER_PASSWORD, id: owner.id };
      state.other = { username: other.username, password: USER_PASSWORD, id: other.id };
      writeJson(STACK_JSON, state);
      log(`已注册 ${owner.username}(${owner.id})与 ${other.username}`);
      await stopProcess('gopan');
    }
    await startGopan(state, true);
  }
  const bound = await ownerBound(state);
  if (bound !== 'ID_REQUIRED') {
    if (bound === 'BAD_CREDENTIALS' && !state.reRegistered) {
      // 库被换过(用户不存在了):清掉记录重注册一次
      log('owner 不存在(库可能被重置),重新注册');
      delete state.owner;
      delete state.other;
      state.reRegistered = true;
      return ensureGopan(state, { binaryRebuilt: false });
    }
    throw new Error(`owner 绑定检查失败:期望 ID_REQUIRED,实际 ${bound}`);
  }
  delete state.reRegistered;
  log(`owner 已绑定 Yophon ID(密码登录 → ID_REQUIRED)`);
}

// ---------------- 命令 ----------------

async function start(args) {
  ensureRunDir();
  const state = readJson(STACK_JSON, {}) || {};
  const device = new Device();
  device.ensureOnline();
  const binaryRebuilt = await ensureBinary(state, { rebuild: args.includes('--rebuild') });
  writeJson(STACK_JSON, state);
  await ensureIdServer(state);
  writeJson(STACK_JSON, state);
  await ensureGopan(state, { binaryRebuilt });
  for (const port of [PORTS.gopan, PORTS.id, PORTS.minio]) device.reverse(port);
  Object.assign(state, {
    serial: device.serial,
    ports: PORTS,
    gopanBase: GOPAN_BASE,
    idIssuer: ID_ISSUER,
    dbUrl: DB_URL,
    s3: { endpoint: S3_ENDPOINT, publicEndpoint: `localhost:${PORTS.minio}`, bucket: S3_BUCKET },
    pids: { id: readPid('id')?.pid, gopan: readPid('gopan')?.pid },
    startedAt: new Date().toISOString(),
  });
  writeJson(STACK_JSON, state);
  log(`栈已就绪:gopan ${GOPAN_BASE}、账号中心 ${ID_ISSUER};adb reverse ${[PORTS.gopan, PORTS.id, PORTS.minio].join('/')} → ${device.serial}`);
  log(`账号:demo(ID)/ ${state.owner.username}(绑定 ID)/ ${state.other.username}(密码);详见 ${STACK_JSON}`);
}

async function stop() {
  ensureRunDir();
  await stopProcess('gopan');
  await stopProcess('id');
  try {
    const device = new Device();
    for (const port of [PORTS.gopan, PORTS.id, PORTS.minio]) device.removeReverse(port);
    device.removeForward(`tcp:${PORTS.cdp}`);
    log(`已移除 ${device.serial} 上的 adb reverse / forward`);
  } catch (e) {
    log(`跳过 adb 清理:${e.message}`);
  }
}

async function status() {
  const state = readJson(STACK_JSON, null);
  const rows = [];
  for (const name of ['id', 'gopan']) {
    const rec = readPid(name);
    const own = ownProcess(name);
    rows.push(`${name.padEnd(6)} pid=${rec?.pid ?? '-'} ${own ? '运行中' : rec ? '记录失效' : '未启动'}`);
  }
  rows.push(`账号中心 ${ID_ISSUER}: ${(await idReady()) ? 'OK' : '不可达'}`);
  rows.push(`gopan ${GOPAN_BASE}: ${(await gopanReady()) ? 'OK' : '不可达'}(模式 ${state?.gopanMode ?? '-'})`);
  try {
    const device = new Device();
    rows.push(`adb reverse(${device.serial}):${device.reverseList().replace(/\r?\n/g, '; ') || '无'}`);
  } catch (e) {
    rows.push(`adb:${e.message}`);
  }
  if (state) {
    rows.push(`demo=${state.demo?.username}(sub ${state.demo?.sub})owner=${state.owner?.username}(${state.owner?.id})other=${state.other?.username}`);
  }
  rows.forEach((r) => console.log(r));
}

// 被 p1.mjs 等 import 时不跑命令行
const isMain = process.argv[1] && resolvePath(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const cmd = process.argv[2];
  const args = process.argv.slice(3);
  const commands = { start, stop, status };
  if (!commands[cmd]) {
    console.error('用法:node stack.mjs start [--rebuild] | stop | status');
    process.exit(2);
  }
  commands[cmd](args).catch((e) => {
    console.error(`stack ${cmd} 失败:${e.stack || e.message}`);
    process.exit(1);
  });
}
