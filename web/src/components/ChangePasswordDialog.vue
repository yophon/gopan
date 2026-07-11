<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useMutation } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ChangePasswordDocument } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'

const visible = defineModel<boolean>({ required: true })
const auth = useAuthStore()

const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })

watch(visible, (v) => {
  if (v) Object.assign(form, { oldPassword: '', newPassword: '', confirm: '' })
})

const mutation = useMutation({
  mutationFn: () =>
    request(ChangePasswordDocument, {
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    }),
  onSuccess: (data) => {
    // 改密吊销全部 refresh family,服务端已回发新 pair,当前会话无感续命
    auth.setAuth(data.changePassword)
    visible.value = false
    ElMessage.success('密码已修改,其它设备已下线')
  },
  onError: (err) =>
    ElMessage.error(
      errorText(err, '修改失败,请稍后再试', { BAD_CREDENTIALS: '旧密码错误' }),
    ),
})

function onSubmit() {
  if (!form.oldPassword || !form.newPassword) return
  if (form.newPassword.length < 8) {
    ElMessage.warning('新密码至少 8 位')
    return
  }
  if (form.newPassword !== form.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  mutation.mutate()
}
</script>

<template>
  <el-dialog v-model="visible" title="修改密码" width="400px">
    <el-form label-width="80px" @submit.prevent="onSubmit">
      <el-form-item label="旧密码">
        <el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="form.newPassword" type="password" show-password placeholder="至少 8 位" autocomplete="new-password" />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input
          v-model="form.confirm"
          type="password"
          show-password
          autocomplete="new-password"
          @keyup.enter="onSubmit"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="mutation.isPending.value"
        :disabled="!form.oldPassword || !form.newPassword || !form.confirm"
        @click="onSubmit"
      >
        确认修改
      </el-button>
    </template>
  </el-dialog>
</template>
