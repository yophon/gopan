import { h, ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'

import { getErrorMessage, request } from '@/api/client'
import { errorText } from '@/api/errors'
import { UndoActivitiesDocument, UndoPreviewDocument } from '@/api/gen/graphql'
import type { UndoResultFieldsFragment, UndoSelection } from '@/api/gen/graphql'
import { actionVerb, undoPreviewText, undoResultText } from '@/utils/activity'

/**
 * 撤回(doc/23 二.2)。整批 / 按来源先预览:「会撤回 N 项、M 项撤不了(原因)」,确认后才执行;
 * 单条直接撤,撤不了的也说原因。撤回会动到文件列表、回收站、用量、空间分析,一并刷新。
 */
export function useUndo() {
  const queryClient = useQueryClient()
  const busy = ref(false)

  function refreshAfter() {
    for (const key of ['activities', 'children', 'breadcrumb', 'trash', 'me', 'space']) {
      void queryClient.invalidateQueries({ queryKey: [key] })
    }
  }

  function report(res: UndoResultFieldsFragment) {
    const text = undoResultText(res)
    if (res.failed === 0) ElMessage.success(text)
    else if (res.done === 0) ElMessage.error({ message: text, duration: 8000 })
    else ElMessage.warning({ message: text, duration: 8000 })
  }

  function fail(err: unknown) {
    ElMessage.error(getErrorMessage(err) ?? errorText(err, '撤回失败'))
  }

  /** 预览里撤不了的前几条:名字 + 原因 */
  function previewBody(res: UndoResultFieldsFragment) {
    const bad = res.items.filter((it) => !it.ok)
    return h('div', { class: 'undo-preview' }, [
      h('p', { style: 'margin:0 0 8px' }, undoPreviewText(res)),
      bad.length
        ? h(
            'ul',
            { style: 'margin:0;padding-left:18px;font-size:12px;color:var(--el-text-color-secondary);line-height:1.7' },
            [
              ...bad.slice(0, 5).map((it) =>
                h('li', `${actionVerb[it.activity.action]}「${it.activity.name}」:${it.reason ?? '撤不了'}`),
              ),
              ...(bad.length > 5 ? [h('li', `还有 ${bad.length - 5} 项`)] : []),
            ],
          )
        : null,
      h(
        'p',
        { style: 'margin:8px 0 0;font-size:12px;color:var(--el-text-color-secondary)' },
        '从新到旧逐条撤回,每条各自成败;撤回本身也会记进动态。',
      ),
    ])
  }

  /** 整批 / 按来源:先预览再确认。返回是否真的执行了 */
  async function undoWithPreview(selection: UndoSelection, title: string): Promise<boolean> {
    if (busy.value) return false
    busy.value = true
    try {
      let preview: UndoResultFieldsFragment
      try {
        preview = (await request(UndoPreviewDocument, { selection })).undoPreview
      } catch (err) {
        fail(err)
        return false
      }
      if (preview.done === 0) {
        await ElMessageBox.alert(previewBody(preview), title, { confirmButtonText: '知道了' }).catch(() => undefined)
        return false
      }
      try {
        await ElMessageBox.confirm(previewBody(preview), title, {
          confirmButtonText: `撤回 ${preview.done} 项`,
          cancelButtonText: '取消',
          type: 'warning',
        })
      } catch {
        return false
      }
      try {
        report((await request(UndoActivitiesDocument, { selection })).undoActivities)
      } catch (err) {
        fail(err)
      }
      refreshAfter()
      return true
    } finally {
      busy.value = false
    }
  }

  /** 单条:直接撤 */
  async function undoOne(id: string): Promise<void> {
    if (busy.value) return
    busy.value = true
    try {
      report((await request(UndoActivitiesDocument, { selection: { ids: [id] } })).undoActivities)
    } catch (err) {
      fail(err)
    } finally {
      busy.value = false
      refreshAfter()
    }
  }

  return { busy, undoWithPreview, undoOne }
}
