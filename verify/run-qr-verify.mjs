// 二维码编码器的交叉验证驱动。
//
// 用 Node 的 `qrcode` 包生成参考矩阵（同一份输入），喂给 Java 侧的
// VerifyQr，逐模块比对。需要先 `npm i qrcode`。
//
// 用法：node verify/run-qr-verify.mjs
//   环境变量：
//     WB_CLASSES       编译产物目录（含 com/wbgateway）
//     WB_QRCODE_MODULE 参考实现 qrcode 包的路径（找不到自动 require 时用）
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'

const require = createRequire(import.meta.url)
let qrcode
try {
  qrcode = require('qrcode')
} catch {
  // 退到显式指定的位置（CI / 临时目录里跑时常见）
  const fallback = process.env.WB_QRCODE_MODULE
  if (!fallback || !existsSync(fallback)) {
    console.error('缺少参考实现：请先 `npm i qrcode`，或设置 WB_QRCODE_MODULE 指向它')
    process.exit(2)
  }
  qrcode = require(fallback)
}

// 覆盖：短/中/长文本、非 ASCII，以及真实的登录 URL 形态
const samples = [
  'A',
  'https://xiaohuanxiong.com/login/mp?code=qr-01a1a12d-76a4-7057-8466-ec711a85eca6&appname=%E5%95%86%E6%B1%A4%E5%B0%8F%E6%B5%A3%E7%86%8A&source=platform',
  'https://qoder.com/device/selectAccounts?challenge=U9PA4mucFQMSLB1Ut3K4jfp15H6QNgpZBrZgMAAcKAs&challenge_method=S256&nonce=6c5f0687-bdc7-417d-a2bb-b44738268040&machine_id=5ce06885-a739-4a7c-bf68-ff05baa6ff42&client_id=e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb',
  'https://bigmodel.cn/login?redirect=https%3A%2F%2Fzcode.z.ai%2Fapp%2Foauth%2Flogin%3Fredirect%3Dzcode%253A%252F%252Foauth%252Fcallback%26app_version%3D3.14.3&appId=zcode&state=abf97e946a5c5e2a82086742f8d05c66',
  '中文内容也应该能编码：统一 API 网关',
  'x'.repeat(120),
  'y'.repeat(300),
  'z'.repeat(700),
]

const levels = ['L', 'M', 'Q', 'H']
const lines = []

for (const text of samples) {
  for (const level of levels) {
    const opts = { errorCorrectionLevel: level }
    // ⚠ 必须**强制字节模式**：`qrcode` 默认会做字符集混合优化（数字 / 字母数字 /
    // 字节分段），同一段文本会被切成更短的段，选出的版本比纯字节模式小。
    // 我们的编码器只实现字节模式（登录二维码是 URL，够用），所以参考侧也要固定
    // 成字节模式 —— 否则比的是「两份不同协议」而不是「同一份实现的正确性」。
    const seg = qrcode.create([{ data: text, mode: 'byte' }], opts)
    const size = seg.modules.size
    const data = seg.modules.data
    const rows = []
    for (let y = 0; y < size; y += 1) {
      let row = ''
      for (let x = 0; x < size; x += 1) row += data[y * size + x] ? '1' : '0'
      rows.push(row)
    }
    lines.push([
      level,
      String(seg.version),
      String(size),
      rows.join(','),
      encodeURIComponent(text),
    ].join('\t'))
  }
}

const classes = process.env.WB_CLASSES
if (!classes) {
  console.error('请设置 WB_CLASSES 指向编译产物目录（含 com/wbgateway）')
  process.exit(2)
}

const result = execFileSync(process.env.WB_JAVA || 'java', ['-cp', classes, 'VerifyQr'], {
  input: lines.join('\n') + '\n',
  encoding: 'utf8',
  maxBuffer: 64 * 1024 * 1024,
})
process.stdout.write(result)
// 判据看汇总行而不是「输出里有没有 FAIL 字样」——
// 「0 FAIL」里也含 FAIL，那样判会永远失败。
if (!/0 FAIL/.test(result)) process.exit(1)
