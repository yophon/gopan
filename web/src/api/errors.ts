import { getErrorCode } from '@/api/client'

/** 业务错误码 → 中文提示 */
export const errorMessages: Record<string, string> = {
  UNAUTHENTICATED: '登录已过期,请重新登录',
  BAD_CREDENTIALS: '用户名或密码错误',
  NAME_CONFLICT: '同名文件或文件夹已存在',
  CYCLIC_MOVE: '不能移动到自身或其子文件夹中',
  RATE_LIMITED: '操作过于频繁,请稍后再试',
  NOT_IMPLEMENTED: '该功能暂未开放',
  QUOTA_EXCEEDED: '云盘空间不足,无法上传',
  TOO_MANY_SESSIONS: '进行中的上传会话过多,请稍后再试',
  MISSING_PART: '有分片未上传完成,请重试',
  BAD_SESSION_STATE: '上传会话状态异常,请重新上传',
}

/** 从任意错误取中文文案;overrides 用于按场景覆盖(如注册时 NAME_CONFLICT → 用户名已被占用) */
export function errorText(
  err: unknown,
  fallback = '请求失败,请稍后重试',
  overrides?: Record<string, string>,
): string {
  const code = getErrorCode(err)
  if (!code) return fallback
  return overrides?.[code] ?? errorMessages[code] ?? fallback
}
