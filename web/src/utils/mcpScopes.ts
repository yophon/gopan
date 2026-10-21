export const mcpScopeOptions = [
  { value: 'files:read', label: '浏览与搜索' },
  { value: 'files:download', label: '读取与下载内容' },
  { value: 'files:upload', label: '上传文件' },
  { value: 'files:write', label: '新建、重命名、移动与复制' },
  { value: 'files:delete', label: '移入回收站与恢复' },
  { value: 'shares:read', label: '查看分享' },
  { value: 'shares:write', label: '创建与撤销分享' },
  { value: 'chat:read', label: '读取文件传输助手消息' },
  { value: 'chat:write', label: '发送消息与文件到文件传输助手' },
  { value: 'audit:read', label: '查看操作记录' },
] as const

export const mcpAdminScopeOptions = [
  { value: 'admin:read', label: '查看用户、任务与实例统计' },
  { value: 'admin:users', label: '管理账号、配额与密码' },
  { value: 'admin:tasks', label: '重试失败任务' },
  { value: 'admin:purge', label: '永久删除回收站文件' },
] as const

/** 图床密钥(doc/22 四.3):只能往图床文件夹传图片,不能和别的 scope 混用,不在 Agent 接入里选 */
export const IMG_UPLOAD_SCOPE = 'img:upload'

export function isImageKey(scopes: readonly string[]): boolean {
  return scopes.includes(IMG_UPLOAD_SCOPE)
}

const labels = new Map<string, string>([
  ...[...mcpScopeOptions, ...mcpAdminScopeOptions].map((item): [string, string] => [item.value, item.label]),
  [IMG_UPLOAD_SCOPE, '图床上传'],
])

export function mcpScopeLabel(scope: string): string {
  return labels.get(scope) ?? scope
}
