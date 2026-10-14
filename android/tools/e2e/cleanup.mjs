// 清掉 P2 / P3 / P4 留在 other 账号根目录下的工作目录(0p2-* / 0p3-* / 0p4-*)并清空回收站。
// 只动本地栈的临时数据。各套件结束时会自己清,这个脚本用于中途被打断后的收尾。
import { GopanClient } from './gql.mjs';
import { log, readStack } from './util.mjs';

const stack = readStack();
if (!stack) throw new Error('先运行 node stack.mjs start');

export async function removeWorkFolders(client, pattern = /^0p[234]-/) {
  const { items } = await client.allChildren(null);
  const ids = items.filter((n) => n.kind === 'FOLDER' && pattern.test(n.name)).map((n) => n.id);
  if (ids.length) await client.deleteNodes(ids);
  await client.purgeTrash();
  return ids.length;
}

if (import.meta.url === `file:///${process.argv[1].replaceAll('\\', '/')}` || process.argv[1]?.endsWith('cleanup.mjs')) {
  const host = new GopanClient();
  await host.login(stack.other.username, stack.other.password);
  const n = await removeWorkFolders(host);
  log(`删除了 ${n} 个工作目录并清空回收站`);
}
