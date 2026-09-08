export const mcpScopeOptions = [
  { value: 'files:read', label: '浏览与搜索' },
  { value: 'files:download', label: '读取与下载内容' },
  { value: 'files:upload', label: '上传文件' },
  { value: 'files:write', label: '新建、重命名、移动与复制' },
  { value: 'files:delete', label: '移入回收站与恢复' },
  { value: 'shares:read', label: '查看分享' },
  { value: 'shares:write', label: '创建与撤销分享' },
  { value: 'audit:read', label: '查看操作记录' },
] as const

export const mcpAdminScopeOptions = [
  { value: 'admin:read', label: '查看用户、任务与实例统计' },
  { value: 'admin:users', label: '管理账号、配额与密码' },
  { value: 'admin:tasks', label: '重试失败任务' },
  { value: 'admin:purge', label: '永久删除回收站文件' },
] as const

const labels = new Map<string, string>([...mcpScopeOptions, ...mcpAdminScopeOptions].map((item) => [item.value, item.label]))

export function mcpScopeLabel(scope: string): string {
  return labels.get(scope) ?? scope
}
