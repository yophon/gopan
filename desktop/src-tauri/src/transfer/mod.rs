//! 传输引擎(doc/29 D2):下载到本地文件夹、断点续传的上传,任务存在 SQLite 里,关了再开接着传。
//!
//! - 同时最多下 3 个、传 2 个;断网、服务器忙按次数退避重试,确定性错误(没空间、文件没了)直接失败;
//! - 文件夹是一行批次:开跑时先展开成子任务(下载:列云端目录树、在本地建好目录;上传:在云端建好目录);
//! - 进度每半秒发一次 desktop://tasks 给网页的传输抽屉,托盘提示里也有;传完弹系统通知(一小会儿内的合成一条)。

mod db;
mod download;
mod names;
mod upload;

pub use db::{now_ms, EditRow, Kind, State, Task};
use db::{Db, NewTask};
pub use names::safe_name;

use crate::api::ApiError;
use crate::auth::{Auth, AuthError};
use crate::settings::SettingsStore;
use crate::{notify, tray, ui};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::async_runtime::JoinHandle;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;
use tokio::sync::Notify;

pub const TASKS_EVENT: &str = "desktop://tasks";
const MAX_DOWNLOADS: usize = 3;
const MAX_UPLOADS: usize = 2;
const MAX_ATTEMPTS: i64 = 8;
/// 这么久之内传完的合成一条通知
const TOAST_GATHER_MS: i64 = 1500;

/// 正在跑的任务的实时进度(只在内存里;库里的 done_bytes 每两秒落一次)
#[derive(Default)]
pub struct Live {
    pub done: AtomicI64,
    pub total: AtomicI64,
    phase: Mutex<&'static str>,
}

impl Live {
    pub fn phase(&self, p: &'static str) {
        *self.phase.lock().unwrap() = p;
    }
}

/// 一次运行的结果
#[derive(Debug)]
pub enum RunError {
    /// 过一会儿再试
    Retry(String),
    /// 不再自动重试
    Fail(String),
}

impl From<ApiError> for RunError {
    fn from(e: ApiError) -> Self {
        let retry = e.is_transient()
            || matches!(e, ApiError::Auth(AuthError::SignedOut))
            || matches!(e.code(), "UNAUTHENTICATED" | "TOO_MANY_SESSIONS" | "RATE_LIMITED");
        if retry {
            RunError::Retry(e.to_string())
        } else {
            RunError::Fail(e.to_string())
        }
    }
}

pub fn io_fail(e: std::io::Error) -> RunError {
    RunError::Fail(format!("读写本地文件出错:{e}"))
}

pub fn net_retry(e: reqwest::Error) -> RunError {
    RunError::Retry(format!("网络错误:{e}"))
}

#[derive(Default)]
pub struct Outcome {
    pub node_id: Option<String>,
    pub local_path: Option<String>,
    pub size: i64,
}

struct Running {
    live: Arc<Live>,
    handle: JoinHandle<()>,
    kind: Kind,
    batch: bool,
}

pub struct Engine {
    pub(crate) db: Db,
    wake: Notify,
    running: Mutex<HashMap<i64, Running>>,
    dirty: AtomicBool,
    /// 结束了、还没弹通知的顶层任务
    toasts: Mutex<Vec<i64>>,
    last_finish: AtomicI64,
}

pub fn init(app: &AppHandle) -> Result<(), String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let db = Db::open(&dir.join("transfers.db")).map_err(|e| format!("打开传输队列失败:{e}"))?;
    app.manage(Engine {
        db,
        wake: Notify::new(),
        running: Mutex::new(HashMap::new()),
        dirty: AtomicBool::new(true),
        toasts: Mutex::new(Vec::new()),
        last_finish: AtomicI64::new(0),
    });
    let a = app.clone();
    tauri::async_runtime::spawn(async move { scheduler(a).await });
    let a = app.clone();
    tauri::async_runtime::spawn(async move { reporter(a).await });
    Ok(())
}

pub(crate) fn engine(app: &AppHandle) -> tauri::State<'_, Engine> {
    app.state::<Engine>()
}

// ---- 调度 ----

async fn scheduler(app: AppHandle) {
    loop {
        // 网页交过 token 才开跑(刚启动、主窗口还没登录时不去催)
        if app.state::<Auth>().ready() {
            dispatch(&app);
        }
        let _ = tokio::time::timeout(Duration::from_secs(2), engine(&app).wake.notified()).await;
    }
}

fn dispatch(app: &AppHandle) {
    let eng = engine(app);
    let mut running = eng.running.lock().unwrap();
    let mut downs = running.values().filter(|r| !r.batch && r.kind == Kind::Download).count();
    let mut ups = running.values().filter(|r| !r.batch && r.kind == Kind::Upload).count();
    for t in eng.db.runnable(now_ms()) {
        if running.contains_key(&t.id) {
            continue;
        }
        if !t.is_batch {
            let slot = match t.kind {
                Kind::Download => &mut downs,
                Kind::Upload => &mut ups,
            };
            let max = if t.kind == Kind::Download { MAX_DOWNLOADS } else { MAX_UPLOADS };
            if *slot >= max {
                continue;
            }
            *slot += 1;
        }
        if !eng.db.transition(t.id, State::Queued, State::Running, t.error.as_deref()) {
            continue;
        }
        let live = Arc::new(Live::default());
        live.total.store(t.size, Ordering::Relaxed);
        live.done.store(t.done_bytes, Ordering::Relaxed);
        let (id, kind, batch) = (t.id, t.kind, t.is_batch);
        let a = app.clone();
        let l = live.clone();
        // 持着 running 的锁 spawn:任务就算立刻结束,也要等这里登记完才能把自己摘掉
        let handle = tauri::async_runtime::spawn(async move { run_task(a, t, l).await });
        running.insert(id, Running { live, handle, kind, batch });
        eng.dirty.store(true, Ordering::Relaxed);
    }
}

async fn run_task(app: AppHandle, task: Task, live: Arc<Live>) {
    let result = if task.is_batch {
        expand(&app, &task, &live).await
    } else {
        match task.kind {
            Kind::Download => download::run(&app, &task, &live).await,
            Kind::Upload => upload::run(&app, &task, &live).await,
        }
    };
    let eng = engine(&app);
    eng.running.lock().unwrap().remove(&task.id);
    match result {
        Ok(out) if task.is_batch => {
            // 展开好了(子任务已经入库);空文件夹直接算完
            if eng.db.children(task.id).is_empty() {
                eng.db.transition(task.id, State::Running, State::Done, None);
                finished(&app, task.id);
            }
            let _ = out;
        }
        Ok(out) => {
            if eng.db.finish(task.id, out.node_id.as_deref(), out.local_path.as_deref(), out.size) {
                if task.purpose.is_empty() {
                    finished(&app, task.id);
                } else if let Some(mut done) = eng.db.get(task.id) {
                    // 「打开」「传回」的任务做完就从列表里拿掉,结果由 edits.rs 接着处理。
                    // 做完后库里的 node_id 是传出来的节点(冲突时另存的是个新文件),edits.rs 要的是原来那个
                    done.node_id = task.node_id.clone();
                    eng.db.delete_one(task.id);
                    crate::edits::task_done(&app, &done);
                }
            }
        }
        Err(RunError::Retry(msg)) => {
            let attempts = task.attempts + 1;
            if attempts >= MAX_ATTEMPTS {
                if eng.db.transition(task.id, State::Running, State::Failed, Some(&msg)) {
                    failed(&app, &task, &msg);
                }
            } else {
                eng.db.retry_later(task.id, attempts, now_ms() + backoff_ms(attempts), &msg);
            }
        }
        Err(RunError::Fail(msg)) => {
            log::warn!("传输任务 {} 失败:{msg}", task.id);
            if eng.db.transition(task.id, State::Running, State::Failed, Some(&msg)) {
                failed(&app, &task, &msg);
            }
        }
    }
    eng.dirty.store(true, Ordering::Relaxed);
    eng.wake.notify_one();
}

/// 不再重试了:普通任务排进通知,「打开」「传回」的交给 edits.rs(任务行留着,能在列表里重试)
fn failed(app: &AppHandle, task: &Task, msg: &str) {
    if task.purpose.is_empty() {
        finished(app, task.id);
    } else {
        crate::edits::task_failed(app, task, msg);
    }
}

/// 第 n 次失败后等多久:5 秒起,翻倍,封顶 5 分钟
fn backoff_ms(attempts: i64) -> i64 {
    (5_000i64 << (attempts - 1).clamp(0, 6)).min(300_000)
}

/// 文件夹批次:展开成子任务入库
async fn expand(app: &AppHandle, batch: &Task, live: &Live) -> Result<Outcome, RunError> {
    live.phase("listing");
    let (kids, root_path, root_node) = match batch.kind {
        Kind::Download => download::expand(app, batch).await?,
        Kind::Upload => upload::expand(app, batch).await?,
    };
    let eng = engine(app);
    let total: i64 = kids.iter().map(|k| k.size).sum();
    eng.db.insert_many(&kids).map_err(|e| RunError::Fail(format!("写传输队列失败:{e}")))?;
    eng.db.mark_expanded(batch.id, total, root_path.as_deref(), root_node.as_deref());
    Ok(Outcome::default())
}

/// 一个任务到终态:属于批次的看整批完了没有;顶层的排进通知
fn finished(app: &AppHandle, id: i64) {
    let eng = engine(app);
    let Some(t) = eng.db.get(id) else { return };
    let top = match t.batch_id {
        None => t.id,
        Some(b) => {
            let kids = eng.db.children(b);
            if !kids.iter().all(|k| k.state.terminal()) {
                return;
            }
            let failed = kids.iter().filter(|k| k.state == State::Failed).count();
            let to = if failed > 0 { State::Failed } else { State::Done };
            let msg = (failed > 0).then(|| format!("{failed} 个文件没传成"));
            eng.db.set_state(b, to, msg.as_deref());
            // 文件夹发到传输助手:文件已经自动成了消息,再发一条指向整个文件夹的
            if let Some(batch) = eng.db.get(b).filter(|x| to == State::Done && x.kind == Kind::Upload && x.to_chat) {
                if let Some(node) = batch.node_id {
                    let a = app.clone();
                    tauri::async_runtime::spawn(async move {
                        if let Err(e) = upload::send_chat_node(&a, &node).await {
                            log::warn!("文件夹发到传输助手失败:{e}");
                        }
                    });
                }
            }
            b
        }
    };
    // 「上传并复制分享链接」的批次由 quickshare.rs 自己出通知
    if crate::quickshare::task_finished(app, top) {
        return;
    }
    let mut q = eng.toasts.lock().unwrap();
    if !q.contains(&top) {
        q.push(top);
    }
    eng.last_finish.store(now_ms(), Ordering::Relaxed);
}

// ---- 进度与通知 ----

async fn reporter(app: AppHandle) {
    let mut was_active = false;
    // 任务栏进度、托盘「最近传输」:变了才去改
    let mut last_progress: Option<Option<u64>> = None;
    let mut last_recent: Option<Vec<i64>> = None;
    loop {
        tokio::time::sleep(Duration::from_millis(500)).await;
        let eng = engine(&app);
        let active = !eng.running.lock().unwrap().is_empty();
        if eng.dirty.swap(false, Ordering::Relaxed) || active || was_active {
            let list = views(&app);
            let _ = app.emit(TASKS_EVENT, &list);
            tray::set_tooltip(&app, &tooltip(&list));
            let progress = overall_progress(&list);
            if last_progress != Some(progress) {
                set_taskbar_progress(&app, progress);
                last_progress = Some(progress);
            }
            let recent: Vec<i64> = recent_of(&list, RECENT_N).iter().map(|(id, _)| *id).collect();
            if last_recent.as_ref() != Some(&recent) {
                if last_recent.is_some() {
                    tray::refresh(&app);
                }
                last_recent = Some(recent);
            }
        }
        was_active = active;
        if now_ms() - eng.last_finish.load(Ordering::Relaxed) >= TOAST_GATHER_MS {
            let ids: Vec<i64> = std::mem::take(&mut *eng.toasts.lock().unwrap());
            if !ids.is_empty() {
                toast_finished(&app, &ids);
            }
        }
    }
}

/// 在传的总进度(百分比);没有在传的为 None
fn overall_progress(list: &[TaskView]) -> Option<u64> {
    let active: Vec<&TaskView> = list.iter().filter(|v| matches!(v.state, State::Running | State::Queued)).collect();
    if active.is_empty() {
        return None;
    }
    let size: i64 = active.iter().map(|v| v.size.max(0)).sum();
    let done: i64 = active.iter().map(|v| v.done.clamp(0, v.size.max(0))).sum();
    Some(if size > 0 { (done * 100 / size) as u64 } else { 0 })
}

/// 任务栏上 gopan 图标的进度条(主窗口隐藏在托盘时任务栏里没有它,也就看不到)
fn set_taskbar_progress(app: &AppHandle, progress: Option<u64>) {
    use tauri::window::{ProgressBarState, ProgressBarStatus};
    if let Some(w) = app.get_webview_window(ui::MAIN) {
        let state = match progress {
            Some(p) => ProgressBarState { status: Some(ProgressBarStatus::Normal), progress: Some(p.min(100)) },
            None => ProgressBarState { status: Some(ProgressBarStatus::None), progress: None },
        };
        let _ = w.set_progress_bar(state);
    }
}

const RECENT_N: usize = 6;

fn recent_of(list: &[TaskView], n: usize) -> Vec<(i64, String)> {
    list.iter()
        .filter(|v| v.state == State::Done && v.purpose.is_empty())
        .take(n)
        .map(|v| (v.id, format!("{} {}", if v.kind == Kind::Download { "↓" } else { "↑" }, v.name)))
        .collect()
}

/// 托盘「最近传输」:最近传完的几项(新的在前);引擎还没起来时为空
pub fn recent(app: &AppHandle, n: usize) -> Vec<(i64, String)> {
    if app.try_state::<Engine>().is_none() {
        return Vec::new();
    }
    recent_of(&views(app), n)
}

/// 点托盘里最近传输的一项:下载的在文件夹中显示;上传的去云端看它(发到传输助手的打开小窗)
pub fn open_recent(app: &AppHandle, id: i64) {
    let Some(t) = engine(app).db.get(id) else { return };
    if t.kind == Kind::Download {
        reveal(app, &PathBuf::from(&t.local_path), t.is_batch);
        return;
    }
    if t.to_chat {
        ui::open_mini(app);
        return;
    }
    let folder = if t.is_batch { t.node_id.clone() } else { t.parent_id.clone() };
    let path = folder.map(|f| format!("/drive/{f}")).unwrap_or_else(|| "/drive".into());
    ui::show_main(app);
    crate::navigate_main(app, &path);
}

/// 网页传输抽屉里的一行(顶层任务;文件夹汇总子任务)
#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct TaskView {
    id: i64,
    kind: Kind,
    state: State,
    /// hashing / preparing / transferring / verifying / listing;不在跑时为 None
    phase: Option<&'static str>,
    name: String,
    is_folder: bool,
    local_path: String,
    node_id: Option<String>,
    parent_id: Option<String>,
    to_chat: bool,
    /// 空 / open(下载完用本地程序打开)/ edit(本地改完传回)
    purpose: String,
    size: i64,
    done: i64,
    files: usize,
    files_done: usize,
    error: Option<String>,
    /// 排队等重试:到这个时刻(毫秒)再试
    retry_at: Option<i64>,
    created_at: i64,
    updated_at: i64,
}

pub fn views(app: &AppHandle) -> Vec<TaskView> {
    let eng = engine(app);
    let all = eng.db.all();
    let running = eng.running.lock().unwrap();
    let live_done = |t: &Task| -> i64 {
        if t.state == State::Done {
            return t.size;
        }
        running.get(&t.id).map(|r| r.live.done.load(Ordering::Relaxed)).unwrap_or(t.done_bytes)
    };
    let mut kids: HashMap<i64, Vec<&Task>> = HashMap::new();
    for t in all.iter().filter(|t| t.batch_id.is_some()) {
        kids.entry(t.batch_id.unwrap()).or_default().push(t);
    }
    let mut out: Vec<TaskView> = all
        .iter()
        .filter(|t| t.batch_id.is_none())
        .map(|t| {
            let mut v = TaskView {
                id: t.id,
                kind: t.kind,
                state: t.state,
                phase: running.get(&t.id).map(|r| *r.live.phase.lock().unwrap()),
                name: t.name.clone(),
                is_folder: t.is_batch,
                local_path: t.local_path.clone(),
                node_id: t.node_id.clone(),
                parent_id: t.parent_id.clone(),
                to_chat: t.to_chat,
                purpose: t.purpose.clone(),
                size: running.get(&t.id).map(|r| r.live.total.load(Ordering::Relaxed)).filter(|n| *n > 0).unwrap_or(t.size),
                done: live_done(t),
                files: 1,
                files_done: (t.state == State::Done) as usize,
                error: t.error.clone(),
                retry_at: (t.state == State::Queued && t.attempts > 0).then_some(t.next_at),
                created_at: t.created_at,
                updated_at: t.updated_at,
            };
            if t.is_batch {
                let list = kids.get(&t.id).map(|v| v.as_slice()).unwrap_or(&[]);
                v.files = list.len();
                v.files_done = list.iter().filter(|k| k.state == State::Done).count();
                if t.expanded {
                    v.size = list.iter().map(|k| k.size).sum();
                    v.done = list.iter().map(|k| live_done(k)).sum();
                    v.state = batch_state(list).unwrap_or(t.state);
                    v.phase = list.iter().find_map(|k| running.get(&k.id).map(|r| *r.live.phase.lock().unwrap()));
                } else {
                    v.done = 0;
                }
            }
            // 错误只在失败、或排队等重试时给人看
            if !(v.state == State::Failed || (v.state == State::Queued && t.attempts > 0)) {
                v.error = None;
            }
            v
        })
        .collect();
    out.reverse();
    out
}

/// 文件夹的状态看子任务:有在跑的算在跑,其次排队、暂停,全结束了看有没有失败
fn batch_state(kids: &[&Task]) -> Option<State> {
    if kids.is_empty() {
        return None;
    }
    for s in [State::Running, State::Queued, State::Paused, State::Failed] {
        if kids.iter().any(|k| k.state == s) {
            return Some(s);
        }
    }
    Some(State::Done)
}

fn tooltip(list: &[TaskView]) -> String {
    let active: Vec<&TaskView> = list.iter().filter(|v| matches!(v.state, State::Running | State::Queued)).collect();
    if active.is_empty() {
        return "gopan".into();
    }
    let downs = active.iter().filter(|v| v.kind == Kind::Download).count();
    let ups = active.len() - downs;
    let size: i64 = active.iter().map(|v| v.size).sum();
    let done: i64 = active.iter().map(|v| v.done.min(v.size)).sum();
    let mut parts = Vec::new();
    if downs > 0 {
        parts.push(format!("下载 {downs} 项"));
    }
    if ups > 0 {
        parts.push(format!("上传 {ups} 项"));
    }
    let pct = if size > 0 { format!(" · {}%", done * 100 / size) } else { String::new() };
    format!("gopan\n{}{pct}", parts.join(" · "))
}

fn toast_finished(app: &AppHandle, ids: &[i64]) {
    let eng = engine(app);
    let tasks: Vec<Task> = ids.iter().filter_map(|id| eng.db.get(*id)).collect();
    if tasks.is_empty() {
        return;
    }
    let failed: Vec<&Task> = tasks.iter().filter(|t| t.state == State::Failed).collect();
    if tasks.len() == 1 {
        let t = &tasks[0];
        let verb = match (t.kind, t.to_chat) {
            (Kind::Download, _) => "下载",
            (Kind::Upload, true) => "发送",
            (Kind::Upload, false) => "上传",
        };
        if t.state == State::Failed {
            let reason = t.error.clone().unwrap_or_default();
            notify::toast(app, &format!("{verb}失败"), &format!("{}:{reason}", t.name), Some(open_transfers(app)));
            return;
        }
        let title = if t.to_chat { "已发到传输助手".to_string() } else { format!("{verb}完成") };
        let body = if t.is_batch { format!("{}({} 个文件)", t.name, eng.db.children(t.id).len()) } else { t.name.clone() };
        let click: Box<dyn Fn() + Send + Sync> = match t.kind {
            Kind::Download => {
                let (a, path, folder) = (app.clone(), PathBuf::from(&t.local_path), t.is_batch);
                Box::new(move || reveal(&a, &path, folder))
            }
            Kind::Upload if t.to_chat => {
                let a = app.clone();
                Box::new(move || {
                    let h = a.clone();
                    let _ = a.run_on_main_thread(move || ui::open_mini(&h));
                })
            }
            Kind::Upload => {
                let (a, folder) = (app.clone(), t.parent_id.clone());
                Box::new(move || {
                    let h = a.clone();
                    let path = folder.as_ref().map(|f| format!("/drive/{f}")).unwrap_or_else(|| "/drive".into());
                    let _ = a.run_on_main_thread(move || {
                        ui::show_main(&h);
                        crate::navigate_main(&h, &path);
                    });
                })
            }
        };
        notify::toast(app, &title, &body, Some(click));
        return;
    }
    let downs = tasks.iter().filter(|t| t.kind == Kind::Download).count();
    let ups = tasks.len() - downs;
    let mut parts = Vec::new();
    if downs > 0 {
        parts.push(format!("下载 {downs} 项"));
    }
    if ups > 0 {
        parts.push(format!("上传 {ups} 项"));
    }
    let title = if failed.is_empty() { "传输完成".to_string() } else { format!("{} 项没传成", failed.len()) };
    notify::toast(app, &title, &parts.join("、"), Some(open_transfers(app)));
}

/// 点通知:主窗口打开传输列表
fn open_transfers(app: &AppHandle) -> Box<dyn Fn() + Send + Sync> {
    let a = app.clone();
    Box::new(move || {
        let h = a.clone();
        let _ = a.run_on_main_thread(move || {
            ui::show_main(&h);
            let _ = h.emit_to(ui::MAIN, "desktop://open-transfers", ());
        });
    })
}

fn reveal(app: &AppHandle, path: &std::path::Path, folder: bool) {
    let r = if folder { app.opener().open_path(path.to_string_lossy(), None::<&str>) } else { app.opener().reveal_item_in_dir(path) };
    if let Err(e) = r {
        log::warn!("打开文件夹失败:{e}");
    }
}

// ---- 入队与操作 ----

/// 下载一组云端条目到本地目录;返回目录
pub fn enqueue_downloads(app: &AppHandle, dir: &std::path::Path, items: &[DownloadItem]) -> Result<(), String> {
    std::fs::create_dir_all(dir).map_err(|e| format!("建不了下载目录 {}:{e}", dir.display()))?;
    let eng = engine(app);
    for it in items {
        let t = if it.is_folder {
            NewTask {
                kind: Some(Kind::Download),
                is_batch: true,
                name: it.name.clone(),
                node_id: Some(it.id.clone()),
                local_path: dir.to_string_lossy().into_owned(),
                ..Default::default()
            }
        } else {
            NewTask {
                kind: Some(Kind::Download),
                name: it.name.clone(),
                node_id: Some(it.id.clone()),
                local_path: dir.join(names::safe_name(&it.name)).to_string_lossy().into_owned(),
                size: it.size.unwrap_or(0),
                ..Default::default()
            }
        };
        eng.db.insert(&t).map_err(|e| format!("写传输队列失败:{e}"))?;
    }
    eng.dirty.store(true, Ordering::Relaxed);
    eng.wake.notify_one();
    Ok(())
}

/// 上传本地文件 / 文件夹到云端文件夹(None = 根目录);[to_chat] 发到传输助手
/// [replace]:这些名字的文件(只看顶层的文件)遇到同名直接替换,旧内容进历史版本
pub fn enqueue_uploads(
    app: &AppHandle,
    paths: &[PathBuf],
    parent_id: Option<String>,
    to_chat: bool,
    replace: &[String],
) -> Result<usize, String> {
    enqueue_uploads_ids(app, paths, parent_id, to_chat, replace).map(|ids| ids.len())
}

/// 同上,返回入队的顶层任务 id(读不到的路径跳过)
pub fn enqueue_uploads_ids(
    app: &AppHandle,
    paths: &[PathBuf],
    parent_id: Option<String>,
    to_chat: bool,
    replace: &[String],
) -> Result<Vec<i64>, String> {
    let eng = engine(app);
    let mut ids = Vec::new();
    for p in paths {
        let Ok(meta) = std::fs::metadata(p) else { continue };
        let name = p.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| p.to_string_lossy().into_owned());
        let t = NewTask {
            kind: Some(Kind::Upload),
            is_batch: meta.is_dir(),
            replace: !meta.is_dir() && replace.contains(&name),
            name,
            parent_id: parent_id.clone(),
            local_path: p.to_string_lossy().into_owned(),
            size: if meta.is_dir() { 0 } else { meta.len() as i64 },
            to_chat,
            ..Default::default()
        };
        ids.push(eng.db.insert(&t).map_err(|e| format!("写传输队列失败:{e}"))?);
    }
    eng.dirty.store(true, Ordering::Relaxed);
    eng.wake.notify_one();
    Ok(ids)
}

/// 队列里的一个任务(quickshare.rs 看它做完的结果)
pub fn task(app: &AppHandle, id: i64) -> Option<Task> {
    engine(app).db.get(id)
}

/// 下载一个云端文件到确切的位置(覆盖),做完由 edits.rs 用本地程序打开
pub fn enqueue_open(app: &AppHandle, node_id: &str, name: &str, size: i64, path: &std::path::Path) -> Result<(), String> {
    let eng = engine(app);
    let t = NewTask {
        kind: Some(Kind::Download),
        name: name.to_string(),
        node_id: Some(node_id.to_string()),
        local_path: path.to_string_lossy().into_owned(),
        size,
        purpose: "open".into(),
        ..Default::default()
    };
    eng.db.insert(&t).map_err(|e| format!("写传输队列失败:{e}"))?;
    eng.dirty.store(true, Ordering::Relaxed);
    eng.wake.notify_one();
    Ok(())
}

/// 把本地改过的副本传回云端:[name] 是云端的名字;[replace] 为 false 时是另存一份(冲突时)
pub fn enqueue_save_back(app: &AppHandle, e: &EditRow, name: &str, replace: bool) -> Result<(), String> {
    let eng = engine(app);
    let t = NewTask {
        kind: Some(Kind::Upload),
        name: name.to_string(),
        node_id: Some(e.node_id.clone()),
        parent_id: e.parent_id.clone(),
        local_path: e.local_path.clone(),
        size: e.size,
        replace,
        purpose: "edit".into(),
        ..Default::default()
    };
    eng.db.insert(&t).map_err(|e| format!("写传输队列失败:{e}"))?;
    eng.dirty.store(true, Ordering::Relaxed);
    eng.wake.notify_one();
    Ok(())
}

fn stop_running(eng: &Engine, ids: &[i64]) {
    let mut running = eng.running.lock().unwrap();
    for id in ids {
        if let Some(r) = running.remove(id) {
            r.handle.abort();
        }
    }
}

fn tree_ids(eng: &Engine, id: i64) -> Vec<i64> {
    let mut ids = vec![id];
    ids.extend(eng.db.children(id).iter().map(|k| k.id));
    ids
}

/// 取消 / 移除时把没下完的 .part 删掉,没传完的上传会话关掉
fn cleanup(app: &AppHandle, tasks: Vec<Task>) {
    let mut sessions = Vec::new();
    for t in tasks {
        match t.kind {
            Kind::Download if !t.is_batch && t.state != State::Done => {
                let _ = std::fs::remove_file(download::part_path(&PathBuf::from(&t.local_path), t.id));
            }
            Kind::Upload if t.state != State::Done => sessions.extend(t.session_id),
            _ => {}
        }
    }
    if !sessions.is_empty() && app.state::<Auth>().ready() {
        let a = app.clone();
        tauri::async_runtime::spawn(async move {
            for s in sessions {
                upload::abort_session(&a, &s).await;
            }
        });
    }
}

pub fn action(app: &AppHandle, id: Option<i64>, action: &str) -> Result<(), String> {
    let eng = engine(app);
    match (action, id) {
        ("clear", _) => {
            // 清除已结束(完成和失败的):失败的下载还留着 .part,一起删
            for t in eng.db.all().into_iter().filter(|t| t.batch_id.is_none() && t.state.terminal()) {
                let ids = tree_ids(&eng, t.id);
                let tasks: Vec<Task> = ids.iter().filter_map(|i| eng.db.get(*i)).collect();
                eng.db.delete_tree(t.id);
                cleanup(app, tasks);
            }
        }
        ("pause", Some(id)) => {
            eng.db.set_tree_state(id, &[State::Queued, State::Running], State::Paused);
            stop_running(&eng, &tree_ids(&eng, id));
        }
        ("resume", Some(id)) => eng.db.set_tree_state(id, &[State::Paused], State::Queued),
        ("retry", Some(id)) => eng.db.set_tree_state(id, &[State::Failed, State::Paused], State::Queued),
        ("cancel" | "remove", Some(id)) => {
            let ids = tree_ids(&eng, id);
            stop_running(&eng, &ids);
            let tasks: Vec<Task> = ids.iter().filter_map(|i| eng.db.get(*i)).collect();
            eng.db.delete_tree(id);
            cleanup(app, tasks);
        }
        ("open" | "reveal", Some(id)) => {
            let t = eng.db.get(id).ok_or("任务不存在")?;
            let path = PathBuf::from(&t.local_path);
            if !names::exists(&path) {
                return Err("文件已经不在原来的位置了".into());
            }
            if action == "open" && !t.is_batch {
                app.opener().open_path(path.to_string_lossy(), None::<&str>).map_err(|e| e.to_string())?;
            } else {
                reveal(app, &path, t.is_batch);
            }
            return Ok(());
        }
        _ => return Err(format!("不认识的操作 {action}")),
    }
    eng.dirty.store(true, Ordering::Relaxed);
    eng.wake.notify_one();
    Ok(())
}

/// 登出:停掉所有任务、清空队列(下一个登录的可能是别人)
pub fn clear_all(app: &AppHandle) {
    let Some(eng) = app.try_state::<Engine>() else { return };
    let ids: Vec<i64> = eng.running.lock().unwrap().keys().copied().collect();
    stop_running(&eng, &ids);
    let tasks = eng.db.all();
    eng.db.delete_all();
    crate::edits::clear_cache(app);
    // 登出了没法再调接口关会话(服务端过期会自己清),只删本地的 .part
    for t in tasks {
        if t.kind == Kind::Download && !t.is_batch && t.state != State::Done {
            let _ = std::fs::remove_file(download::part_path(&PathBuf::from(&t.local_path), t.id));
        }
    }
    eng.dirty.store(true, Ordering::Relaxed);
}

// ---- 命令(远程网页调) ----

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct DownloadItem {
    id: String,
    name: String,
    #[serde(default)]
    is_folder: bool,
    size: Option<i64>,
}

/// 下载到电脑:[choose] 为 true 时先选目录(取消了返回 None),否则用设置里的下载目录。返回存到的目录
#[tauri::command]
pub async fn desktop_download(app: AppHandle, items: Vec<DownloadItem>, choose: Option<bool>) -> Result<Option<String>, String> {
    if items.is_empty() {
        return Ok(None);
    }
    let dir = if choose.unwrap_or(false) {
        let a = app.clone();
        let default = app.state::<SettingsStore>().download_dir(&app);
        let picked = tauri::async_runtime::spawn_blocking(move || {
            a.dialog().file().set_title("下载到哪个文件夹").set_directory(default).blocking_pick_folder()
        })
        .await
        .ok()
        .flatten();
        match picked.and_then(|p| p.into_path().ok()) {
            Some(p) => p,
            None => return Ok(None),
        }
    } else {
        app.state::<SettingsStore>().download_dir(&app)
    };
    enqueue_downloads(&app, &dir, &items)?;
    Ok(Some(dir.to_string_lossy().into_owned()))
}

#[tauri::command]
pub fn desktop_tasks(app: AppHandle) -> Vec<TaskView> {
    views(&app)
}

#[tauri::command]
pub fn desktop_task_action(app: AppHandle, id: Option<i64>, action: String) -> Result<(), String> {
    self::action(&app, id, &action)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_doubles_up_to_five_minutes() {
        assert_eq!(backoff_ms(1), 5_000);
        assert_eq!(backoff_ms(2), 10_000);
        assert_eq!(backoff_ms(5), 80_000);
        assert_eq!(backoff_ms(7), 300_000);
    }

    #[test]
    fn folder_state_follows_its_files() {
        let mk = |state| Task {
            id: 1,
            kind: Kind::Download,
            batch_id: Some(9),
            is_batch: false,
            expanded: false,
            state,
            name: "a".into(),
            node_id: None,
            parent_id: None,
            local_path: String::new(),
            size: 1,
            done_bytes: 0,
            sha256: None,
            session_id: None,
            src_mtime: 0,
            to_chat: false,
            replace: false,
            purpose: String::new(),
            error: None,
            attempts: 0,
            next_at: 0,
            created_at: 0,
            updated_at: 0,
        };
        let (a, b, c) = (mk(State::Done), mk(State::Failed), mk(State::Paused));
        assert_eq!(batch_state(&[&a, &b]), Some(State::Failed));
        assert_eq!(batch_state(&[&a, &b, &c]), Some(State::Paused));
        assert_eq!(batch_state(&[&a]), Some(State::Done));
        assert_eq!(batch_state(&[]), None);
    }
}
