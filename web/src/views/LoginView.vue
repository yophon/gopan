<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'

import BrandLogo from '@/components/BrandLogo.vue'
import { useAuthStore } from '@/stores/auth'
import { errorText } from '@/api/errors'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const idIssuer = ref(''), idReady = ref(false), legacy = ref(false)
onMounted(async () => {
  try { const r=await fetch('/api/auth/id/config'); if(r.ok) idIssuer.value=(await r.json()).issuer || '' }
  catch { ElMessage.error('账号服务连接失败，请刷新重试') }
  finally { idReady.value=true }
})
function identityLogin() { location.assign('/api/auth/id/start?return='+encodeURIComponent(redirectTarget())) }

const activeTab = ref<'login' | 'register'>('login')
const submitting = ref(false)

const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({ username: '', password: '', confirm: '' })

const loginRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度 3-32 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
        if (value !== registerForm.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

function redirectTarget(): string {
  const r = route.query.redirect
  return typeof r === 'string' && r.startsWith('/') ? r : '/drive'
}

async function submitLogin() {
  const valid = await loginFormRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await auth.login(loginForm.username, loginForm.password)
    await router.push(redirectTarget())
  } catch (err) {
    ElMessage.error(errorText(err, '登录失败,请稍后重试'))
  } finally {
    submitting.value = false
  }
}

async function submitRegister() {
  const valid = await registerFormRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await auth.register(registerForm.username, registerForm.password)
    ElMessage.success('注册成功')
    await router.push(redirectTarget())
  } catch (err) {
    ElMessage.error(
      errorText(err, '注册失败,请稍后重试', { NAME_CONFLICT: '用户名已被占用' }),
    )
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-box">
      <section class="gp-folder login-folder">
        <span class="gp-folder-tab"><BrandLogo /></span>
        <div class="login-body">
          <h1 class="title">登录</h1>
          <p v-if="!idReady" class="muted">正在连接账号服务…</p>
          <template v-else-if="idIssuer && !legacy">
            <el-button type="primary" class="submit-btn" @click="identityLogin">使用 Yophon ID 登录</el-button>
            <el-button link class="legacy-btn" @click="legacy=true">其他账号使用密码登录</el-button>
          </template>
          <el-tabs v-else v-model="activeTab" stretch>
            <el-tab-pane label="登录" name="login">
              <el-form
                ref="loginFormRef"
                :model="loginForm"
                :rules="loginRules"
                label-position="top"
                @submit.prevent="submitLogin"
              >
                <el-form-item label="用户名" prop="username">
                  <el-input v-model="loginForm.username" autocomplete="username" />
                </el-form-item>
                <el-form-item label="密码" prop="password">
                  <el-input
                    v-model="loginForm.password"
                    type="password"
                    show-password
                    autocomplete="current-password"
                    @keyup.enter="submitLogin"
                  />
                </el-form-item>
                <el-button
                  type="primary"
                  class="submit-btn"
                  :loading="submitting"
                  native-type="submit"
                >
                  登录
                </el-button>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="注册" name="register">
              <el-form
                ref="registerFormRef"
                :model="registerForm"
                :rules="registerRules"
                label-position="top"
                @submit.prevent="submitRegister"
              >
                <el-form-item label="用户名" prop="username">
                  <el-input v-model="registerForm.username" autocomplete="username" />
                </el-form-item>
                <el-form-item label="密码" prop="password">
                  <el-input
                    v-model="registerForm.password"
                    type="password"
                    show-password
                    autocomplete="new-password"
                  />
                </el-form-item>
                <el-form-item label="确认密码" prop="confirm">
                  <el-input
                    v-model="registerForm.confirm"
                    type="password"
                    show-password
                    autocomplete="new-password"
                    @keyup.enter="submitRegister"
                  />
                </el-form-item>
                <el-button
                  type="primary"
                  class="submit-btn"
                  :loading="submitting"
                  native-type="submit"
                >
                  注册
                </el-button>
              </el-form>
            </el-tab-pane>
          </el-tabs>
        </div>
      </section>
      <router-link to="/app" class="app-link">安卓 App 与更新日志</router-link>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: calc(72px + var(--sat)) 16px calc(32px + var(--sab));
  box-sizing: border-box;
  background: var(--el-bg-color);
}
.login-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24px;
  width: min(400px, 100%);
}
.login-folder {
  --gp-r: 28px;
  --gp-tr: 18px;
  width: 100%;
}
.login-folder .gp-folder-tab {
  height: 42px;
  padding: 0 20px;
  color: var(--gp-ink);
  font-size: 16px;
}
.login-body {
  padding: 36px 32px 32px;
}
.title {
  margin: 0 0 28px;
  font-size: 40px;
  font-weight: 800;
  line-height: 1.1;
  letter-spacing: -0.04em;
}
.muted {
  margin: 0;
  color: var(--gp-ink-3);
}
.submit-btn {
  width: 100%;
  height: 48px;
  margin: 8px 0 0;
  border-radius: 999px;
  font-size: 15px;
}
.legacy-btn {
  width: 100%;
  margin: 16px 0 0 !important;
  color: var(--gp-ink-2);
}
.app-link {
  color: var(--gp-ink-3);
  font-size: 13px;
  text-decoration: none;
}
.app-link:hover {
  color: var(--gp-ink);
}

@media (max-width: 767px) {
  .login-body {
    padding: 28px 22px 24px;
  }
  .title {
    font-size: 34px;
  }
}
</style>
