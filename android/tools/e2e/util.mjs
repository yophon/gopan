// E2E 公共:路径、端口、工具定位、子进程与小工具函数。零依赖,Node 24。
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const IS_WIN = process.platform === 'win32';
export const E2E_DIR = dirname(fileURLToPath(import.meta.url));
export const RUN_DIR = join(E2E_DIR, '.run');
export const ANDROID_DIR = resolve(E2E_DIR, '../..');
export const REPO_ROOT = resolve(E2E_DIR, '../../..');
export const STACK_JSON = join(RUN_DIR, 'stack.json');

/** 本地栈端口。8080 被本机别的程序占用,gopan 用 8090 */
export const PORTS = { gopan: 8090, id: 3040, minio: 9009, pprof: 6061, cdp: 9333 };

export const GOPAN_BASE = `http://localhost:${PORTS.gopan}`;
/** 主机侧访问 gopan 用 127.0.0.1,免得 localhost 先解析到 ::1 */
export const GOPAN_HOST_BASE = `http://127.0.0.1:${PORTS.gopan}`;
export const ID_ISSUER = `http://localhost:${PORTS.id}`;
export const ID_HOST_BASE = `http://127.0.0.1:${PORTS.id}`;
export const APP_ID = 'xyz.yophon.gopan';

/** 账号中心源码(只读引用,不改其中任何文件) */
export const ID_SRC = process.env.E2E_ID_SRC || resolve(REPO_ROOT, '../yo-style/yophon-id');

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const randHex = (bytes = 3) => randomBytes(bytes).toString('hex');
export const randSecret = (bytes = 24) => randomBytes(bytes).toString('base64url');

export function ensureRunDir() {
  mkdirSync(RUN_DIR, { recursive: true });
  return RUN_DIR;
}

export function readJson(path, fallback = null) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    return fallback;
  }
}

export function writeJson(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(value, null, 2) + '\n');
}

export function readStack() {
  return readJson(STACK_JSON, null);
}

const t0 = Date.now();
export function log(...args) {
  const s = ((Date.now() - t0) / 1000).toFixed(1).padStart(6);
  console.log(`[${s}s]`, ...args);
}

/** 轮询直到 fn 返回真值(返回该值);超时抛错 */
export async function waitUntil(fn, { timeout = 30000, interval = 500, label = 'condition' } = {}) {
  const deadline = Date.now() + timeout;
  let lastErr;
  for (;;) {
    try {
      const v = await fn();
      if (v) return v;
    } catch (e) {
      lastErr = e;
    }
    if (Date.now() > deadline) {
      const why = lastErr ? `: ${lastErr.message || lastErr}` : '';
      throw new Error(`等待超时(${timeout}ms):${label}${why}`);
    }
    await sleep(interval);
  }
}

/** 同步跑一个命令;check=true 时非零退出抛错 */
export function run(cmd, args, { cwd, env, input, check = true, timeout, encoding = 'utf8', shell = false } = {}) {
  const r = spawnSync(cmd, args, { cwd, env, input, timeout, encoding, shell, windowsHide: true, maxBuffer: 256 * 1024 * 1024 });
  if (r.error) {
    if (check) throw new Error(`${cmd} ${args.join(' ')} 启动失败:${r.error.message}`);
  } else if (check && r.status !== 0) {
    const out = encoding ? `${r.stdout || ''}${r.stderr || ''}`.trim().slice(-2000) : '';
    throw new Error(`${cmd} ${args.join(' ')} 退出码 ${r.status}${out ? `\n${out}` : ''}`);
  }
  return r;
}

function firstExisting(cands) {
  return cands.find((p) => p && existsSync(p));
}

/** Windows 上从 PATH 找可执行文件(where.exe);只要 .exe,.cmd 包装器会让 PID 指向 cmd.exe */
function whereExe(name) {
  if (!IS_WIN) {
    const r = spawnSync('which', [name], { encoding: 'utf8' });
    return r.status === 0 ? r.stdout.split(/\r?\n/)[0].trim() : null;
  }
  const r = spawnSync('where.exe', [name], { encoding: 'utf8', windowsHide: true });
  if (r.status !== 0) return null;
  return r.stdout.split(/\r?\n/).map((s) => s.trim()).find((s) => /\.exe$/i.test(s)) || null;
}

export function resolveAdb() {
  const exe = IS_WIN ? 'adb.exe' : 'adb';
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || join(homedir(), '.tools/android-sdk');
  const p = firstExisting([process.env.ADB, join(sdk, 'platform-tools', exe)]) || whereExe('adb');
  if (!p) throw new Error('找不到 adb:设 ANDROID_HOME 或 ADB');
  return p;
}

export function resolveBun() {
  const exe = IS_WIN ? 'bun.exe' : 'bun';
  const p =
    firstExisting([
      process.env.E2E_BUN,
      join(homedir(), '.bun/bin', exe),
      process.env.APPDATA && join(process.env.APPDATA, 'npm/node_modules/bun/bin', exe),
    ]) || whereExe('bun');
  if (!p) throw new Error('找不到 bun:设 E2E_BUN');
  return p;
}

export function resolveGo() {
  const exe = IS_WIN ? 'go.exe' : 'go';
  const p = firstExisting([process.env.E2E_GO, join(homedir(), '.tools/go/bin', exe)]) || whereExe('go');
  if (!p) throw new Error('找不到 go:设 E2E_GO');
  return p;
}

/** 只保留系统变量(与 yophon-id 的 dev-fixture 相同的白名单),账号中心实例不吃本机任何业务配置 */
export function systemEnv() {
  const keep = new Set(['path', 'pathext', 'systemroot', 'windir', 'temp', 'tmp']);
  return Object.fromEntries(Object.entries(process.env).filter(([k, v]) => keep.has(k.toLowerCase()) && v !== undefined));
}

/** HTTP GET 是否 2xx(超时视为否) */
export async function httpOk(url, timeoutMs = 2000) {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
    await res.arrayBuffer();
    return res.ok;
  } catch {
    return false;
  }
}

/** 进程是否还在(不校验身份) */
export function pidAlive(pid) {
  if (!pid) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
}

/** 进程启动时刻(UTC ISO),用来确认 PID 没被系统复用;取不到返回 null */
export function processStartTime(pid) {
  if (!pid) return null;
  if (IS_WIN) {
    const r = spawnSync(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-Command', `(Get-Process -Id ${Number(pid)} -ErrorAction Stop).StartTime.ToUniversalTime().ToString('o')`],
      { encoding: 'utf8', windowsHide: true },
    );
    return r.status === 0 ? r.stdout.trim() || null : null;
  }
  const r = spawnSync('ps', ['-o', 'lstart=', '-p', String(pid)], { encoding: 'utf8' });
  return r.status === 0 ? r.stdout.trim() || null : null;
}

/** 进程映像名(Windows tasklist);非 Windows 返回 comm */
export function processImage(pid) {
  if (!pid) return null;
  if (IS_WIN) {
    const r = spawnSync('tasklist.exe', ['/FI', `PID eq ${Number(pid)}`, '/FO', 'CSV', '/NH'], { encoding: 'utf8', windowsHide: true });
    const m = r.stdout && r.stdout.match(/^"([^"]+)","(\d+)"/m);
    return m && Number(m[2]) === Number(pid) ? m[1] : null;
  }
  const r = spawnSync('ps', ['-o', 'comm=', '-p', String(pid)], { encoding: 'utf8' });
  return r.status === 0 ? r.stdout.trim() || null : null;
}
