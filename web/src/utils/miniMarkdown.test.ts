import { describe, expect, it } from 'vitest'

import { miniMarkdown } from './miniMarkdown'

describe('miniMarkdown', () => {
  it('段落、列表、粗体、行内代码', () => {
    expect(miniMarkdown('已完成:\n\n- 新建 `/2026 报销`\n- 移入 **2** 个文件')).toBe(
      '<p>已完成:</p><ul><li>新建 <code>/2026 报销</code></li><li>移入 <strong>2</strong> 个文件</li></ul>',
    )
    expect(miniMarkdown('1. 一\n2. 二\n第二段')).toBe('<ol><li>一</li><li>二</li></ol><p>第二段</p>')
    expect(miniMarkdown('第一行\n第二行')).toBe('<p>第一行<br>第二行</p>')
    expect(miniMarkdown('## 标题')).toBe('<p><strong>标题</strong></p>')
  })

  it('转义 HTML,代码块原样,链接只认 http(s)', () => {
    expect(miniMarkdown('<script>alert(1)</script>')).toBe('<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>')
    expect(miniMarkdown('```\n<b>x</b>\n**y**\n```')).toBe('<pre><code>&lt;b&gt;x&lt;/b&gt;\n**y**</code></pre>')
    expect(miniMarkdown('[看](https://a.b/c)')).toBe('<p><a href="https://a.b/c" target="_blank" rel="noopener noreferrer">看</a></p>')
    expect(miniMarkdown('[坏](javascript:alert(1))')).toBe('<p>[坏](javascript:alert(1))</p>')
    expect(miniMarkdown('`**不加粗**`')).toBe('<p><code>**不加粗**</code></p>')
    expect(miniMarkdown('[x](https://a.b/"onmouseover=1)')).not.toContain('"onmouseover')
  })
})
