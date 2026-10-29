/**
 * 助手的权限判断(doc/33 四):规则从 gopan 来,判断交给 tau。
 *
 * 每个 MCP 工具在 _meta["gopan/scopes"] 里写着它可能用到的 scope,服务端的权限表把 scope 分成组,每组
 * allow / ask / deny。这里把它们合成 tau 的 ToolPolicy:一个工具属于几组时取最严的;「这次对话都允许」过的
 * 工具直接放行(破坏性的不给这个选项)。服务端照样按 scope 拒,这里的 deny 只是不让模型白跑一趟。
 *
 * 以后的本机文件工具(doc/33 六)也走这里:工具来源不只 MCP,所以 ToolMeta 不假设名字的格式。
 */

export type Permission = 'allow' | 'ask' | 'deny'

export interface ToolMeta {
  /** 模型看到的名字,例如 mcp__gopan__move_nodes */
  name: string
  /** gopan 工具名,例如 move_nodes */
  tool: string
  scopes: string[]
  readOnly: boolean
  destructive: boolean
}

export interface AccessGroup {
  key: string
  scopes: string[]
  permission: Permission
}

const STRICTNESS: Record<Permission, number> = { allow: 0, ask: 1, deny: 2 }

export const MCP_SERVER = 'gopan'

export function mcpToolName(tool: string): string {
  return `mcp__${MCP_SERVER}__${tool}`.replace(/[^A-Za-z0-9_]/g, '_')
}

/** 权限组 → 每个 scope 的权限 */
export function scopePermissions(groups: readonly AccessGroup[]): Map<string, Permission> {
  const out = new Map<string, Permission>()
  for (const g of groups) for (const s of g.scopes) out.set(s, g.permission)
  return out
}

/** 一个工具该怎么办:不认识的工具一律问 */
export function decide(meta: ToolMeta | undefined, scopes: Map<string, Permission>, sessionAllowed: ReadonlySet<string>): Permission {
  if (!meta || meta.scopes.length === 0) return 'ask'
  let worst: Permission = 'allow'
  for (const s of meta.scopes) {
    const p = scopes.get(s) ?? 'ask'
    if (STRICTNESS[p] > STRICTNESS[worst]) worst = p
  }
  if (worst === 'ask' && !meta.destructive && sessionAllowed.has(meta.name)) return 'allow'
  return worst
}

/** tools/list 的结果 → 名字到元信息 */
export function toolMetas(tools: readonly { name: string; annotations?: { readOnlyHint?: boolean; destructiveHint?: boolean }; _meta?: Record<string, unknown> }[]): Map<string, ToolMeta> {
  const out = new Map<string, ToolMeta>()
  for (const t of tools) {
    const raw = t._meta?.['gopan/scopes']
    const scopes = Array.isArray(raw) ? raw.filter((s): s is string => typeof s === 'string') : []
    const name = mcpToolName(t.name)
    out.set(name, {
      name, tool: t.name, scopes,
      readOnly: t.annotations?.readOnlyHint === true,
      destructive: t.annotations?.destructiveHint === true,
    })
  }
  return out
}
