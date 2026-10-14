// 预览场景用的测试文件(零依赖):正弦波 WAV、几页的 PDF、GBK 编码的文本。
// 视频用模拟器自带的 screenrecord 录几秒(H.264 MP4),见 p3.mjs。

/** 单声道 16 bit PCM 正弦波 */
export function makeWav(seconds = 20, freq = 440, rate = 22050) {
  const n = Math.round(seconds * rate);
  const data = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) data.writeInt16LE(Math.round(Math.sin((2 * Math.PI * freq * i) / rate) * 8000), i * 2);
  const h = Buffer.alloc(44);
  h.write('RIFF', 0, 'ascii');
  h.writeUInt32LE(36 + data.length, 4);
  h.write('WAVE', 8, 'ascii');
  h.write('fmt ', 12, 'ascii');
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20); // PCM
  h.writeUInt16LE(1, 22); // 单声道
  h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 2, 28);
  h.writeUInt16LE(2, 32);
  h.writeUInt16LE(16, 34);
  h.write('data', 36, 'ascii');
  h.writeUInt32LE(data.length, 40);
  return Buffer.concat([h, data]);
}

/** 每页一行大字 + 一个色块的 PDF(手写对象与 xref 表) */
export function makePdf(pages = 3, title = 'gopan') {
  const objects = new Map();
  objects.set(1, '<< /Type /Catalog /Pages 2 0 R >>');
  objects.set(3, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  const kids = [];
  let next = 4;
  for (let i = 0; i < pages; i++) {
    const pageId = next++;
    const contentId = next++;
    kids.push(pageId);
    const r = (0.2 + 0.25 * i) % 1;
    const stream = `${r.toFixed(2)} 0.45 0.8 rg 72 360 468 260 re f\nBT /F1 40 Tf 72 680 Td (${title} page ${i + 1}) Tj ET\n`;
    objects.set(pageId, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${contentId} 0 R >>`);
    objects.set(contentId, `<< /Length ${Buffer.byteLength(stream, 'latin1')} >>\nstream\n${stream}endstream`);
  }
  objects.set(2, `<< /Type /Pages /Kids [${kids.map((k) => `${k} 0 R`).join(' ')}] /Count ${pages} >>`);
  const count = next - 1;
  let out = '%PDF-1.4\n';
  const offsets = [];
  for (let id = 1; id <= count; id++) {
    offsets[id] = Buffer.byteLength(out, 'latin1');
    out += `${id} 0 obj\n${objects.get(id)}\nendobj\n`;
  }
  const xref = Buffer.byteLength(out, 'latin1');
  out += `xref\n0 ${count + 1}\n0000000000 65535 f \n`;
  for (let id = 1; id <= count; id++) out += `${String(offsets[id]).padStart(10, '0')} 00000 n \n`;
  out += `trailer\n<< /Size ${count + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, 'latin1');
}

/** 「中文老文件 GBK」的 GBK 编码(Node 只能解 GBK 不能编,这里直接给字节,用 TextDecoder 自检) */
export function gbkText() {
  const bytes = Buffer.from([0xd6, 0xd0, 0xce, 0xc4, 0xc0, 0xcf, 0xce, 0xc4, 0xbc, 0xfe, 0x20, 0x47, 0x42, 0x4b, 0x0a]);
  const decoded = new TextDecoder('gbk').decode(bytes);
  if (decoded !== '中文老文件 GBK\n') throw new Error(`GBK 自检失败:${decoded}`);
  return bytes;
}
