import { describe, expect, it } from 'vitest'

import { IMG_MAX_BYTES, imageUploadError, parseUploadResponse, uploadToolConfigs } from './imgUpload'

describe('imageUploadError', () => {
  it('按扩展名认图片', () => {
    expect(imageUploadError({ name: 'a.PNG', type: 'image/png', size: 10 })).toBeNull()
    expect(imageUploadError({ name: 'a.svg', type: 'image/svg+xml', size: 10 })).toBeNull()
    expect(imageUploadError({ name: 'a.pdf', type: 'application/pdf', size: 10 })).toContain('只能传图片')
    // 扩展名不对,type 是图片也不收(服务端要求扩展名与文件头都对得上)
    expect(imageUploadError({ name: 'a.heic', type: 'image/heic', size: 10 })).toContain('只能传图片')
  })
  it('没有扩展名(剪贴板)按 type 认', () => {
    expect(imageUploadError({ name: 'blob', type: 'image/png', size: 10 })).toBeNull()
    expect(imageUploadError({ name: '', type: 'text/plain', size: 10 })).toContain('只能传图片')
  })
  it('大小:超 20 MB、空文件', () => {
    expect(imageUploadError({ name: 'a.jpg', type: 'image/jpeg', size: IMG_MAX_BYTES + 1 })).toBe('图片超过 20 MB')
    expect(imageUploadError({ name: 'a.jpg', type: 'image/jpeg', size: 0 })).toBe('空文件')
  })
})

describe('parseUploadResponse', () => {
  const ok = {
    url: 'https://p.example.com/x/t/a.png',
    markdown: '![a.png](https://p.example.com/x/t/a.png)',
    html: '',
    bbcode: '',
    name: 'a.png',
    size: 1,
    node_id: 'n',
    link_id: 'l',
  }
  it('200 返回结果', () => {
    const r = parseUploadResponse(200, JSON.stringify(ok))
    expect(r.ok).toBe(true)
    if (r.ok) expect(r.result.markdown).toBe(ok.markdown)
  })
  it('200 但内容不对', () => {
    expect(parseUploadResponse(200, '<html>')).toMatchObject({ ok: false, code: 'BAD_RESPONSE' })
  })
  it('4xx 带错误码:认识的用中文,不认识的用服务端 message', () => {
    expect(parseUploadResponse(429, JSON.stringify({ error: { code: 'RATE_LIMITED', message: 'x' } }))).toEqual({
      ok: false,
      code: 'RATE_LIMITED',
      message: '传得太快了,稍后再试',
    })
    expect(parseUploadResponse(415, JSON.stringify({ error: { code: 'UNSUPPORTED_TYPE', message: '只收图片:jpg、png' } }))).toEqual({
      ok: false,
      code: 'UNSUPPORTED_TYPE',
      message: '只收图片:jpg、png',
    })
    expect(parseUploadResponse(400, JSON.stringify({ error: { code: 'WEIRD', message: '奇怪的错' } }))).toEqual({
      ok: false,
      code: 'WEIRD',
      message: '奇怪的错',
    })
  })
  it('网关报错(不是 JSON)', () => {
    expect(parseUploadResponse(413, '<html>too large</html>')).toEqual({
      ok: false,
      code: 'HTTP_413',
      message: '图片太大',
    })
    expect(parseUploadResponse(502, '')).toMatchObject({ message: '上传失败(HTTP 502)' })
  })
})

describe('uploadToolConfigs', () => {
  const c = uploadToolConfigs('https://pan.example.com/', 'KEY123')
  it('接口地址拼上站点', () => {
    expect(c.endpoint).toBe('https://pan.example.com/api/img/upload')
  })
  it('PicGo:地址、参数名、JSON 路径、请求头', () => {
    expect(c.picgo).toContain('API 地址:https://pan.example.com/api/img/upload')
    expect(c.picgo).toContain('POST 参数名:file')
    expect(c.picgo).toContain('JSON 路径:url')
    expect(c.picgo).toContain('{"Authorization":"Bearer KEY123"}')
  })
  it('ShareX .sxcu 是合法 JSON', () => {
    const j = JSON.parse(c.sharex)
    expect(j).toMatchObject({
      DestinationType: 'ImageUploader',
      RequestMethod: 'POST',
      RequestURL: 'https://pan.example.com/api/img/upload',
      Headers: { Authorization: 'Bearer KEY123' },
      Body: 'MultipartFormData',
      FileFormName: 'file',
      URL: '{json:url}',
    })
    expect(typeof j.Version).toBe('string')
    expect(c.sharexFileName).toBe('pan.example.com.sxcu')
  })
  it('uPic、curl', () => {
    expect(c.upic).toContain('URL 路径:["url"]')
    expect(c.upic).toContain('Authorization = Bearer KEY123')
    expect(c.curl).toBe(
      'curl -H "Authorization: Bearer KEY123" -F "file=@a.png" https://pan.example.com/api/img/upload',
    )
  })
  it('Typora 走 PicGo', () => {
    expect(c.typora).toContain('PicGo')
  })
})
