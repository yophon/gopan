import { h } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * 带一个操作按钮的提示,比如「已放入回收站 · 撤销」。按钮点一次就关;
 * 放进回收站这类能撤回的操作不再先弹确认框,做完给 6 秒撤销的机会。
 */
export function actionToast(text: string, actionLabel: string, onAction: () => void, duration = 6000) {
  let done = false
  const msg = ElMessage({
    type: 'success',
    duration,
    grouping: false,
    customClass: 'gp-action-toast',
    message: h('span', { class: 'gp-action-toast__body' }, [
      h('span', text),
      h(
        'button',
        {
          type: 'button',
          class: 'gp-action-toast__btn',
          onClick: () => {
            if (done) return
            done = true
            msg.close()
            onAction()
          },
        },
        actionLabel,
      ),
    ]),
  })
  return msg
}
