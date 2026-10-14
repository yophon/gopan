// gopan 安卓 App 的页面操作(Compose 语义 → uiautomator 节点):登录页、底部 tab、文件列表行、对话框、下拉刷新。
// 文案取自 app/src/main/res/values/strings_*.xml;坐标一律取 dump 出的 bounds 中心,输入框聚焦后键盘会顶起布局,所以每步重新 dump。
import { DeviceError } from './device.mjs';
import { APP_ID, sleep } from './util.mjs';

export const T = {
  idLogin: '使用 Yophon ID 登录',
  idConnecting: '正在连接账号中心…',
  otherAccount: '其他账号使用密码登录',
  backToId: '返回使用 Yophon ID 登录',
  tabLogin: '登录',
  submit: '登录',
  badCredentials: '用户名或密码错误',
  idRequired: '请使用 Yophon ID 登录',
  tabs: ['文件', '传输助手', '分享', '我的'],
  driveRoot: '文件',
  offline: '网络不可用',
  emptyFolder: '这个文件夹是空的',
};

export class App {
  constructor(device) {
    this.d = device;
  }

  dump() {
    return this.d.dump();
  }

  // ---- 页面识别 ----

  isLogin(tree) {
    return tree.has(T.idLogin) || tree.has(T.otherAccount) || tree.has(T.backToId) || tree.has(T.idConnecting);
  }

  isMain(tree) {
    return T.tabs.every((t) => tree.has({ text: t }));
  }

  /** 前台是不是本 App */
  inForeground() {
    return this.d.focusedWindow().startsWith(`${APP_ID}/`);
  }

  async waitLogin(timeout = 30000) {
    return this.d.waitFor((t) => this.isLogin(t) && t, { timeout, label: '登录页' });
  }

  async waitMain(timeout = 30000) {
    return this.d.waitFor((t) => this.isMain(t) && t, { timeout, label: '主界面(底部四个 tab)' });
  }

  /** 冷启动:force-stop 后启动 MainActivity */
  coldStart() {
    this.d.forceStop();
    return this.d.launch();
  }

  // ---- 底部导航 ----

  /** 底部导航项:同名文本取最靠下的那个(根目录标题也叫「文件」) */
  navItem(tree, label) {
    const hits = tree.find({ text: label });
    return hits.sort((a, b) => b.cy - a.cy)[0] || null;
  }

  async gotoTab(label) {
    const tree = await this.waitMain();
    const item = this.navItem(tree, label);
    this.d.tapXY(item.cx, item.cy);
    await sleep(700);
  }

  // ---- 登录页 ----

  editTexts(tree) {
    return tree.find({ cls: 'EditText' }).sort((a, b) => a.cy - b.cy);
  }

  /** 点输入框 → 重新 dump 确认聚焦 → 清空 → 输入 */
  async fill(index, text, { clear = true } = {}) {
    let fields = this.editTexts(this.dump());
    if (!fields[index]) throw new DeviceError(`第 ${index + 1} 个输入框不存在`);
    this.d.tapXY(fields[index].cx, fields[index].cy);
    await sleep(500);
    fields = this.editTexts(this.dump());
    if (!fields[index]?.focused) {
      this.d.tapXY(fields[index].cx, fields[index].cy);
      await sleep(500);
    }
    if (clear) this.d.clearFocused(Math.max(40, (fields[index]?.text || '').length + 5));
    if (text) this.d.type(text);
    await sleep(200);
  }

  /** 展开密码登录面板(已展开则不动) */
  async openPasswordPanel() {
    const tree = await this.waitLogin();
    if (tree.has(T.backToId)) return tree;
    await this.d.tap(T.otherAccount);
    return this.d.waitFor(T.backToId, { label: '密码登录面板' });
  }

  /** 表单下方的「登录」按钮(tab 也叫「登录」,取最靠下的) */
  async tapSubmit() {
    const tree = this.dump();
    const hits = tree.find({ text: T.submit }).sort((a, b) => b.cy - a.cy);
    if (!hits[0]) throw new DeviceError('找不到登录按钮');
    this.d.tapXY(hits[0].cx, hits[0].cy);
  }

  async passwordLogin(username, password) {
    await this.openPasswordPanel();
    await this.fill(0, username);
    await this.fill(1, password);
    this.d.hideKeyboard();
    await sleep(400);
    await this.tapSubmit();
  }

  /** 登录页横幅错误(或 null) */
  loginError(tree) {
    const known = [T.badCredentials, T.idRequired, '尝试过于频繁,请稍后再试', '网络不可用,请检查网络后重试', '登录失败,请重试'];
    return known.find((k) => tree.has(k)) || null;
  }

  // ---- 文件列表 ----

  /** 顶栏标题(列表区上方最靠上的一段文字) */
  title(tree) {
    const cands = tree.nodes.filter((n) => n.text && n.cy < 90 && n.cx < 200 && n.pkg === APP_ID);
    return cands.sort((a, b) => a.cy - b.cy || a.cx - b.cx)[0]?.text || null;
  }

  /** 列表行(名字文本节点);只认列表区(顶栏下、底部导航上)里的,按 y 排序 */
  rows(tree, { names } = {}) {
    const top = 84;
    const bottom = this.navItem(tree, '文件')?.bounds.y1 ?? 740;
    const isName = names || ((t) => /^(f-\d{3}.*|a|b|c|[a-z][\w.-]*\.(png|txt)|[a-z][\w-]*)$/.test(t));
    return tree
      .find({ filter: (n) => n.text && isName(n.text) && n.cy > top && n.cy < bottom && n.pkg === APP_ID && n.cls.endsWith('TextView') })
      .sort((a, b) => a.cy - b.cy || a.cx - b.cx);
  }

  /** 名字为 name 的行的「更多」按钮(同一行里 desc=更多 的节点) */
  moreButton(tree, name) {
    const label = tree.first({ text: name, filter: (n) => n.cy > 84 });
    if (!label) return null;
    const row = rowContainer(label);
    return tree.find({ desc: '更多', filter: (n) => n.cy >= row.bounds.y1 && n.cy <= row.bounds.y2 && n.cy > 84 })[0] || null;
  }

  async openRowMenu(name) {
    // waitFor(选择器) 返回的是命中的节点;找同一行的「更多」按钮需要整棵树
    await this.d.waitFor({ text: name }, { label: `行 ${name}` });
    let more = this.moreButton(this.dump(), name);
    // 行在首屏底部时「更多」可能被浮动按钮挡住、不在树里:往上滚一点再找
    for (let i = 0; i < 3 && !more; i++) {
      this.scrollUp(200);
      await sleep(600);
      more = this.moreButton(this.dump(), name);
    }
    if (!more) throw new DeviceError(`行 ${name} 没有「更多」按钮`);
    this.d.tapXY(more.clickableAncestor().cx, more.clickableAncestor().cy);
    return this.d.waitFor({ text: '详情' }, { label: '操作菜单' });
  }

  /** 下拉刷新:在列表区从上往下拖 */
  pullToRefresh() {
    const { w } = this.d.displaySize();
    this.d.swipe(w / 2, 220, w / 2, 640, 450);
  }

  /** 列表上滑一屏(慢速,避免惯性甩过头) */
  scrollUp(dist = 420) {
    const { w } = this.d.displaySize();
    this.d.swipe(w / 2, 660, w / 2, 660 - dist, 700);
  }

  /** 快速下甩直到首行不再变化(到顶) */
  async scrollToTop(maxFlings = 25) {
    const { w } = this.d.displaySize();
    let prev = null;
    for (let i = 0; i < maxFlings; i++) {
      this.d.swipe(w / 2, 150, w / 2, 720, 60);
      await sleep(350);
      const first = this.rows(this.dump()).map((n) => n.text).join(',');
      if (first === prev) return true;
      prev = first;
    }
    return false;
  }

  snackbar(tree, pattern) {
    return tree.first({ textMatches: pattern });
  }
}

/** 行容器:向上找宽度接近整屏的可点击祖先 */
export function rowContainer(node) {
  for (let n = node; n; n = n.parent) {
    if (n.clickable && n.width > 300) return n;
  }
  return node.clickableAncestor();
}
