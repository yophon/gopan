// 主机侧 gopan GraphQL 客户端:造数据、核对服务端状态。协议与 App 相同:
// login/register 拿 15 分钟 access token + Set-Cookie gopan_rt(Path=/query);UNAUTHENTICATED → refresh(cookie)→ 重放一次。
import { createHash } from 'node:crypto';
import http from 'node:http';
import { GOPAN_HOST_BASE, sleep } from './util.mjs';

export class GqlError extends Error {
  constructor(err) {
    super(`${err?.extensions?.code || 'ERROR'}: ${err?.message || JSON.stringify(err)}`);
    this.code = err?.extensions?.code || 'ERROR';
    this.serverMessage = err?.message;
  }
}

const USER_FIELDS = 'id username quotaBytes usedBytes isAdmin';
export const NODE_FIELDS =
  'id parentId name kind size mime createdAt updatedAt deletedAt purgeAt subtreeBytes subtreeCount statsStale preview { kind thumbUrl status }';

export class GopanClient {
  constructor(base = GOPAN_HOST_BASE, { userAgent = null } = {}) {
    this.base = base;
    this.token = null;
    this.refreshCookie = null;
    this.user = null;
    /** 自定义 User-Agent(登录设备列表里据此认出这台「设备」) */
    this.userAgent = userAgent;
  }

  async raw(query, variables = {}, { auth = true } = {}) {
    const headers = { 'content-type': 'application/json' };
    if (this.userAgent) headers['user-agent'] = this.userAgent;
    if (auth && this.token) headers.authorization = `Bearer ${this.token}`;
    if (this.refreshCookie) headers.cookie = `gopan_rt=${this.refreshCookie}`;
    const res = await fetch(`${this.base}/query`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ query, variables }),
      signal: AbortSignal.timeout(60000),
    });
    for (const c of res.headers.getSetCookie?.() || []) {
      const m = c.match(/^gopan_rt=([^;]*)/);
      if (m) this.refreshCookie = m[1] || null;
    }
    const text = await res.text();
    try {
      return JSON.parse(text);
    } catch {
      throw new Error(`HTTP ${res.status} 非 JSON 响应:${text.slice(0, 200)}`);
    }
  }

  /** 发请求;UNAUTHENTICATED 时用 cookie refresh 一次再重放;有 errors 抛 GqlError */
  async q(query, variables = {}) {
    let body = await this.raw(query, variables);
    if (body.errors?.[0]?.extensions?.code === 'UNAUTHENTICATED' && this.refreshCookie) {
      await this.refresh();
      body = await this.raw(query, variables);
    }
    if (body.errors?.length) throw new GqlError(body.errors[0]);
    return body.data;
  }

  /** 登录/注册类调用:被限速(RATE_LIMITED,每 IP 突发 10 次、6 秒回 1 次)时等一等再试 */
  async authCall(field, query, variables) {
    for (let attempt = 0; ; attempt++) {
      const body = await this.raw(query, variables, { auth: false });
      const err = body.errors?.[0];
      if (err?.extensions?.code === 'RATE_LIMITED' && attempt < 8) {
        await sleep(7000);
        continue;
      }
      if (err) throw new GqlError(err);
      this.token = body.data[field].accessToken;
      this.user = body.data[field].user;
      return this.user;
    }
  }

  login(username, password) {
    return this.authCall('login', `mutation($u:String!,$p:String!){login(username:$u,password:$p){accessToken user{${USER_FIELDS}}}}`, {
      u: username,
      p: password,
    });
  }

  register(username, password) {
    return this.authCall(
      'register',
      `mutation($u:String!,$p:String!){register(username:$u,password:$p){accessToken user{${USER_FIELDS}}}}`,
      { u: username, p: password },
    );
  }

  async refresh() {
    const body = await this.raw(`mutation{refresh{accessToken user{${USER_FIELDS}}}}`, {}, { auth: false });
    if (body.errors?.length) throw new GqlError(body.errors[0]);
    this.token = body.data.refresh.accessToken;
    this.user = body.data.refresh.user;
    return this.user;
  }

  async me() {
    return (await this.q(`{me{${USER_FIELDS}}}`)).me;
  }

  /** 一页子节点 */
  async children(parentId = null, { order = 'NAME', desc = false, cursor = null } = {}) {
    const d = await this.q(
      `query($p:ID,$c:String,$o:NodeOrder,$d:Boolean){children(parentId:$p,cursor:$c,order:$o,desc:$d){items{${NODE_FIELDS}} nextCursor total}}`,
      { p: parentId, c: cursor, o: order, d: desc },
    );
    return d.children;
  }

  /** 全部子节点(自动翻页) */
  async allChildren(parentId = null, opts = {}) {
    const items = [];
    let cursor = null;
    let pages = 0;
    let total = 0;
    do {
      const page = await this.children(parentId, { ...opts, cursor });
      items.push(...page.items);
      total = page.total;
      cursor = page.nextCursor;
      pages++;
    } while (cursor && pages < 100);
    return { items, total, pages };
  }

  async childByName(parentId, name) {
    const { items } = await this.allChildren(parentId);
    return items.find((n) => n.name === name) || null;
  }

  async node(id) {
    return (await this.q(`query($id:ID!){node(id:$id){${NODE_FIELDS}}}`, { id })).node;
  }

  async createFolder(parentId, name) {
    return (await this.q(`mutation($p:ID,$n:String!){createFolder(parentId:$p,name:$n){${NODE_FIELDS}}}`, { p: parentId, n: name }))
      .createFolder;
  }

  async rename(id, name) {
    return (await this.q(`mutation($id:ID!,$n:String!){renameNode(id:$id,name:$n){${NODE_FIELDS}}}`, { id, n: name })).renameNode;
  }

  async deleteNodes(ids) {
    return (await this.q(`mutation($ids:[ID!]!){deleteNodes(ids:$ids)}`, { ids })).deleteNodes;
  }

  async trash() {
    const items = [];
    let cursor = null;
    let total = 0;
    do {
      const d = await this.q(`query($c:String){trash(cursor:$c){items{${NODE_FIELDS}} nextCursor total}}`, { c: cursor });
      items.push(...d.trash.items);
      total = d.trash.total;
      cursor = d.trash.nextCursor;
    } while (cursor);
    return { items, total };
  }

  async search(q) {
    return (await this.q(`query($q:String!){searchNodes(q:$q){items{${NODE_FIELDS}} nextCursor total}}`, { q })).searchNodes;
  }

  async purgeTrash() {
    return (await this.q('mutation{purgeTrash}')).purgeTrash;
  }

  /**
   * 走与网页端相同的上传协议:initUpload(sha256 秒传)→ PUT 预签名分片 → completeUpload,
   * UPLOAD_PROCESSING 时每秒轮询。返回节点。
   */
  async upload(parentId, name, bytes) {
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const d = await this.q(
      `mutation($p:ID,$n:String!,$s:String!,$z:Int64!){initUpload(parentId:$p,name:$n,sha256:$s,size:$z){instant node{${NODE_FIELDS}} session{id partSize partUrls{partNumber url} uploadedParts}}}`,
      { p: parentId, n: name, s: sha256, z: bytes.length },
    );
    const init = d.initUpload;
    if (init.instant) return init.node;
    const s = init.session;
    const etags = [];
    for (const part of s.partUrls) {
      if (s.uploadedParts.includes(part.partNumber)) continue;
      const start = (part.partNumber - 1) * s.partSize;
      const etag = await putPresigned(part.url, bytes.subarray(start, Math.min(bytes.length, start + s.partSize)));
      etags.push({ partNumber: part.partNumber, etag });
    }
    const deadline = Date.now() + 120000;
    for (;;) {
      const body = await this.raw(`mutation($id:ID!,$e:[PartEtag!]!){completeUpload(sessionId:$id,etags:$e){${NODE_FIELDS}}}`, {
        id: s.id,
        e: etags,
      });
      const err = body.errors?.[0];
      if (!err) return body.data.completeUpload;
      if (err.extensions?.code === 'UPLOAD_PROCESSING' && Date.now() < deadline) {
        await sleep(1000);
        continue;
      }
      throw new GqlError(err);
    }
  }
}

/**
 * PUT 预签名 URL。URL 的 host 是 localhost:9009(GOPAN_S3_PUBLIC_ENDPOINT,签名含 Host),
 * 主机上连 127.0.0.1 并保留原 Host 头,免得 localhost 解析到 ::1 时隧道不通。
 */
function putPresigned(url, buf) {
  const u = new URL(url);
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: u.hostname === 'localhost' ? '127.0.0.1' : u.hostname,
        port: u.port || 80,
        method: 'PUT',
        path: u.pathname + u.search,
        headers: { host: u.host, 'content-length': buf.length },
        timeout: 60000,
      },
      (res) => {
        let text = '';
        res.on('data', (c) => (text += c));
        res.on('end', () => {
          if (res.statusCode >= 200 && res.statusCode < 300) resolve(String(res.headers.etag || '').replaceAll('"', ''));
          else reject(new Error(`PUT 分片失败 HTTP ${res.statusCode}: ${text.slice(0, 300)}`));
        });
      },
    );
    req.on('timeout', () => req.destroy(new Error('PUT 分片超时')));
    req.on('error', reject);
    req.end(buf);
  });
}

/** 生成一张 PNG(RGB 渐变 + 粗边框),零依赖:zlib 压缩 + 手写 CRC32 */
export async function makePng(width = 256, height = 256, seed = 0) {
  const { deflateSync } = await import('node:zlib');
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y++) {
    const row = y * (width * 3 + 1);
    raw[row] = 0;
    for (let x = 0; x < width; x++) {
      const border = x < 12 || y < 12 || x >= width - 12 || y >= height - 12;
      const i = row + 1 + x * 3;
      raw[i] = border ? 20 : (x * 255) / width;
      raw[i + 1] = border ? 20 : (y * 255) / height;
      raw[i + 2] = border ? 20 : (128 + seed * 37) % 256;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 1 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

let crcTable;
function crc32(buf) {
  if (!crcTable) {
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c >>> 0;
    }
  }
  let c = 0xffffffff;
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
