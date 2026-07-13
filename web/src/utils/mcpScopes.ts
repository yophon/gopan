export const mcpScopeOptions = [
  { value: 'files:read', label: '浏览与搜索' },
  { value: 'files:download', label: '读取与下载内容' },
  { value: 'files:upload', label: '上传文件' },
  { value: 'files:write', label: '新建、重命名、移动与复制' },
  { value: 'files:delete', label: '移入回收站与恢复' },
] as const

const labels = new Map<string, string>(mcpScopeOptions.map((item) => [item.value, item.label]))

export function mcpScopeLabel(scope: string): string {
  return labels.get(scope) ?? scope
}
