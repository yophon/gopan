// 把本机 tau 仓库的当前提交编译进 src/vendor/tau/(doc/33 五):不依赖 tau 发 npm。
//
//   node scripts/vendor-tau.mjs ../../tau
//
// 用 tau 自己的 tsconfig.build.json 逐包编译(JS + d.ts,不要 sourcemap),d.ts 里的 .ts 引用改成 .js,
// 写 SOURCE.md(tau 的提交号、有没有没提交的改动、每个文件的 sha256)。包名靠 vite.config.ts 与
// tsconfig.json 里的别名指过来。要更新 tau 就重跑。
import { execFileSync, spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync, cpSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const TAU_PACKAGES = ['kernel', 'ext-mcp-http', 'ext-provider-anthropic', 'ext-provider-google', 'ext-provider-openai-responses']

const web = resolve(fileURLToPath(new URL('..', import.meta.url)))
const tau = resolve(process.argv[2] ?? join(web, '../../tau'))
const out = join(web, 'src/vendor/tau')

const git = (...args) => execFileSync('git', ['-C', tau, ...args], { encoding: 'utf8' }).trim()
const commit = git('rev-parse', 'HEAD')
const subject = git('log', '-1', '--format=%s')
const dirty = git('status', '--porcelain', '--', 'packages').length > 0

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name)
    return statSync(p).isDirectory() ? walk(p) : [p]
  })
}

const tmp = mkdtempSync(join(tmpdir(), 'gopan-vendor-tau-'))
try {
  for (const pkg of TAU_PACKAGES) {
    const dist = join(tmp, pkg)
    const r = spawnSync('npx', ['tsc', '-p', join(tau, 'packages', pkg, 'tsconfig.build.json'),
      '--outDir', dist, '--sourceMap', 'false', '--declarationMap', 'false'], { cwd: tau, stdio: 'inherit', shell: process.platform === 'win32' })
    if (r.status !== 0) throw new Error(`tsc failed for ${pkg}`)
    for (const f of walk(dist)) {
      if (!f.endsWith('.d.ts')) continue
      const text = readFileSync(f, 'utf8')
      const fixed = text.replace(/(?<!\.d)\.ts(?=["'])/g, '.js')
      if (fixed !== text) writeFileSync(f, fixed)
    }
  }
  rmSync(out, { recursive: true, force: true })
  mkdirSync(out, { recursive: true })
  for (const pkg of TAU_PACKAGES) cpSync(join(tmp, pkg), join(out, pkg), { recursive: true })
  cpSync(join(tau, 'LICENSE'), join(out, 'LICENSE'))
} finally {
  rmSync(tmp, { recursive: true, force: true })
}

const files = walk(out).map((f) => relative(out, f).replaceAll('\\', '/')).sort()
const sums = files.map((f) => `${createHash('sha256').update(readFileSync(join(out, f))).digest('hex')}  ${f}`)
const version = JSON.parse(readFileSync(join(tau, 'packages/kernel/package.json'), 'utf8')).version
writeFileSync(join(out, 'SOURCE.md'), `# tau(编译产物)

由 \`web/scripts/vendor-tau.mjs\` 从本机 tau 仓库生成,不要手改;要更新就重跑脚本。

- 仓库:https://github.com/yophon/tau(MIT)
- 提交:\`${commit}\` ${subject}${dirty ? '\n- 注意:生成时 packages/ 下有没提交的改动' : ''}
- 包版本号:${version}(未发布到 npm,用的是这个提交)
- 包:${TAU_PACKAGES.join('、')}
- 生成时间:${new Date().toISOString()}

## sha256

\`\`\`
${sums.join('\n')}
\`\`\`
`)
console.log(`vendored tau ${commit.slice(0, 7)} (${files.length} files)${dirty ? ' — packages/ has uncommitted changes' : ''}`)
