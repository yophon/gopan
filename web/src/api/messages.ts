/** 业务错误码 → 中文提示。零依赖纯模块,单测直接引。 */
export const errorMessages: Record<string, string> = {
  UNAUTHENTICATED: '登录已过期,请重新登录',
  BAD_CREDENTIALS: '用户名或密码错误',
  NAME_CONFLICT: '同名文件或文件夹已存在',
  CYCLIC_MOVE: '不能移动到自身或其子文件夹中',
  CYCLIC_COPY: '不能复制到自身或其子文件夹中',
  RATE_LIMITED: '操作过于频繁,请稍后再试',
  NOT_IMPLEMENTED: '该功能暂未开放',
  QUOTA_EXCEEDED: '云盘空间不足',
  TOO_MANY_SESSIONS: '进行中的上传会话过多,请稍后再试',
  MISSING_PART: '有分片未上传完成,请重试',
  BAD_SESSION_STATE: '上传会话状态异常,请重新上传',
  SHARE_EXPIRED: '分享已过期或被取消',
  SHARE_PASSWORD_REQUIRED: '该分享需要密码',
  BAD_SHARE_PASSWORD: '分享密码错误',
  PACK_TOO_LARGE: '打包内容超过 2GB 上限,请分批下载',
  PACK_BUSY: '打包通道繁忙,稍后再试',
  QUERY_TOO_DEEP: '请求异常,请刷新重试',
  FORBIDDEN: '没有权限执行该操作',
  NOT_FOUND: '对象不存在或已被删除',
}

/** 纯映射:错误码 → 文案,overrides > 全局表 > fallback */
export function messageFor(
  code: string | undefined,
  fallback: string,
  overrides?: Record<string, string>,
): string {
  if (!code) return fallback
  return overrides?.[code] ?? errorMessages[code] ?? fallback
}
