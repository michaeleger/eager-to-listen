//! Eager to Listen desktop: the native side.
//!
//! The UI (src/) holds all podcast logic, shared rule-for-rule with the Android app. This side only
//! does what a web page can't: save the library to disk, fetch feeds without CORS limits, read and
//! write the phone's sync file (through rclone or a plain folder), and download episodes.

use futures_util::StreamExt;
use serde::Serialize;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::AsyncWriteExt;

const USER_AGENT: &str = concat!(
    "EagerToListen-Desktop/",
    env!("CARGO_PKG_VERSION"),
    " (+https://eagertobehealthy.com/eager-to-listen)"
);
const MAX_TEXT_BYTES: usize = 20 * 1024 * 1024;
const LIBRARY_FILE: &str = "library.json";

const SYNC_MAIN: &str = "eagertolisten_sync.json";
const SYNC_TMP: &str = "eagertolisten_sync.tmp.json";
const SYNC_BAK: &str = "eagertolisten_sync.bak.json";
const ARCHIVE_MAIN: &str = "eagertolisten_bookmarks.jsonl";
const ARCHIVE_TMP: &str = "eagertolisten_bookmarks.tmp.jsonl";

type CmdResult<T> = Result<T, String>;

fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

fn data_dir(app: &AppHandle) -> CmdResult<PathBuf> {
    let dir = app.path().app_data_dir().map_err(err)?;
    std::fs::create_dir_all(&dir).map_err(err)?;
    Ok(dir)
}

/// Write a file so a crash mid-write never leaves it half written: temp file, flush, rename.
async fn write_atomic(path: &Path, bytes: &[u8]) -> CmdResult<()> {
    let tmp = path.with_extension("tmp");
    {
        let mut f = tokio::fs::File::create(&tmp).await.map_err(err)?;
        f.write_all(bytes).await.map_err(err)?;
        f.sync_all().await.map_err(err)?;
    }
    tokio::fs::rename(&tmp, path).await.map_err(err)
}

// ---------------------------------------------------------------- library

#[tauri::command]
async fn load_library(app: AppHandle) -> CmdResult<Option<String>> {
    let dir = data_dir(&app)?;
    for name in [LIBRARY_FILE, "library.bak.json"] {
        let p = dir.join(name);
        if let Ok(text) = tokio::fs::read_to_string(&p).await {
            if serde_json::from_str::<serde_json::Value>(&text).is_ok() {
                return Ok(Some(text));
            }
        }
    }
    Ok(None)
}

#[tauri::command]
async fn save_library(app: AppHandle, json: String) -> CmdResult<()> {
    serde_json::from_str::<serde_json::Value>(&json).map_err(|e| format!("Refusing to save a broken library: {e}"))?;
    let dir = data_dir(&app)?;
    let main = dir.join(LIBRARY_FILE);
    if main.exists() {
        let _ = tokio::fs::copy(&main, dir.join("library.bak.json")).await;
    }
    write_atomic(&main, json.as_bytes()).await
}

// ---------------------------------------------------------------- network

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FetchResult {
    status: u16,
    body: String,
    final_url: String,
    etag: Option<String>,
    last_modified: Option<String>,
}

fn http_client() -> CmdResult<reqwest::Client> {
    reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .redirect(reqwest::redirect::Policy::limited(8))
        .build()
        .map_err(err)
}

/// GET a feed, transcript, chapters file or search result as text.
#[tauri::command]
async fn fetch_text(url: String, etag: Option<String>, last_modified: Option<String>) -> CmdResult<FetchResult> {
    let parsed = reqwest::Url::parse(url.trim()).map_err(|_| "That isn't a web address.".to_string())?;
    if parsed.scheme() != "http" && parsed.scheme() != "https" {
        return Err("Only http and https addresses work here.".into());
    }
    let mut req = http_client()?.get(parsed);
    if let Some(e) = etag.filter(|s| !s.is_empty()) {
        req = req.header(reqwest::header::IF_NONE_MATCH, e);
    }
    if let Some(m) = last_modified.filter(|s| !s.is_empty()) {
        req = req.header(reqwest::header::IF_MODIFIED_SINCE, m);
    }
    let resp = req.send().await.map_err(|e| format!("Couldn't reach {url}: {e}"))?;
    let status = resp.status().as_u16();
    let final_url = resp.url().to_string();
    let header = |h: reqwest::header::HeaderName| resp.headers().get(h).and_then(|v| v.to_str().ok()).map(String::from);
    let etag = header(reqwest::header::ETAG);
    let last_modified = header(reqwest::header::LAST_MODIFIED);

    let mut body = Vec::new();
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(err)?;
        if body.len() + chunk.len() > MAX_TEXT_BYTES {
            return Err("That file is too big to be a podcast feed.".into());
        }
        body.extend_from_slice(&chunk);
    }
    Ok(FetchResult { status, body: String::from_utf8_lossy(&body).into_owned(), final_url, etag, last_modified })
}

/// Podcast Index API (podcastindex.org), signed the way the Android client signs it:
/// Authorization = sha1(key + secret + unix seconds). Keys are the user's own, from Settings.
#[tauri::command]
async fn podcastindex_get(path: String, api_key: String, api_secret: String) -> CmdResult<String> {
    let (key, secret) = (api_key.trim(), api_secret.trim());
    if key.is_empty() || secret.is_empty() {
        return Err("Add your Podcast Index API key and secret in Settings.".into());
    }
    let allowed = ["search/byterm?", "podcasts/bycategory?", "podcasts/trending?", "podcasts/byfeedurl?"];
    if !allowed.iter().any(|a| path.starts_with(a)) {
        return Err("That Podcast Index request isn't supported.".into());
    }
    let ts = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_err(err)?.as_secs().to_string();
    let auth = sha1_smol::Sha1::from(format!("{key}{secret}{ts}")).digest().to_string();
    let resp = http_client()?
        .get(format!("https://api.podcastindex.org/api/1.0/{path}"))
        .header("X-Auth-Date", &ts)
        .header("X-Auth-Key", key)
        .header("Authorization", auth)
        .send()
        .await
        .map_err(|e| format!("Couldn't reach Podcast Index: {e}"))?;
    let status = resp.status();
    let body = resp.text().await.map_err(err)?;
    if status.as_u16() == 401 || status.as_u16() == 403 {
        return Err("Podcast Index turned down the API key or secret. Check them in Settings.".into());
    }
    if !status.is_success() {
        return Err(format!("Podcast Index returned an error ({status})."));
    }
    Ok(body)
}

// ---------------------------------------------------------------- sync target
//
// A target is either a local folder ("/home/me/Drive/EagerToListen") or an rclone remote path
// ("gdrive:EagerToListen"). Either way the app sees the same handful of files the phone uses.

enum Target {
    Folder(PathBuf),
    Rclone(String),
}

fn parse_target(t: &str) -> CmdResult<Target> {
    let t = t.trim();
    if t.is_empty() {
        return Err("No sync folder set. Add one in Settings.".into());
    }
    let looks_local = t.starts_with('/') || t.starts_with('~') || (t.len() > 2 && t.as_bytes()[1] == b':' && t.as_bytes()[2] == b'\\');
    if looks_local {
        let p = if let Some(rest) = t.strip_prefix("~/") {
            std::env::var_os("HOME").map(PathBuf::from).unwrap_or_default().join(rest)
        } else {
            PathBuf::from(t)
        };
        return Ok(Target::Folder(p));
    }
    if !t.contains(':') {
        return Err("Use an rclone remote and folder (remote:Folder), or a full folder path.".into());
    }
    Ok(Target::Rclone(t.trim_end_matches('/').to_string()))
}

fn rclone_path(remote: &str, name: &str) -> String {
    if remote.ends_with(':') {
        format!("{remote}{name}")
    } else {
        format!("{remote}/{name}")
    }
}

/// Where rclone lives. Desktop launchers often don't include ~/.local/bin on the PATH, and on a
/// box without sudo that's exactly where a user-level rclone gets installed, so look there too.
fn rclone_bin() -> Option<PathBuf> {
    let exe = if cfg!(windows) { "rclone.exe" } else { "rclone" };
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            let p = dir.join(exe);
            if p.is_file() {
                return Some(p);
            }
        }
    }
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")).map(PathBuf::from)?;
    [
        home.join(".local/bin").join(exe),
        home.join("bin").join(exe),
        home.join("rclone").join(exe),
        PathBuf::from("/usr/local/bin").join(exe),
        PathBuf::from("/usr/bin").join(exe),
    ]
    .into_iter()
    .find(|p| p.is_file())
}

async fn rclone(args: &[&str], stdin: Option<&[u8]>) -> CmdResult<Vec<u8>> {
    rclone_with_timeout(args, stdin, Duration::from_secs(120)).await
}

async fn rclone_with_timeout(args: &[&str], stdin: Option<&[u8]>, limit: Duration) -> CmdResult<Vec<u8>> {
    let bin = rclone_bin().ok_or("rclone isn't installed. See Settings for how to add it without sudo.")?;
    let mut cmd = tokio::process::Command::new(bin);
    cmd.args(args).stdout(Stdio::piped()).stderr(Stdio::piped());
    cmd.stdin(if stdin.is_some() { Stdio::piped() } else { Stdio::null() });
    #[cfg(windows)]
    {
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let mut child = cmd.spawn().map_err(|e| {
        if e.kind() == std::io::ErrorKind::NotFound {
            "rclone isn't installed (or isn't on the PATH).".to_string()
        } else {
            e.to_string()
        }
    })?;
    if let Some(bytes) = stdin {
        let mut pipe = child.stdin.take().ok_or("rclone stdin unavailable")?;
        pipe.write_all(bytes).await.map_err(err)?;
        drop(pipe);
    }
    let out = tokio::time::timeout(limit, child.wait_with_output())
        .await
        .map_err(|_| format!("rclone took longer than {} seconds.", limit.as_secs()))?
        .map_err(err)?;
    if !out.status.success() {
        let msg = String::from_utf8_lossy(&out.stderr);
        let last = msg.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("rclone failed");
        return Err(format!("rclone: {}", last.trim()));
    }
    Ok(out.stdout)
}

async fn target_list(t: &Target) -> CmdResult<Vec<String>> {
    match t {
        Target::Folder(dir) => {
            let mut out = Vec::new();
            let mut rd = tokio::fs::read_dir(dir).await.map_err(|e| format!("Can't open {}: {e}", dir.display()))?;
            while let Some(entry) = rd.next_entry().await.map_err(err)? {
                if entry.file_type().await.map(|f| f.is_file()).unwrap_or(false) {
                    out.push(entry.file_name().to_string_lossy().into_owned());
                }
            }
            Ok(out)
        }
        Target::Rclone(remote) => {
            let raw = rclone(&["lsf", "--files-only", "--max-depth", "1", remote], None).await?;
            Ok(String::from_utf8_lossy(&raw).lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect())
        }
    }
}

async fn target_read(t: &Target, name: &str) -> CmdResult<String> {
    match t {
        Target::Folder(dir) => tokio::fs::read_to_string(dir.join(name)).await.map_err(err),
        Target::Rclone(remote) => Ok(String::from_utf8_lossy(&rclone(&["cat", &rclone_path(remote, name)], None).await?).into_owned()),
    }
}

async fn target_write(t: &Target, name: &str, bytes: &[u8]) -> CmdResult<()> {
    match t {
        Target::Folder(dir) => {
            let mut f = tokio::fs::File::create(dir.join(name)).await.map_err(err)?;
            f.write_all(bytes).await.map_err(err)?;
            f.sync_all().await.map_err(err)
        }
        Target::Rclone(remote) => rclone(&["rcat", &rclone_path(remote, name)], Some(bytes)).await.map(|_| ()),
    }
}

async fn target_delete(t: &Target, name: &str) -> CmdResult<()> {
    match t {
        Target::Folder(dir) => tokio::fs::remove_file(dir.join(name)).await.map_err(err),
        Target::Rclone(remote) => rclone(&["deletefile", &rclone_path(remote, name)], None).await.map(|_| ()),
    }
}

#[tauri::command]
async fn sync_list(target: String) -> CmdResult<Vec<String>> {
    target_list(&parse_target(&target)?).await
}

#[tauri::command]
async fn sync_read(target: String, name: String) -> CmdResult<String> {
    if name.contains('/') || name.contains('\\') {
        return Err("Bad file name".into());
    }
    target_read(&parse_target(&target)?, &name).await
}

#[tauri::command]
async fn sync_delete(target: String, name: String) -> CmdResult<()> {
    let is_sync_copy = name.starts_with("eagertolisten_sync") && name != SYNC_MAIN;
    let is_archive_copy = name.starts_with("eagertolisten_bookmarks") && name.ends_with(".jsonl") && name != ARCHIVE_MAIN;
    if !(is_sync_copy || is_archive_copy) || name.contains('/') || name.contains('\\') {
        return Err("Only duplicate sync or archive copies can be deleted.".into());
    }
    target_delete(&parse_target(&target)?, &name).await
}

/// The same write-safety steps the phone uses: write .tmp.json, read it back and check it, copy
/// the current file to .bak.json, write the main file, remove the temp file.
#[tauri::command]
async fn sync_write(target: String, json: String) -> CmdResult<()> {
    let t = parse_target(&target)?;
    let value: serde_json::Value = serde_json::from_str(&json).map_err(err)?;
    if value.get("format").and_then(|f| f.as_i64()) != Some(2) {
        return Err("Refusing to write a sync file that isn't format 2.".into());
    }
    target_write(&t, SYNC_TMP, json.as_bytes()).await?;
    let back = target_read(&t, SYNC_TMP).await?;
    let check: serde_json::Value = serde_json::from_str(&back).map_err(|_| "The sync file didn't read back cleanly.".to_string())?;
    if check.get("format").and_then(|f| f.as_i64()) != Some(2) {
        return Err("The sync file didn't read back cleanly.".into());
    }
    if target_list(&t).await?.iter().any(|n| n == SYNC_MAIN) {
        if let Ok(current) = target_read(&t, SYNC_MAIN).await {
            target_write(&t, SYNC_BAK, current.as_bytes()).await?;
        }
    }
    target_write(&t, SYNC_MAIN, json.as_bytes()).await?;
    let _ = target_delete(&t, SYNC_TMP).await;
    Ok(())
}

/// Write the bookmark archive (JSON Lines) safely: temp file, read it back and check every line,
/// then the real file. The archive only ever grows, so refuse a write that would shrink it a lot.
#[tauri::command]
async fn archive_write(target: String, text: String) -> CmdResult<()> {
    let t = parse_target(&target)?;
    let first = text.lines().find(|l| !l.trim().is_empty()).unwrap_or("");
    if serde_json::from_str::<serde_json::Value>(first).is_err() {
        return Err("Refusing to write an archive whose first line isn't JSON.".into());
    }
    if target_list(&t).await?.iter().any(|n| n == ARCHIVE_MAIN) {
        if let Ok(current) = target_read(&t, ARCHIVE_MAIN).await {
            let (old, new) = (archive_entries(&current), archive_entries(&text));
            if new < old {
                return Err(format!("Refusing to shrink the bookmark archive ({old} bookmarks to {new})."));
            }
        }
    }
    target_write(&t, ARCHIVE_TMP, text.as_bytes()).await?;
    let back = target_read(&t, ARCHIVE_TMP).await?;
    if back != text {
        return Err("The bookmark archive didn't read back cleanly.".into());
    }
    target_write(&t, ARCHIVE_MAIN, text.as_bytes()).await?;
    let _ = target_delete(&t, ARCHIVE_TMP).await;
    Ok(())
}

/// Distinct bookmarks in an archive (by id), plus any lines that aren't JSON (those are kept too).
fn archive_entries(text: &str) -> usize {
    let mut ids = std::collections::HashSet::new();
    let mut other = 0;
    for line in text.lines().filter(|l| !l.trim().is_empty()) {
        match serde_json::from_str::<serde_json::Value>(line).ok().and_then(|v| v.get("id").and_then(|i| i.as_str()).map(String::from)) {
            Some(id) => {
                ids.insert(id);
            }
            None => other += 1,
        }
    }
    ids.len() + other
}

/// Save text (the share list) to a file in Downloads; returns the path.
#[tauri::command]
async fn save_text_file(app: AppHandle, file_name: String, text: String) -> CmdResult<String> {
    let dir = app.path().download_dir().or_else(|_| app.path().home_dir()).map_err(err)?;
    tokio::fs::create_dir_all(&dir).await.map_err(err)?;
    let name = safe_file_name(&file_name);
    let mut path = dir.join(&name);
    let mut n = 1;
    while path.exists() {
        let stem = Path::new(&name).file_stem().and_then(|s| s.to_str()).unwrap_or("bookmarks");
        let ext = Path::new(&name).extension().and_then(|s| s.to_str()).unwrap_or("md");
        path = dir.join(format!("{stem}-{n}.{ext}"));
        n += 1;
    }
    write_atomic(&path, text.as_bytes()).await?;
    Ok(path.to_string_lossy().into_owned())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RcloneStatus {
    installed: bool,
    path: Option<String>,
    version: Option<String>,
    remotes: Vec<String>,
    error: Option<String>,
}

#[tauri::command]
async fn rclone_status() -> RcloneStatus {
    match rclone(&["version"], None).await {
        Err(e) => RcloneStatus { installed: false, path: None, version: None, remotes: vec![], error: Some(e) },
        Ok(v) => {
            let version = String::from_utf8_lossy(&v).lines().next().map(|s| s.trim().to_string());
            let remotes = rclone(&["listremotes"], None)
                .await
                .map(|r| String::from_utf8_lossy(&r).lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect())
                .unwrap_or_default();
            let path = rclone_bin().map(|p| p.to_string_lossy().into_owned());
            RcloneStatus { installed: true, path, version, remotes, error: None }
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FoundSyncFolder {
    /// Ready to use as the sync folder, e.g. "gdrive:Apps/Eager to Listen"
    target: String,
    mod_time: Option<String>,
    size: Option<u64>,
}

fn remote_join(remote: &str, dir: &str) -> String {
    // remote is "name:" or "name:sub/path"; dir is relative to it
    let dir = dir.trim_matches('/');
    if dir.is_empty() {
        remote.to_string()
    } else if remote.ends_with(':') {
        format!("{remote}{dir}")
    } else {
        format!("{}/{dir}", remote.trim_end_matches('/'))
    }
}

/// Find the phone's sync file wherever it was saved: search every rclone remote (or just the
/// one given) for eagertolisten_sync.json. Nothing about the folder is assumed or hard coded.
#[tauri::command]
async fn sync_find(remote: Option<String>) -> CmdResult<Vec<FoundSyncFolder>> {
    let remotes: Vec<String> = match remote.filter(|r| !r.trim().is_empty()) {
        Some(r) => vec![r.trim().to_string()],
        None => String::from_utf8_lossy(&rclone(&["listremotes"], None).await?)
            .lines()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect(),
    };
    if remotes.is_empty() {
        return Err("rclone has no remotes yet. Run `rclone config` once to connect Google Drive.".into());
    }
    let mut found = Vec::new();
    let mut errors = Vec::new();
    for r in &remotes {
        let args = ["lsjson", "-R", "--fast-list", "--files-only", "--no-mimetype", "--include", SYNC_MAIN, r.as_str()];
        match rclone_with_timeout(&args, None, Duration::from_secs(300)).await {
            Ok(raw) => {
                let items: Vec<serde_json::Value> = serde_json::from_slice(&raw).map_err(err)?;
                for it in items {
                    let path = it.get("Path").and_then(|p| p.as_str()).unwrap_or_default();
                    let dir = path.rsplit_once('/').map(|(d, _)| d).unwrap_or("");
                    found.push(FoundSyncFolder {
                        target: remote_join(r, dir),
                        mod_time: it.get("ModTime").and_then(|m| m.as_str()).map(String::from),
                        size: it.get("Size").and_then(|s| s.as_u64()),
                    });
                }
            }
            Err(e) => errors.push(format!("{r} {e}")),
        }
    }
    if found.is_empty() && !errors.is_empty() {
        return Err(errors.join("; "));
    }
    // Newest first: the folder the phone wrote to most recently is almost always the right one
    found.sort_by(|a, b| b.mod_time.cmp(&a.mod_time));
    Ok(found)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BrowseResult {
    target: String,
    folders: Vec<String>,
    has_sync_file: bool,
}

/// List the folders inside a remote or local folder, for picking a sync folder by hand.
#[tauri::command]
async fn sync_browse(target: String) -> CmdResult<BrowseResult> {
    match parse_target(&target)? {
        Target::Folder(dir) => {
            let mut folders = Vec::new();
            let mut has_sync_file = false;
            let mut rd = tokio::fs::read_dir(&dir).await.map_err(|e| format!("Can't open {}: {e}", dir.display()))?;
            while let Some(entry) = rd.next_entry().await.map_err(err)? {
                let name = entry.file_name().to_string_lossy().into_owned();
                let ft = entry.file_type().await.map_err(err)?;
                if ft.is_dir() && !name.starts_with('.') {
                    folders.push(name);
                } else if name == SYNC_MAIN {
                    has_sync_file = true;
                }
            }
            folders.sort_by_key(|f| f.to_lowercase());
            Ok(BrowseResult { target, folders, has_sync_file })
        }
        Target::Rclone(remote) => {
            let raw = rclone(&["lsf", "--max-depth", "1", &remote], None).await?;
            let text = String::from_utf8_lossy(&raw);
            let mut folders: Vec<String> = text.lines().filter_map(|l| l.strip_suffix('/')).map(String::from).collect();
            folders.sort_by_key(|f| f.to_lowercase());
            let has_sync_file = text.lines().any(|l| l == SYNC_MAIN);
            Ok(BrowseResult { target: remote, folders, has_sync_file })
        }
    }
}

// ---------------------------------------------------------------- downloads

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DownloadProgress {
    episode_id: String,
    received: u64,
    total: Option<u64>,
}

fn safe_file_name(s: &str) -> String {
    let cleaned: String = s.chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' { c } else { '_' }).collect();
    cleaned.chars().take(120).collect()
}

/// Download an episode into the app's downloads folder; returns the file path.
#[tauri::command]
async fn download_episode(app: AppHandle, episode_id: String, url: String, file_stem: String) -> CmdResult<String> {
    let dir = data_dir(&app)?.join("downloads");
    tokio::fs::create_dir_all(&dir).await.map_err(err)?;
    let parsed = reqwest::Url::parse(&url).map_err(err)?;
    let ext = Path::new(parsed.path())
        .extension()
        .and_then(|e| e.to_str())
        .filter(|e| e.len() <= 4 && e.chars().all(|c| c.is_ascii_alphanumeric()))
        .unwrap_or("mp3")
        .to_lowercase();
    let dest = dir.join(format!("{}.{ext}", safe_file_name(&file_stem)));
    let part = dest.with_extension(format!("{ext}.part"));

    let client = reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .connect_timeout(Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::limited(10))
        .build()
        .map_err(err)?;
    let resp = client.get(parsed).send().await.map_err(err)?.error_for_status().map_err(err)?;
    let total = resp.content_length();
    let mut file = tokio::fs::File::create(&part).await.map_err(err)?;
    let mut received: u64 = 0;
    let mut last_emit: u64 = 0;
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(err)?;
        file.write_all(&chunk).await.map_err(err)?;
        received += chunk.len() as u64;
        if received - last_emit > 512 * 1024 {
            last_emit = received;
            let _ = app.emit("download-progress", DownloadProgress { episode_id: episode_id.clone(), received, total });
        }
    }
    file.sync_all().await.map_err(err)?;
    drop(file);
    tokio::fs::rename(&part, &dest).await.map_err(err)?;
    let _ = app.emit("download-progress", DownloadProgress { episode_id, received, total: Some(received) });
    Ok(dest.to_string_lossy().into_owned())
}

#[tauri::command]
async fn delete_download(app: AppHandle, path: String) -> CmdResult<()> {
    let dir = data_dir(&app)?.join("downloads");
    let p = PathBuf::from(&path);
    // Only ever delete inside our own downloads folder
    let canon = std::fs::canonicalize(&p).map_err(err)?;
    let base = std::fs::canonicalize(&dir).map_err(err)?;
    if !canon.starts_with(&base) {
        return Err("That file isn't one of ours.".into());
    }
    tokio::fs::remove_file(canon).await.map_err(err)
}

// ---------------------------------------------------------------- app

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            load_library,
            save_library,
            fetch_text,
            sync_list,
            sync_read,
            sync_write,
            sync_delete,
            rclone_status,
            podcastindex_get,
            archive_write,
            save_text_file,
            sync_find,
            sync_browse,
            download_episode,
            delete_download
        ])
        .run(tauri::generate_context!())
        .expect("error while running Eager to Listen");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn targets() {
        assert!(matches!(parse_target("gdrive:EagerToListen").unwrap(), Target::Rclone(ref r) if r == "gdrive:EagerToListen"));
        assert!(matches!(parse_target("gdrive:").unwrap(), Target::Rclone(_)));
        assert!(matches!(parse_target("/home/me/sync").unwrap(), Target::Folder(_)));
        assert!(matches!(parse_target("C:\\Users\\me\\Drive").unwrap(), Target::Folder(_)));
        assert!(parse_target("").is_err());
        assert!(parse_target("justaname").is_err());
        assert_eq!(rclone_path("gdrive:", "a.json"), "gdrive:a.json");
        assert_eq!(rclone_path("gdrive:Sync", "a.json"), "gdrive:Sync/a.json");
    }

    #[test]
    fn podcastindex_signature() {
        // sha1("abc") is a fixed, published value; the header is the lowercase hex digest
        assert_eq!(sha1_smol::Sha1::from("abc").digest().to_string(), "a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    #[test]
    fn archive_counting() {
        let a = "{\"id\":\"a\"}\n{\"id\":\"a\"}\n{\"id\":\"b\"}\nnot json\n";
        assert_eq!(archive_entries(a), 3);
    }

    #[test]
    fn joins() {
        assert_eq!(remote_join("gd:", ""), "gd:");
        assert_eq!(remote_join("gd:", "Apps/Eager to Listen"), "gd:Apps/Eager to Listen");
        assert_eq!(remote_join("gd:Base", "Sub"), "gd:Base/Sub");
    }

    #[test]
    fn file_names() {
        assert_eq!(safe_file_name("a b/c:d"), "a_b_c_d");
    }
}
