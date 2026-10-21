import { describe, expect, it } from 'vitest'

import {
  channelFormFrom,
  channelInput,
  emptyChannelForm,
  switchChannelKind,
  validateChannelForm,
} from './notifyChannels'

const events = [
  { kind: 'collect_upload', defaultPush: true },
  { kind: 'job_done', defaultPush: false },
  { kind: 'login', defaultPush: true },
]

describe('emptyChannelForm', () => {
  it('新渠道默认勾上 defaultPush 的事件,服务器给默认地址', () => {
    const f = emptyChannelForm('NTFY', events)
    expect(f.events).toEqual(['collect_upload', 'login'])
    expect(f.url).toBe('https://ntfy.sh')
    expect(f.enabled).toBe(true)
    expect(emptyChannelForm('BARK', events).url).toBe('https://api.day.app')
    expect(emptyChannelForm('WEBHOOK', events).url).toBe('')
  })
})

describe('switchChannelKind', () => {
  it('没改过的服务器地址与名称跟着换', () => {
    const f = switchChannelKind(emptyChannelForm('NTFY', events), 'BARK')
    expect(f.url).toBe('https://api.day.app')
    expect(f.name).toBe('Bark')
  })
  it('用户填过的地址与名称不动', () => {
    const f = { ...emptyChannelForm('NTFY', events), url: 'https://ntfy.example.com', name: '我的手机' }
    const g = switchChannelKind(f, 'BARK')
    expect(g.url).toBe('https://ntfy.example.com')
    expect(g.name).toBe('我的手机')
  })
})

describe('channelInput:密钥只写不读', () => {
  const channel = {
    kind: 'BARK' as const,
    name: 'iPhone',
    enabled: true,
    events: ['login'],
    url: 'https://api.day.app',
    topic: '',
  }
  it('编辑时没动密钥:不传 secret(保持原值)', () => {
    const input = channelInput(channelFormFrom(channel))
    expect('secret' in input).toBe(false)
  })
  it('输入了新密钥就传新的', () => {
    const input = channelInput({ ...channelFormFrom(channel), secret: ' abcd ' })
    expect(input.secret).toBe('abcd')
  })
  it('点了清除传空串', () => {
    const input = channelInput({ ...channelFormFrom(channel), clearSecret: true })
    expect(input.secret).toBe('')
  })
  it('这个类型不用的字段传 null', () => {
    const tg = channelInput({ ...emptyChannelForm('TELEGRAM', events), topic: ' 123 ', secret: 't' })
    expect(tg.url).toBeNull()
    expect(tg.topic).toBe('123')
    const bark = channelInput(channelFormFrom(channel))
    expect(bark.topic).toBeNull()
    expect(bark.url).toBe('https://api.day.app')
  })
  it('名称留空用类型名', () => {
    expect(channelInput({ ...emptyChannelForm('NTFY', events), name: '  ' }).name).toBe('ntfy')
  })
})

describe('validateChannelForm', () => {
  it('Webhook 要合法地址', () => {
    const f = emptyChannelForm('WEBHOOK', events)
    expect(validateChannelForm(f, false)).toBe('请填写地址')
    expect(validateChannelForm({ ...f, url: 'ftp://x' }, false)).toBe('地址要以 http:// 或 https:// 开头')
    expect(validateChannelForm({ ...f, url: 'https://hook.example.com/x' }, false)).toBeNull()
  })
  it('ntfy 要 topic', () => {
    const f = emptyChannelForm('NTFY', events)
    expect(validateChannelForm(f, false)).toBe('请填写 Topic')
    expect(validateChannelForm({ ...f, topic: 'gopan-x' }, false)).toBeNull()
  })
  it('Bark 必须有 Device Key:新建要填,编辑时原来有且没清除就算有', () => {
    const f = emptyChannelForm('BARK', events)
    expect(validateChannelForm(f, false)).toBe('请填写 Device Key')
    expect(validateChannelForm(f, true)).toBeNull()
    expect(validateChannelForm({ ...f, clearSecret: true }, true)).toBe('请填写 Device Key')
  })
  it('Telegram 要 Chat ID 与 Bot Token', () => {
    const f = emptyChannelForm('TELEGRAM', events)
    expect(validateChannelForm(f, false)).toBe('请填写 Chat ID')
    expect(validateChannelForm({ ...f, topic: '1' }, false)).toBe('请填写 Bot Token')
    expect(validateChannelForm({ ...f, topic: '1', secret: 'x' }, false)).toBeNull()
  })
  it('至少一类事件', () => {
    const f = { ...emptyChannelForm('NTFY', events), topic: 't', events: [] }
    expect(validateChannelForm(f, false)).toBe('至少选一类要推送的事件')
  })
})
