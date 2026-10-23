// 首屏前先按记住的外观挂上 <html class="dark">,免得深色用户先闪一下白(CSP 不许内联脚本,单独一个文件)。
// key 与取值和 src/composables/themeMode.ts 保持一致。
;(function () {
  try {
    var m = localStorage.getItem('gopan_theme')
    var dark = m === 'dark' || (m !== 'light' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches)
    if (dark) {
      document.documentElement.classList.add('dark')
      var meta = document.querySelector('meta[name="theme-color"]')
      if (meta) meta.setAttribute('content', '#141312')
    }
  } catch (e) {
    // 读不到存储就按浅色
  }
})()
