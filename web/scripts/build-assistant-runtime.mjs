// 把助手的运行时(src/assistant/quickjs-entry.ts:tau 内核 + gopan 的装配)打成一个 iife,
// 放进安卓 App 的 assets,由 QuickJS 执行(doc/33 八之 2)。改了 src/assistant 或重新编译了 tau 之后重跑:
//
//   node scripts/build-assistant-runtime.mjs
import { createHash } from 'node:crypto'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'

// esbuild 不是直接依赖(pnpm 不让直接引),用 vite 自带的那份
const fromVite = createRequire(createRequire(import.meta.url).resolve('vite'))
const { build } = await import(pathToFileURL(fromVite.resolve('esbuild')).href)

// 与 vendor-tau.mjs 的 TAU_PACKAGES 一致(那个脚本一引入就会重新编译 tau,这里不引它)
const TAU_PACKAGES = ['kernel', 'ext-mcp-http', 'ext-provider-anthropic', 'ext-provider-google', 'ext-provider-openai-responses']

const web = resolve(fileURLToPath(new URL('..', import.meta.url)))
const out = join(web, '../android/app/src/main/assets/assistant/runtime.js')

const result = await build({
  entryPoints: [join(web, 'src/assistant/quickjs-entry.ts')],
  bundle: true,
  platform: 'neutral',
  format: 'iife',
  target: 'es2022',
  minify: true,
  legalComments: 'none',
  write: false,
  logLevel: 'warning',
  alias: Object.fromEntries(TAU_PACKAGES.map((p) => [`@yophon/tau-${p}`, join(web, `src/vendor/tau/${p}/index.js`)])),
  tsconfig: join(web, 'tsconfig.json'),
})
const code = result.outputFiles[0].text
mkdirSync(dirname(out), { recursive: true })
writeFileSync(out, code)
const sha = createHash('sha256').update(code).digest('hex')
console.log(`assistant runtime: ${code.length} bytes, sha256 ${sha.slice(0, 12)} → ${out}`)
