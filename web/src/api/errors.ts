import { getErrorCode } from '@/api/client'
import { messageFor } from '@/api/messages'

export { errorMessages, messageFor } from '@/api/messages'

/** 从任意错误取中文文案;overrides 用于按场景覆盖(如注册时 NAME_CONFLICT → 用户名已被占用) */
export function errorText(
  err: unknown,
  fallback = '请求失败,请稍后重试',
  overrides?: Record<string, string>,
): string {
  return messageFor(getErrorCode(err), fallback, overrides)
}
