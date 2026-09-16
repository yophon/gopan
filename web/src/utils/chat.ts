import type { ChatMessagesQuery } from '@/api/gen/graphql'

export type ChatMsg = ChatMessagesQuery['chatMessages'][number]

/**
 * 合并轮询增量与本地消息列表:按 id 去重,按 createdAt 升序(服务端 uuid v7
 * 时间有序,createdAt 与 id 序一致;用 createdAt 排序避免字符串 id 比较的误解)。
 * 输入顺序不作假设,重复 id 保留先出现的副本。
 */
export function mergeChatMessages(existing: ChatMsg[], incoming: ChatMsg[]): ChatMsg[] {
  const seen = new Set<string>()
  const out: ChatMsg[] = []
  for (const batch of [existing, incoming]) {
    for (const m of batch) {
      if (seen.has(m.id)) continue
      seen.add(m.id)
      out.push(m)
    }
  }
  out.sort((a, b) => (a.createdAt < b.createdAt ? -1 : a.createdAt > b.createdAt ? 1 : 0))
  return out
}