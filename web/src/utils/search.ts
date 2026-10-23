/**
 * 搜索关键词在地址里叫 k(/search?k=…)。以前叫 q,旧链接照样认;
 * 换名字是因为有的浏览器插件会把任意站点的 /search?q= 当成搜索结果页劫持走。
 */
export function searchKeyword(query: Record<string, unknown>): string {
  const k = query.k ?? query.q
  return typeof k === 'string' ? k : ''
}
