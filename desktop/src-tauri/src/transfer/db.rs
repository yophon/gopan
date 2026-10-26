//! 传输队列(SQLite):下载 / 上传任务,文件夹是一行批次加若干行子任务。重启后正在跑的回到排队,接着传。

use rusqlite::{params, Connection, OptionalExtension, Row};
use serde::Serialize;
use std::path::Path;
use std::sync::Mutex;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Download,
    Upload,
}

impl Kind {
    fn as_str(self) -> &'static str {
        match self {
            Kind::Download => "download",
            Kind::Upload => "upload",
        }
    }
    fn parse(s: &str) -> Kind {
        if s == "upload" {
            Kind::Upload
        } else {
            Kind::Download
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum State {
    Queued,
    Running,
    Paused,
    Done,
    Failed,
}

impl State {
    pub fn as_str(self) -> &'static str {
        match self {
            State::Queued => "queued",
            State::Running => "running",
            State::Paused => "paused",
            State::Done => "done",
            State::Failed => "failed",
        }
    }
    fn parse(s: &str) -> State {
        match s {
            "running" => State::Running,
            "paused" => State::Paused,
            "done" => State::Done,
            "failed" => State::Failed,
            _ => State::Queued,
        }
    }
    pub fn terminal(self) -> bool {
        matches!(self, State::Done | State::Failed)
    }
}

#[derive(Debug, Clone)]
pub struct Task {
    pub id: i64,
    pub kind: Kind,
    /// 属于哪个文件夹批次;顶层为 None
    pub batch_id: Option<i64>,
    /// 文件夹批次行(本身不传,展开成子任务)
    pub is_batch: bool,
    /// 批次已经展开(子任务建好了)
    pub expanded: bool,
    pub state: State,
    pub name: String,
    /// 下载:云端节点;上传:传完后的节点
    pub node_id: Option<String>,
    /// 上传:云端目标文件夹(None = 根目录)
    pub parent_id: Option<String>,
    /// 下载:最终存放的路径(批次是本地根目录);上传:本地源文件 / 源文件夹
    pub local_path: String,
    pub size: i64,
    pub done_bytes: i64,
    pub sha256: Option<String>,
    /// 上传会话(续传用)
    pub session_id: Option<String>,
    /// 上传源文件的修改时间(毫秒):变了就重新算哈希
    pub src_mtime: i64,
    pub to_chat: bool,
    /// 上传:同名文件直接替换(旧内容进历史版本)
    pub replace: bool,
    /// 空 = 普通传输;open = 下载后用本地程序打开(local_path 是确切位置,覆盖);edit = 本地改完传回(见 edits.rs)
    pub purpose: String,
    pub error: Option<String>,
    pub attempts: i64,
    /// 排队重试:这个时刻之前不跑(毫秒)
    pub next_at: i64,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Default)]
pub struct NewTask {
    pub kind: Option<Kind>,
    pub batch_id: Option<i64>,
    pub is_batch: bool,
    pub name: String,
    pub node_id: Option<String>,
    pub parent_id: Option<String>,
    pub local_path: String,
    pub size: i64,
    pub to_chat: bool,
    pub replace: bool,
    pub purpose: String,
}

/// 用本地程序打开的文件(edits.rs):缓存里的副本与云端节点的对应,存盘后传回
#[derive(Debug, Clone)]
pub struct EditRow {
    pub node_id: String,
    pub name: String,
    pub parent_id: Option<String>,
    pub local_path: String,
    /// 云端现在的内容(下载或传回之后)
    pub base_sha: String,
    /// 本地副本上一次与云端一致时的内容
    pub synced_sha: String,
    /// 上一次看过的本地文件修改时间(毫秒)与大小
    pub mtime: i64,
    pub size: i64,
    pub uploads: i64,
    /// downloading / watching / uploading / conflict / stopped
    pub state: String,
    pub note: Option<String>,
    pub updated_at: i64,
}

pub struct Db {
    conn: Mutex<Connection>,
}

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,
  batch_id INTEGER,
  is_batch INTEGER NOT NULL DEFAULT 0,
  expanded INTEGER NOT NULL DEFAULT 0,
  state TEXT NOT NULL,
  name TEXT NOT NULL,
  node_id TEXT,
  parent_id TEXT,
  local_path TEXT NOT NULL,
  size INTEGER NOT NULL DEFAULT 0,
  done_bytes INTEGER NOT NULL DEFAULT 0,
  sha256 TEXT,
  session_id TEXT,
  src_mtime INTEGER NOT NULL DEFAULT 0,
  to_chat INTEGER NOT NULL DEFAULT 0,
  replace_existing INTEGER NOT NULL DEFAULT 0,
  purpose TEXT NOT NULL DEFAULT '',
  error TEXT,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_at INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tasks_state ON tasks(state);
CREATE INDEX IF NOT EXISTS idx_tasks_batch ON tasks(batch_id);
CREATE TABLE IF NOT EXISTS edits (
  node_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  parent_id TEXT,
  local_path TEXT NOT NULL,
  base_sha TEXT NOT NULL DEFAULT '',
  synced_sha TEXT NOT NULL DEFAULT '',
  mtime INTEGER NOT NULL DEFAULT 0,
  size INTEGER NOT NULL DEFAULT 0,
  uploads INTEGER NOT NULL DEFAULT 0,
  state TEXT NOT NULL,
  note TEXT,
  updated_at INTEGER NOT NULL
);
";

/// 3.14.0 建的库没有这些列:补上(SQLite 只能一列一列加)
const ADDED_COLUMNS: &[(&str, &str)] = &[("replace_existing", "INTEGER NOT NULL DEFAULT 0"), ("purpose", "TEXT NOT NULL DEFAULT ''")];

const EDIT_COLUMNS: &str = "node_id, name, parent_id, local_path, base_sha, synced_sha, mtime, size, uploads, state, note, updated_at";

fn row_to_edit(r: &Row<'_>) -> rusqlite::Result<EditRow> {
    Ok(EditRow {
        node_id: r.get(0)?,
        name: r.get(1)?,
        parent_id: r.get(2)?,
        local_path: r.get(3)?,
        base_sha: r.get(4)?,
        synced_sha: r.get(5)?,
        mtime: r.get(6)?,
        size: r.get(7)?,
        uploads: r.get(8)?,
        state: r.get(9)?,
        note: r.get(10)?,
        updated_at: r.get(11)?,
    })
}

const COLUMNS: &str = "id, kind, batch_id, is_batch, expanded, state, name, node_id, parent_id, local_path, size, done_bytes, sha256, session_id, src_mtime, to_chat, error, attempts, next_at, created_at, updated_at, replace_existing, purpose";

fn row_to_task(r: &Row<'_>) -> rusqlite::Result<Task> {
    Ok(Task {
        id: r.get(0)?,
        kind: Kind::parse(&r.get::<_, String>(1)?),
        batch_id: r.get(2)?,
        is_batch: r.get::<_, i64>(3)? != 0,
        expanded: r.get::<_, i64>(4)? != 0,
        state: State::parse(&r.get::<_, String>(5)?),
        name: r.get(6)?,
        node_id: r.get(7)?,
        parent_id: r.get(8)?,
        local_path: r.get(9)?,
        size: r.get(10)?,
        done_bytes: r.get(11)?,
        sha256: r.get(12)?,
        session_id: r.get(13)?,
        src_mtime: r.get(14)?,
        to_chat: r.get::<_, i64>(15)? != 0,
        error: r.get(16)?,
        attempts: r.get(17)?,
        next_at: r.get(18)?,
        created_at: r.get(19)?,
        updated_at: r.get(20)?,
        replace: r.get::<_, i64>(21)? != 0,
        purpose: r.get(22)?,
    })
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

impl Db {
    pub fn open(path: &Path) -> rusqlite::Result<Db> {
        let conn = Connection::open(path)?;
        Self::init(conn)
    }

    #[cfg(test)]
    pub fn open_in_memory() -> rusqlite::Result<Db> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(conn: Connection) -> rusqlite::Result<Db> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.execute_batch(SCHEMA)?;
        for (name, decl) in ADDED_COLUMNS {
            let has: bool =
                conn.query_row("SELECT count(*) FROM pragma_table_info('tasks') WHERE name = ?1", [name], |r| r.get::<_, i64>(0))? > 0;
            if !has {
                conn.execute_batch(&format!("ALTER TABLE tasks ADD COLUMN {name} {decl}"))?;
            }
        }
        // 上次没跑完的(进程被杀、断电):回到排队
        conn.execute("UPDATE tasks SET state = 'queued' WHERE state = 'running'", [])?;
        Ok(Db { conn: Mutex::new(conn) })
    }

    pub fn insert(&self, t: &NewTask) -> rusqlite::Result<i64> {
        let c = self.conn.lock().unwrap();
        let now = now_ms();
        c.execute(
            "INSERT INTO tasks (kind, batch_id, is_batch, state, name, node_id, parent_id, local_path, size, to_chat, replace_existing, purpose, created_at, updated_at)
             VALUES (?1, ?2, ?3, 'queued', ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?12)",
            params![
                t.kind.unwrap_or(Kind::Download).as_str(),
                t.batch_id,
                t.is_batch as i64,
                t.name,
                t.node_id,
                t.parent_id,
                t.local_path,
                t.size,
                t.to_chat as i64,
                t.replace as i64,
                t.purpose,
                now
            ],
        )?;
        Ok(c.last_insert_rowid())
    }

    /// 批量插子任务(一个事务)
    pub fn insert_many(&self, list: &[NewTask]) -> rusqlite::Result<()> {
        let mut c = self.conn.lock().unwrap();
        let tx = c.transaction()?;
        let now = now_ms();
        for t in list {
            tx.execute(
                "INSERT INTO tasks (kind, batch_id, is_batch, state, name, node_id, parent_id, local_path, size, to_chat, replace_existing, purpose, created_at, updated_at)
                 VALUES (?1, ?2, 0, 'queued', ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?11)",
                params![
                    t.kind.unwrap_or(Kind::Download).as_str(),
                    t.batch_id,
                    t.name,
                    t.node_id,
                    t.parent_id,
                    t.local_path,
                    t.size,
                    t.to_chat as i64,
                    t.replace as i64,
                    t.purpose,
                    now
                ],
            )?;
        }
        tx.commit()
    }

    pub fn get(&self, id: i64) -> Option<Task> {
        let c = self.conn.lock().unwrap();
        c.query_row(&format!("SELECT {COLUMNS} FROM tasks WHERE id = ?1"), [id], row_to_task).optional().ok().flatten()
    }

    pub fn all(&self) -> Vec<Task> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare(&format!("SELECT {COLUMNS} FROM tasks ORDER BY id")).unwrap();
        st.query_map([], row_to_task).map(|it| it.filter_map(Result::ok).collect()).unwrap_or_default()
    }

    pub fn children(&self, batch: i64) -> Vec<Task> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare(&format!("SELECT {COLUMNS} FROM tasks WHERE batch_id = ?1 ORDER BY id")).unwrap();
        st.query_map([batch], row_to_task).map(|it| it.filter_map(Result::ok).collect()).unwrap_or_default()
    }

    /// 能开跑的:排队中、到点了;批次(没展开的)也算,由调度器展开
    pub fn runnable(&self, now: i64) -> Vec<Task> {
        let c = self.conn.lock().unwrap();
        let mut st = c
            .prepare(&format!(
                "SELECT {COLUMNS} FROM tasks WHERE state = 'queued' AND next_at <= ?1 AND (is_batch = 0 OR expanded = 0) ORDER BY id"
            ))
            .unwrap();
        st.query_map([now], row_to_task).map(|it| it.filter_map(Result::ok).collect()).unwrap_or_default()
    }

    pub fn set_state(&self, id: i64, state: State, error: Option<&str>) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute(
            "UPDATE tasks SET state = ?2, error = ?3, updated_at = ?4 WHERE id = ?1",
            params![id, state.as_str(), error, now_ms()],
        );
    }

    /// 只在当前状态是 [from] 时改(运行结果落地时用户刚暂停 / 取消,以用户为准)
    pub fn transition(&self, id: i64, from: State, to: State, error: Option<&str>) -> bool {
        let c = self.conn.lock().unwrap();
        c.execute(
            "UPDATE tasks SET state = ?3, error = ?4, updated_at = ?5 WHERE id = ?1 AND state = ?2",
            params![id, from.as_str(), to.as_str(), error, now_ms()],
        )
        .map(|n| n == 1)
        .unwrap_or(false)
    }

    pub fn retry_later(&self, id: i64, attempts: i64, next_at: i64, error: &str) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute(
            "UPDATE tasks SET state = 'queued', attempts = ?2, next_at = ?3, error = ?4, updated_at = ?5 WHERE id = ?1 AND state = 'running'",
            params![id, attempts, next_at, error, now_ms()],
        );
    }

    pub fn set_progress(&self, id: i64, done: i64) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute("UPDATE tasks SET done_bytes = ?2 WHERE id = ?1", params![id, done]);
    }

    pub fn set_download_target(&self, id: i64, sha256: &str, size: i64) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute("UPDATE tasks SET sha256 = ?2, size = ?3 WHERE id = ?1", params![id, sha256, size]);
    }

    pub fn set_upload_source(&self, id: i64, sha256: &str, size: i64, mtime: i64) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute(
            "UPDATE tasks SET sha256 = ?2, size = ?3, src_mtime = ?4, session_id = NULL WHERE id = ?1",
            params![id, sha256, size, mtime],
        );
    }

    pub fn set_parent(&self, id: i64, parent: Option<&str>) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute("UPDATE tasks SET parent_id = ?2 WHERE id = ?1", params![id, parent]);
    }

    /// 下次重新算哈希、开新会话(服务端说内容对不上时)
    pub fn reset_upload(&self, id: i64) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute("UPDATE tasks SET sha256 = NULL, session_id = NULL WHERE id = ?1", [id]);
    }

    pub fn set_session(&self, id: i64, session: Option<&str>) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute("UPDATE tasks SET session_id = ?2 WHERE id = ?1", params![id, session]);
    }

    pub fn finish(&self, id: i64, node_id: Option<&str>, local_path: Option<&str>, size: i64) -> bool {
        let c = self.conn.lock().unwrap();
        c.execute(
            "UPDATE tasks SET state = 'done', error = NULL, node_id = COALESCE(?2, node_id), local_path = COALESCE(?3, local_path),
             done_bytes = ?4, size = ?4, updated_at = ?5 WHERE id = ?1 AND state = 'running'",
            params![id, node_id, local_path, size, now_ms()],
        )
        .map(|n| n == 1)
        .unwrap_or(false)
    }

    pub fn mark_expanded(&self, id: i64, total: i64, local_path: Option<&str>, parent_id: Option<&str>) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute(
            "UPDATE tasks SET expanded = 1, state = 'running', size = ?2, local_path = COALESCE(?3, local_path), node_id = COALESCE(?4, node_id), updated_at = ?5 WHERE id = ?1",
            params![id, total, local_path, parent_id, now_ms()],
        );
    }

    /// 暂停 / 继续 / 重试作用到批次与它没结束的子任务
    pub fn set_tree_state(&self, id: i64, from: &[State], to: State) {
        let c = self.conn.lock().unwrap();
        let froms: Vec<&str> = from.iter().map(|s| s.as_str()).collect();
        let list = froms.iter().map(|s| format!("'{s}'")).collect::<Vec<_>>().join(",");
        let now = now_ms();
        let _ = c.execute(
            &format!("UPDATE tasks SET state = ?2, next_at = 0, attempts = CASE WHEN ?2 = 'queued' THEN 0 ELSE attempts END, updated_at = ?3 WHERE (id = ?1 OR batch_id = ?1) AND state IN ({list})"),
            params![id, to.as_str(), now],
        );
    }

    pub fn delete_tree(&self, id: i64) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute("DELETE FROM tasks WHERE id = ?1 OR batch_id = ?1", [id]);
    }

    pub fn delete_all(&self) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute("DELETE FROM tasks", []);
        let _ = c.execute("DELETE FROM edits", []);
    }

    pub fn delete_one(&self, id: i64) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute("DELETE FROM tasks WHERE id = ?1", [id]);
    }

    /// 某个节点还没结束的「打开 / 传回」任务
    pub fn active_purpose(&self, node_id: &str, purpose: &str) -> Option<Task> {
        let c = self.conn.lock().unwrap();
        c.query_row(
            &format!("SELECT {COLUMNS} FROM tasks WHERE node_id = ?1 AND purpose = ?2 AND state IN ('queued', 'running', 'paused') ORDER BY id DESC LIMIT 1"),
            params![node_id, purpose],
            row_to_task,
        )
        .optional()
        .ok()
        .flatten()
    }

    // ---- 用本地程序打开的文件 ----

    pub fn edits(&self) -> Vec<EditRow> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare(&format!("SELECT {EDIT_COLUMNS} FROM edits ORDER BY updated_at DESC")).unwrap();
        st.query_map([], row_to_edit).map(|it| it.filter_map(Result::ok).collect()).unwrap_or_default()
    }

    pub fn edit(&self, node_id: &str) -> Option<EditRow> {
        let c = self.conn.lock().unwrap();
        c.query_row(&format!("SELECT {EDIT_COLUMNS} FROM edits WHERE node_id = ?1"), [node_id], row_to_edit).optional().ok().flatten()
    }

    pub fn put_edit(&self, e: &EditRow) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute(
            &format!("INSERT OR REPLACE INTO edits ({EDIT_COLUMNS}) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)"),
            params![
                e.node_id,
                e.name,
                e.parent_id,
                e.local_path,
                e.base_sha,
                e.synced_sha,
                e.mtime,
                e.size,
                e.uploads,
                e.state,
                e.note,
                now_ms()
            ],
        );
    }

    pub fn delete_edit(&self, node_id: &str) {
        let c = self.conn.lock().unwrap();
        let _ = c.execute("DELETE FROM edits WHERE node_id = ?1", [node_id]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(kind: Kind, name: &str) -> NewTask {
        NewTask { kind: Some(kind), name: name.into(), local_path: format!("C:/x/{name}"), size: 10, ..Default::default() }
    }

    #[test]
    fn databases_from_3_14_get_the_new_columns() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, batch_id INTEGER, is_batch INTEGER NOT NULL DEFAULT 0,
               expanded INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL, name TEXT NOT NULL, node_id TEXT, parent_id TEXT, local_path TEXT NOT NULL,
               size INTEGER NOT NULL DEFAULT 0, done_bytes INTEGER NOT NULL DEFAULT 0, sha256 TEXT, session_id TEXT, src_mtime INTEGER NOT NULL DEFAULT 0,
               to_chat INTEGER NOT NULL DEFAULT 0, error TEXT, attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0,
               created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
             INSERT INTO tasks (kind, state, name, local_path, created_at, updated_at) VALUES ('download', 'running', 'a', 'C:/a', 1, 1);",
        )
        .unwrap();
        let db = Db::init(conn).unwrap();
        let old = db.all().pop().unwrap();
        assert_eq!(old.state, State::Queued);
        assert!(!old.replace);
        assert_eq!(old.purpose, "");
        let id = db
            .insert(&NewTask {
                kind: Some(Kind::Upload),
                name: "b".into(),
                local_path: "C:/b".into(),
                replace: true,
                purpose: "edit".into(),
                ..Default::default()
            })
            .unwrap();
        let b = db.get(id).unwrap();
        assert!(b.replace);
        assert_eq!(b.purpose, "edit");
    }

    #[test]
    fn edit_sessions_round_trip() {
        let db = Db::open_in_memory().unwrap();
        let mut e = EditRow {
            node_id: "n1".into(),
            name: "报告.docx".into(),
            parent_id: Some("p".into()),
            local_path: "C:/cache/n1/报告.docx".into(),
            base_sha: "aa".into(),
            synced_sha: "aa".into(),
            mtime: 5,
            size: 9,
            uploads: 0,
            state: "watching".into(),
            note: None,
            updated_at: 0,
        };
        db.put_edit(&e);
        e.uploads = 2;
        e.state = "uploading".into();
        db.put_edit(&e);
        let got = db.edit("n1").unwrap();
        assert_eq!((got.uploads, got.state.as_str(), got.name.as_str()), (2, "uploading", "报告.docx"));
        assert_eq!(db.edits().len(), 1);
        db.delete_edit("n1");
        assert!(db.edit("n1").is_none());
    }

    #[test]
    fn running_tasks_go_back_to_the_queue_on_restart() {
        let db = Db::open_in_memory().unwrap();
        let id = db.insert(&file(Kind::Download, "a")).unwrap();
        db.set_state(id, State::Running, None);
        // 模拟重启:同一个连接再跑一遍初始化语句
        db.conn.lock().unwrap().execute("UPDATE tasks SET state = 'queued' WHERE state = 'running'", []).unwrap();
        assert_eq!(db.get(id).unwrap().state, State::Queued);
    }

    #[test]
    fn transitions_respect_the_current_state_and_trees_move_together() {
        let db = Db::open_in_memory().unwrap();
        let batch = db
            .insert(&NewTask {
                kind: Some(Kind::Download),
                is_batch: true,
                name: "dir".into(),
                local_path: "C:/x".into(),
                ..Default::default()
            })
            .unwrap();
        db.insert_many(&[
            NewTask { batch_id: Some(batch), ..file(Kind::Download, "a") },
            NewTask { batch_id: Some(batch), ..file(Kind::Download, "b") },
        ])
        .unwrap();
        let kids = db.children(batch);
        assert_eq!(kids.len(), 2);
        db.set_state(kids[0].id, State::Running, None);
        assert!(!db.transition(kids[0].id, State::Queued, State::Done, None));
        assert!(db.finish(kids[0].id, None, Some("C:/x/a (1)"), 10));
        assert_eq!(db.get(kids[0].id).unwrap().local_path, "C:/x/a (1)");

        db.set_tree_state(batch, &[State::Queued, State::Running], State::Paused);
        assert_eq!(db.get(kids[1].id).unwrap().state, State::Paused);
        assert_eq!(db.get(kids[0].id).unwrap().state, State::Done);
        db.set_tree_state(batch, &[State::Paused], State::Queued);
        assert_eq!(db.get(kids[1].id).unwrap().state, State::Queued);

        db.delete_tree(batch);
        assert!(db.all().is_empty());
    }

    #[test]
    fn runnable_skips_expanded_batches_and_future_retries() {
        let db = Db::open_in_memory().unwrap();
        let a = db.insert(&file(Kind::Upload, "a")).unwrap();
        let b = db.insert(&file(Kind::Upload, "b")).unwrap();
        db.set_state(b, State::Running, None);
        db.retry_later(b, 1, now_ms() + 60_000, "网络断了");
        let batch = db
            .insert(&NewTask {
                kind: Some(Kind::Upload),
                is_batch: true,
                name: "d".into(),
                local_path: "C:/d".into(),
                ..Default::default()
            })
            .unwrap();
        let ids: Vec<i64> = db.runnable(now_ms()).iter().map(|t| t.id).collect();
        assert_eq!(ids, vec![a, batch]);
        db.mark_expanded(batch, 0, None, None);
        let ids: Vec<i64> = db.runnable(now_ms()).iter().map(|t| t.id).collect();
        assert_eq!(ids, vec![a]);
    }
}
