<script lang="ts">
/**
 * 发布页上画出来的手机:文件首页、预览与传输、动态与撤回、分享四个屏幕。
 * screens 决定画哪几个,active 那个显示,其余淡出(功能区滚动时切换)。纯展示,数据是写死的样例。
 */
export type PhoneScreen = 'files' | 'preview' | 'activity' | 'share'
</script>

<script setup lang="ts">
defineProps<{ screens: PhoneScreen[]; active: PhoneScreen }>()

const shareHost = location.host
</script>

<template>
  <div class="phone" aria-hidden="true">
    <div v-if="screens.includes('files')" class="screen" :class="{ on: active === 'files' }">
      <div class="status"><span>9:41</span><i /></div>
      <div class="p-head"><b>文件</b><span class="avatar">Y</span></div>
      <div class="p-search">
        <svg class="i" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7" /><path d="M20 20l-4-4" /></svg>
        搜索文件名或内容
      </div>
      <div class="p-label"><span>最近</span><span>全部</span></div>
      <div class="p-recent">
        <span class="ph ph1"><span class="ph-dur">37:30</span></span>
        <span class="ph ph2" />
        <span class="ph ph3" />
      </div>
      <div class="p-label"><span>正在传输</span></div>
      <div class="p-row">
        <span class="p-up"><svg class="i" viewBox="0 0 24 24"><path d="M12 16V5m0 0L7.5 9.5M12 5l4.5 4.5M5 19h14" /></svg></span>
        <div><b>旅行 vlog.mp4</b><small>后台上传 · 锁屏也在传</small><div class="bar"><i /></div></div>
      </div>
      <div class="p-label"><span>我的文件</span></div>
      <div class="p-row"><svg class="fd" viewBox="0 0 34 29"><path d="M1 4a3 3 0 0 1 3-3h7.2a2 2 0 0 1 1.5.7L15.4 4H30a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" fill="#e8e5dd" /><path d="M1 9.5h32V25a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" fill="#d9d5cb" /></svg><div><b>照片 2026</b><small>312 项</small></div></div>
      <div class="p-row"><svg class="fd" viewBox="0 0 34 29"><path d="M1 4a3 3 0 0 1 3-3h7.2a2 2 0 0 1 1.5.7L15.4 4H30a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" fill="#e8e5dd" /><path d="M1 9.5h32V25a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" fill="#d9d5cb" /></svg><div><b>工作资料</b><small>48 项</small></div></div>
      <div class="p-row"><span class="ft">PDF</span><div><b>论文终稿.pdf</b><small>12.4 MB · 10月21日</small></div></div>
    </div>

    <div v-if="screens.includes('preview')" class="screen" :class="{ on: active === 'preview' }">
      <div class="status"><span>9:41</span><i /></div>
      <div class="player">
        <span class="ph1 scene" />
        <span class="resume">从 23:14 接着看</span>
        <p class="subline">明天一早，我们去海边吧</p>
        <div class="seek"><i /></div>
      </div>
      <div class="v-info"><b>旅行 vlog.mp4</b><small>2.1 GB · 上次在 Pixel 8 看到 23:14</small></div>
      <div class="p-label"><span>传输</span><span>3 项</span></div>
      <div class="p-row">
        <span class="p-up"><svg class="i" viewBox="0 0 24 24"><path d="M12 16V5m0 0L7.5 9.5M12 5l4.5 4.5M5 19h14" /></svg></span>
        <div><b>照片 2026</b><small>后台上传 · 146 / 312 项</small><div class="bar"><i /></div></div>
      </div>
      <div class="p-row">
        <span class="p-ok"><svg class="i" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg></span>
        <div><b>项目素材.zip</b><small>860 MB · 盘里已有，秒传</small></div>
      </div>
      <div class="p-row"><span class="ft">PDF</span><div><b>论文终稿.pdf</b><small>在线预览 · 第 12 页</small></div></div>
      <div class="p-row"><span class="ft">MP3</span><div><b>播客 第 42 期.mp3</b><small>接着听 · 还剩 18 分钟</small></div></div>
    </div>

    <div v-if="screens.includes('activity')" class="screen" :class="{ on: active === 'activity' }">
      <div class="status"><span>9:41</span><i /></div>
      <div class="p-head"><b>动态</b></div>
      <div class="chips"><span class="on">全部</span><span>手机</span><span>网页</span><span>Agent</span></div>
      <div class="act">
        <span class="who"><svg class="i" viewBox="0 0 24 24"><rect x="6.5" y="2.5" width="11" height="19" rx="2.5" /><path d="M11 18.5h2" /></svg></span>
        <div><b>Pixel 8</b> 删除了 12 个文件<small>2 分钟前 · 照片 2026</small></div>
        <span class="undo">撤回</span>
      </div>
      <div class="act">
        <span class="who ai"><svg class="i" viewBox="0 0 24 24"><path d="M11 3.5l1.9 5 5 1.9-5 1.9-1.9 5-1.9-5-5-1.9 5-1.9z" /><path d="M18.5 15l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z" /></svg></span>
        <div><b>Claude</b> 移动了 34 个 PDF<small>10 分钟前 · 经你授权</small></div>
        <span class="undo">撤回</span>
      </div>
      <div class="act">
        <span class="who"><svg class="i" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="12" rx="2" /><path d="M8 20h8M12 16v4" /></svg></span>
        <div><b>网页版</b> 重命名了 季度汇报<small>昨天 21:40</small></div>
      </div>
      <div class="act">
        <span class="who"><svg class="i" viewBox="0 0 24 24"><rect x="3" y="13" width="18" height="7" rx="2" /><path d="M5 13l2.4-7.2A1.5 1.5 0 0 1 8.8 4.8h6.4a1.5 1.5 0 0 1 1.4 1L19 13M7 16.5h4" /></svg></span>
        <div><b>WebDAV</b> 上传了 3 个文件<small>昨天 18:02</small></div>
      </div>
      <div class="act">
        <span class="who"><svg class="i" viewBox="0 0 24 24"><rect x="6.5" y="2.5" width="11" height="19" rx="2.5" /><path d="M11 18.5h2" /></svg></span>
        <div><b>Pixel 8</b> 上传了 46 张照片<small>昨天 08:12 · 照片 2026</small></div>
      </div>
      <div class="toast">
        <svg class="i" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>已撤回 12 个文件<span>查看</span>
      </div>
    </div>

    <div v-if="screens.includes('share')" class="screen" :class="{ on: active === 'share' }">
      <div class="status"><span>9:41</span><i /></div>
      <div class="dim">
        <div class="p-head"><b>文件</b><span class="avatar">Y</span></div>
        <div class="p-label"><span>工作资料</span></div>
        <div class="p-row"><svg class="fd" viewBox="0 0 34 29"><path d="M1 4a3 3 0 0 1 3-3h7.2a2 2 0 0 1 1.5.7L15.4 4H30a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" fill="#e8e5dd" /><path d="M1 9.5h32V25a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" fill="#d9d5cb" /></svg><div><b>2026 Q3</b><small>16 项</small></div></div>
        <div class="p-row"><span class="ft">PPT</span><div><b>季度汇报.pptx</b><small>8.6 MB</small></div></div>
        <div class="p-row"><span class="ft">XLS</span><div><b>预算.xlsx</b><small>240 KB</small></div></div>
      </div>
      <div class="sheet">
        <div class="grab" />
        <div class="sh-file"><span class="ft">PPT</span><div><b>分享 季度汇报.pptx</b><small>8.6 MB · 已被访问 12 次</small></div></div>
        <div class="sh-link">{{ shareHost }}/s/Xa9k</div>
        <div class="sh-row">提取码<b>8F3K</b><span class="sw" /></div>
        <div class="sh-row">有效期<span class="seg"><i>1 天</i><i class="on">7 天</i><i>永久</i></span></div>
        <div class="sh-btn">复制链接</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.phone {
  position: relative;
  width: 326px;
  height: 672px;
  border-radius: 52px;
  background: var(--gp-ink);
  box-shadow: 0 44px 80px -40px rgba(18, 18, 18, 0.55), inset 0 0 0 1.5px #2c2c2c;
  color: var(--gp-ink);
  font-size: 13px;
  line-height: 1.5;
  font-variant-numeric: tabular-nums;
  user-select: none;
}
.screen {
  position: absolute;
  inset: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 42px;
  background: #fff;
  opacity: 0;
  transform: scale(0.97);
  transition: opacity 0.5s var(--gp-ease), transform 0.5s var(--gp-ease);
}
.screen.on {
  opacity: 1;
  transform: none;
}
.i {
  width: 16px;
  height: 16px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.status {
  display: flex;
  flex: none;
  justify-content: space-between;
  align-items: center;
  height: 38px;
  padding: 6px 28px 0;
  font-size: 12.5px;
  font-weight: 600;
}
.status::before {
  content: '';
  position: absolute;
  top: 13px;
  left: 50%;
  width: 11px;
  height: 11px;
  margin-left: -5.5px;
  border-radius: 50%;
  background: var(--gp-ink);
}
.status i {
  width: 17px;
  height: 9px;
  border-radius: 2.5px;
  background: var(--gp-ink);
}
.p-head {
  display: flex;
  align-items: center;
  padding: 16px 20px 0;
}
.p-head b {
  margin-right: auto;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.03em;
}
.avatar {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--gp-panel-2);
  color: var(--gp-ink-2);
  font-size: 12px;
  font-weight: 700;
}
.p-search {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  margin: 14px 16px 0;
  padding: 0 16px;
  border-radius: 999px;
  background: var(--gp-panel);
  color: var(--gp-ink-3);
}
.p-label {
  display: flex;
  justify-content: space-between;
  padding: 20px 20px 8px;
  color: var(--gp-ink-3);
  font-size: 12px;
  font-weight: 600;
}
.p-recent {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  padding: 0 16px;
}
.ph {
  position: relative;
  aspect-ratio: 1;
  overflow: hidden;
  border-radius: 14px;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.05);
}
.ph1 {
  background: radial-gradient(circle at 68% 42%, #fff4dc 0 9%, transparent 10%),
    linear-gradient(180deg, #f2d2b6 0%, #e7a986 44%, #8197b8 58%, #3f5a82 100%);
}
.ph2 {
  background: radial-gradient(120% 60% at 30% 100%, #5f7a5a 0 40%, transparent 41%),
    radial-gradient(120% 60% at 90% 100%, #7c9470 0 45%, transparent 46%), linear-gradient(180deg, #dfe6e8, #b9cbd3);
}
.ph3 {
  background: linear-gradient(90deg, transparent 0 14%, #5a6170 14% 30%, transparent 30% 38%, #6e7584 38% 58%, transparent 58% 66%, #4c5260 66% 86%, transparent 86%)
      bottom / 100% 62% no-repeat,
    linear-gradient(180deg, #d8dbe2, #aab1bf);
}
.ph-dur {
  position: absolute;
  right: 6px;
  bottom: 5px;
  padding: 0 5px;
  border-radius: 5px;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  font-size: 9.5px;
  font-weight: 600;
  line-height: 16px;
}
.p-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 9px 20px;
}
.p-row > div {
  flex: 1;
  min-width: 0;
}
.p-row b {
  display: block;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.p-row small,
.act small,
.v-info small,
.sh-file small {
  display: block;
  color: var(--gp-ink-3);
  font-size: 11.5px;
}
.p-up,
.p-ok {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 11px;
  background: var(--gp-ink);
  color: #fff;
}
.p-ok {
  background: var(--gp-panel);
  color: var(--gp-ink);
}
.bar {
  height: 3px;
  margin-top: 7px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--gp-panel-2);
}
.bar i {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: var(--gp-accent);
  animation: up 7s ease-in-out infinite alternate;
}
@keyframes up {
  from {
    width: 34%;
  }
  to {
    width: 88%;
  }
}
.fd {
  width: 36px;
  height: 30px;
  flex: none;
}
.ft {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 11px;
  background: var(--gp-panel);
  color: var(--gp-ink-2);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.03em;
}

/* 预览 */
.player {
  position: relative;
  flex: none;
  height: 178px;
  margin: 8px 12px 0;
  overflow: hidden;
  border-radius: 20px;
  color: #fff;
}
.scene {
  position: absolute;
  inset: 0;
}
.scene::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: 57%;
  height: 2px;
  background: rgba(255, 240, 220, 0.45);
  box-shadow: 0 9px 0 -0.5px rgba(255, 240, 220, 0.18);
}
.resume {
  position: absolute;
  left: 10px;
  top: 10px;
  padding: 4px 9px;
  border-radius: 8px;
  background: rgba(12, 16, 28, 0.55);
  font-size: 11px;
  font-weight: 600;
}
.subline {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 30px;
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  text-align: center;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.6);
}
.seek {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 14px;
  height: 3px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.35);
}
.seek i {
  position: relative;
  display: block;
  width: 62%;
  height: 100%;
  border-radius: 2px;
  background: var(--gp-accent);
}
.seek i::after {
  content: '';
  position: absolute;
  right: -5px;
  top: -3.5px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
}
.v-info {
  padding: 14px 20px 0;
}
.v-info b {
  display: block;
  font-size: 16px;
  font-weight: 700;
}

/* 动态 */
.chips {
  display: flex;
  gap: 6px;
  padding: 14px 16px 6px;
}
.chips span {
  padding: 4px 12px;
  border-radius: 999px;
  background: var(--gp-panel);
  color: var(--gp-ink-2);
  font-size: 12px;
}
.chips .on {
  background: var(--gp-ink);
  color: #fff;
}
.act {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 18px;
}
.act .who {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 11px;
  background: var(--gp-panel);
  color: var(--gp-ink-2);
}
.act .who.ai {
  background: #fde8de;
  color: var(--gp-accent);
}
.act > div {
  flex: 1;
  min-width: 0;
  line-height: 1.45;
}
.undo {
  padding: 4px 10px;
  border: 1px solid var(--gp-line);
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 600;
}
.toast {
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 22px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  border-radius: 16px;
  background: var(--gp-ink);
  box-shadow: 0 12px 24px -10px rgba(0, 0, 0, 0.5);
  color: #fff;
}
.toast .i {
  color: var(--gp-accent);
}
.toast span {
  margin-left: auto;
  color: rgba(255, 255, 255, 0.6);
  font-weight: 600;
}

/* 分享 */
.dim {
  position: relative;
}
.dim::after {
  content: '';
  position: absolute;
  inset: -200px 0 -400px;
  background: rgba(18, 18, 18, 0.32);
}
.sheet {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 10px 20px 26px;
  border-radius: 26px 26px 0 0;
  background: #fff;
}
.grab {
  width: 36px;
  height: 4px;
  margin: 0 auto 14px;
  border-radius: 2px;
  background: var(--gp-panel-2);
}
.sh-file {
  display: flex;
  align-items: center;
  gap: 12px;
}
.sh-file b {
  display: block;
  font-size: 15px;
  font-weight: 700;
}
.sh-link {
  margin-top: 16px;
  padding: 11px 14px;
  border-radius: 12px;
  background: var(--gp-panel);
  color: var(--gp-ink-2);
  font-size: 12.5px;
}
.sh-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 0;
  border-bottom: 1px solid var(--gp-line);
  color: var(--gp-ink-2);
}
.sh-row > b {
  margin-left: auto;
  color: var(--gp-ink);
  font-weight: 700;
  letter-spacing: 0.08em;
}
.sw {
  position: relative;
  width: 36px;
  height: 21px;
  border-radius: 11px;
  background: var(--gp-ink);
}
.sw::after {
  content: '';
  position: absolute;
  right: 3px;
  top: 3px;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: #fff;
}
.seg {
  display: flex;
  margin-left: auto;
  padding: 2px;
  border-radius: 999px;
  background: var(--gp-panel);
}
.seg i {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-style: normal;
}
.seg .on {
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
  color: var(--gp-ink);
  font-weight: 700;
}
.sh-btn {
  margin-top: 18px;
  padding: 13px 0;
  border-radius: 999px;
  background: var(--gp-ink);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  text-align: center;
}

@media (prefers-reduced-motion: reduce) {
  .bar i {
    animation: none;
  }
  .screen {
    transition: none;
  }
}
</style>
