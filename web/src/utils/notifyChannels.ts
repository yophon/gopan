/** 推送渠道表单(doc/22 三.2)。纯函数,单测直接引。 */

export type ChannelKindValue = 'WEBHOOK' | 'NTFY' | 'BARK' | 'TELEGRAM'

export interface ChannelKindMeta {
  value: ChannelKindValue
  label: string
  /** url 字段的叫法;没有就不显示 */
  urlLabel?: string
  urlDefault?: string
  urlRequired?: boolean
  topicLabel?: string
  topicPlaceholder?: string
  secretLabel: string
  secretRequired: boolean
  hint: string
}

export const channelKinds: ChannelKindMeta[] = [
  {
    value: 'WEBHOOK',
    label: 'Webhook',
    urlLabel: '地址',
    urlRequired: true,
    secretLabel: '签名密钥',
    secretRequired: false,
    hint: 'POST JSON(kind、level、title、body、url、created_at);填了签名密钥会带 X-Gopan-Signature: sha256=<HMAC>',
  },
  {
    value: 'NTFY',
    label: 'ntfy',
    urlLabel: '服务器',
    urlDefault: 'https://ntfy.sh',
    urlRequired: true,
    topicLabel: 'Topic',
    topicPlaceholder: '订阅的主题名,别用太好猜的',
    secretLabel: '访问令牌',
    secretRequired: false,
    hint: '手机装 ntfy App 订阅同一个 topic;自建服务器需要登录的填访问令牌',
  },
  {
    value: 'BARK',
    label: 'Bark',
    urlLabel: '服务器',
    urlDefault: 'https://api.day.app',
    urlRequired: true,
    secretLabel: 'Device Key',
    secretRequired: true,
    hint: 'iPhone 上的 Bark App 首页能看到 Device Key(地址里 api.day.app/ 后面那段)',
  },
  {
    value: 'TELEGRAM',
    label: 'Telegram',
    topicLabel: 'Chat ID',
    topicPlaceholder: '给机器人发条消息后从 getUpdates 里查,或用 @userinfobot',
    secretLabel: 'Bot Token',
    secretRequired: true,
    hint: '找 @BotFather 建一个机器人拿 Bot Token,先给它发一条消息它才能给你发',
  },
]

export function channelKindMeta(kind: ChannelKindValue): ChannelKindMeta {
  return channelKinds.find((k) => k.value === kind) ?? channelKinds[0]
}

export interface EventKindLike {
  kind: string
  defaultPush: boolean
}

export interface ChannelForm {
  kind: ChannelKindValue
  name: string
  enabled: boolean
  events: string[]
  url: string
  topic: string
  /** 新输入的密钥;空串 = 没动过 */
  secret: string
  /** 点了「清除」 */
  clearSecret: boolean
}

export interface ChannelLike {
  kind: ChannelKindValue
  name: string
  enabled: boolean
  events: string[]
  url: string
  topic: string
}

export function emptyChannelForm(kind: ChannelKindValue, eventKinds: EventKindLike[]): ChannelForm {
  const meta = channelKindMeta(kind)
  return {
    kind,
    name: meta.label,
    enabled: true,
    events: eventKinds.filter((e) => e.defaultPush).map((e) => e.kind),
    url: meta.urlDefault ?? '',
    topic: '',
    secret: '',
    clearSecret: false,
  }
}

export function channelFormFrom(ch: ChannelLike): ChannelForm {
  return {
    kind: ch.kind,
    name: ch.name,
    enabled: ch.enabled,
    events: [...ch.events],
    url: ch.url,
    topic: ch.topic,
    secret: '',
    clearSecret: false,
  }
}

/** 换渠道类型时把服务器地址换成新类型的默认值(用户自己改过的不动) */
export function switchChannelKind(form: ChannelForm, kind: ChannelKindValue): ChannelForm {
  const before = channelKindMeta(form.kind)
  const after = channelKindMeta(kind)
  const untouchedUrl = !form.url || form.url === before.urlDefault
  const untouchedName = !form.name || form.name === before.label
  return {
    ...form,
    kind,
    url: untouchedUrl ? (after.urlDefault ?? '') : form.url,
    name: untouchedName ? after.label : form.name,
  }
}

export interface ChannelInputValue {
  kind: ChannelKindValue
  name: string
  enabled: boolean
  events: string[]
  url?: string | null
  topic?: string | null
  secret?: string
}

/**
 * 表单 → NotifyChannelInput。密钥只写不读:没输入就不传(保持原值),点了清除传空串。
 * 这个类型不用的字段传 null。
 */
export function channelInput(form: ChannelForm): ChannelInputValue {
  const meta = channelKindMeta(form.kind)
  const input: ChannelInputValue = {
    kind: form.kind,
    name: form.name.trim() || meta.label,
    enabled: form.enabled,
    events: [...form.events],
    url: meta.urlLabel ? form.url.trim() : null,
    topic: meta.topicLabel ? form.topic.trim() : null,
  }
  const secret = form.secret.trim()
  if (secret) input.secret = secret
  else if (form.clearSecret) input.secret = ''
  return input
}

/** 提交前的校验;hasSecret = 编辑的渠道原来有密钥。返回错误文案,没问题为 null */
export function validateChannelForm(form: ChannelForm, hasSecret: boolean): string | null {
  const meta = channelKindMeta(form.kind)
  if (meta.urlLabel && meta.urlRequired) {
    const url = form.url.trim()
    if (!url) return `请填写${meta.urlLabel}`
    if (!/^https?:\/\/[^\s/]+/i.test(url)) return `${meta.urlLabel}要以 http:// 或 https:// 开头`
  }
  if (meta.topicLabel && !form.topic.trim()) return `请填写 ${meta.topicLabel}`
  if (meta.secretRequired) {
    const keeps = hasSecret && !form.clearSecret
    if (!form.secret.trim() && !keeps) return `请填写 ${meta.secretLabel}`
  }
  if (form.events.length === 0) return '至少选一类要推送的事件'
  return null
}
