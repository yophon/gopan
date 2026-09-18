/**
 * 列表渲染所需的最小节点字段集。
 * 各页面的 GraphQL 选择集不同(children / search / share / chat),这里只约定
 * 共同需要的那部分,组件按结构类型收参 —— 换页面不用改组件。
 */
export interface NodeListItem {
  id: string
  name: string
  kind: string
  /** 生成类型的 size 是可选可空的(文件夹没有),这里跟着放宽 */
  size?: number | null
  /** 回收站/分享列表没有 updatedAt,用它们的自有时间字段顶替(可能是 null) */
  updatedAt?: string | null
  /** 回收站条目:预计被彻删的时刻(deleted_at + TRASH_TTL),其它页面不填 */
  purgeAt?: string | null
  parentId?: string | null
  preview?: { kind: string; thumbUrl?: string | null } | null
  subtreeBytes?: number | null
  subtreeCount?: number | null
  statsStale?: boolean | null
}

/** 长按/⋮ 弹出的操作条目 */
export interface SheetItem {
  key: string
  label: string
  icon?: unknown
  danger?: boolean
  disabled?: boolean
}
