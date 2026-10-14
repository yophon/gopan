// P1 网盘场景的测试数据(主机侧 GraphQL 造):根目录 f-001..f-NNN(超过服务端一页 200 条,测分页)、
// 嵌套 a/b/c、根目录与 a/ 里各一张 PNG(走 initUpload → PUT 预签名分片 → completeUpload,第二张命中秒传)、a/note.txt。
import { makePng } from './gql.mjs';
import { log, sleep } from './util.mjs';

/** 服务端 children 一页 200 条(server/internal/service/node.go pageSize),造 205 个保证有第二页 */
export const FOLDER_COUNT = Number(process.env.E2E_FOLDER_COUNT || 205);
export const folderName = (i) => `f-${String(i).padStart(3, '0')}`;
export const PNG_NAME = 'pic.png';
export const NOTE_NAME = 'note.txt';

async function pool(items, concurrency, fn) {
  const queue = [...items];
  const workers = Array.from({ length: concurrency }, async () => {
    while (queue.length) await fn(queue.shift());
  });
  await Promise.all(workers);
}

/** 造数据;返回关键节点 id。client 已登录为目标用户。 */
export async function seedDrive(client) {
  const t0 = Date.now();
  const a = await client.createFolder(null, 'a');
  const b = await client.createFolder(a.id, 'b');
  const c = await client.createFolder(b.id, 'c');
  await pool(
    Array.from({ length: FOLDER_COUNT }, (_, i) => folderName(i + 1)),
    4,
    (name) => client.createFolder(null, name),
  );
  const png = await makePng(512, 512, 1);
  const pic = await client.upload(null, PNG_NAME, png);
  const picA = await client.upload(a.id, PNG_NAME, png); // 同 sha256 → 秒传
  const note = await client.upload(a.id, NOTE_NAME, Buffer.from('gopan e2e note\n'));
  log(`造数据完成:${FOLDER_COUNT} 个 f-*、a/b/c、${PNG_NAME}×2(${png.length} 字节)、${NOTE_NAME},用时 ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  return { a, b, c, pic, picA, note, pngSize: png.length };
}

/** 等缩略图派生完成(preview.thumbUrl 非空) */
export async function waitThumb(client, nodeId, timeout = 90000) {
  const deadline = Date.now() + timeout;
  for (;;) {
    const n = await client.node(nodeId);
    if (n.preview?.thumbUrl) return n;
    if (Date.now() > deadline) throw new Error(`缩略图未就绪:${n.name} preview=${JSON.stringify(n.preview)}`);
    await sleep(1500);
  }
}
