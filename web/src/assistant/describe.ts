/**
 * 确认卡上的话(doc/33 一):把工具调用写成「移动 12 项到『报销』」这样的人话,而不是原始参数。
 * ids 是要动的节点,面板再去查名字列出来;paths 是参数里直接写着的路径。
 */

export interface CallDescription {
  title: string
  ids: string[]
  paths: string[]
  detail?: string
}

type Args = Record<string, unknown>

const str = (v: unknown): string | undefined => (typeof v === 'string' && v !== '' ? v : undefined)
const strs = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x !== '') : [])

function count(ids: string[], paths: string[]): string {
  const n = ids.length + paths.length
  return n > 0 ? `${n} 项` : ''
}

function target(args: Args): string {
  const p = str(args.target_path)
  if (p) return `「${p}」`
  return str(args.target_folder_id) ? '目标文件夹' : '根目录'
}

function parentOf(args: Args): string {
  const p = str(args.parent_path)
  if (p) return `「${p}」`
  return str(args.parent_id) ? '所在文件夹' : '根目录'
}

function one(args: Args): { ids: string[]; paths: string[] } {
  return { ids: strs([args.node_id]), paths: strs([args.path]) }
}

export function describeCall(tool: string, args: Args): CallDescription {
  const ids = strs(args.node_ids)
  const paths = strs(args.paths)
  switch (tool) {
    case 'create_folder':
      return { title: `在${parentOf(args)}新建文件夹「${str(args.name) ?? ''}」`, ids: [], paths: [] }
    case 'create_directories':
      return { title: '建出这个路径上缺的文件夹', ids: [], paths: strs([args.path]) }
    case 'rename_node': {
      const o = one(args)
      return { title: `改名为「${str(args.name) ?? ''}」`, ...o }
    }
    case 'move_nodes':
      return { title: `移动 ${count(ids, paths)}到${target(args)}`, ids, paths }
    case 'copy_nodes':
      return { title: `复制 ${count(ids, paths)}到${target(args)}`, ids, paths }
    case 'trash_nodes':
      return { title: `把 ${count(ids, paths)}放进回收站`, ids, paths }
    case 'restore_nodes':
      return { title: `从回收站恢复 ${count(ids, paths)}`, ids, paths }
    case 'batch_nodes': {
      const op = str(args.operation)
      const verb = op === 'move' ? `移动到${target(args)}` : op === 'copy' ? `复制到${target(args)}` : op === 'trash' ? '放进回收站' : op === 'restore' ? '从回收站恢复' : '批量处理'
      return { title: `${count(ids, paths)}${verb}${args.dry_run === true ? '(先试一遍,不真改)' : ''}`, ids, paths }
    }
    case 'extract_archive':
      return { title: `解压到${parentOf(args)}`, ...one(args) }
    case 'fetch_url':
      return { title: `把网址上的文件存到${parentOf(args)}`, ids: [], paths: [], detail: str(args.url) }
    case 'clip_url':
      return { title: '剪藏这个网页', ids: [], paths: [], detail: str(args.url) }
    case 'create_share': {
      const extra = [args.password ? '带密码' : '', args.allow_upload === true ? '允许对方上传' : '', str(args.expires_at) ? `到 ${str(args.expires_at)} 过期` : ''].filter(Boolean).join('、')
      return { title: `建分享链接${extra ? `(${extra})` : ''}`, ...one(args), detail: '拿到链接的人都能看' }
    }
    case 'revoke_share':
      return { title: '吊销一个分享', ids: [], paths: [] }
    case 'create_link':
      return { title: '生成公开直链', ...one(args), detail: '任何人拿到地址都能下载' }
    case 'revoke_link':
      return { title: '吊销一个直链', ids: [], paths: [] }
    case 'create_file_request':
      return { title: `建收集链接「${str(args.title) ?? ''}」`, ids: strs([args.folder_id]), paths: strs([args.path]) }
    case 'revoke_file_request':
      return { title: '吊销一个收集链接', ids: [], paths: [] }
    case 'chat_send':
      return { title: '发到传输助手', ids: strs([args.node_id]), paths: [], detail: str(args.text) }
    default:
      return { title: `调用 ${tool}`, ids: [], paths: [], detail: JSON.stringify(args).slice(0, 300) }
  }
}

// 只读的工具不弹确认,面板上记一行它做了什么
const READ_LABELS: Record<string, (a: Args) => string> = {
  list_files: (a) => `看了${str(a.path) ? `「${str(a.path)}」` : '文件夹'}`,
  search_files: (a) => `搜索「${str(a.query) ?? str(a.q) ?? ''}」`,
  search_file_contents: (a) => `按内容搜索「${str(a.query) ?? str(a.q) ?? ''}」`,
  get_file_info: () => '查看文件信息',
  resolve_path: (a) => `找到「${str(a.path) ?? ''}」`,
  read_text_file: () => '读了文件',
  read_document_text: () => '读了文档',
  list_archive: () => '看了压缩包里有什么',
  list_trash: () => '看了回收站',
  storage_status: () => '查看容量',
  list_shares: () => '看了分享列表',
  list_links: () => '看了直链列表',
  list_file_requests: () => '看了收集链接',
  list_activity: () => '看了动态',
  list_audit: () => '看了访问记录',
  chat_list: () => '看了传输助手',
  get_job: () => '查看任务进度',
  list_jobs: () => '看了任务列表',
}

/** 面板上一行的说法:只读的用短说法,改东西的用确认卡上那句 */
export function toolLabel(tool: string, args: Args): string {
  const read = READ_LABELS[tool]
  return read ? read(args) : describeCall(tool, args).title
}
