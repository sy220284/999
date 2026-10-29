//! Android入口只组合固定codex-worktree库；实际分配/元数据/清理均由原版管理器执行。
use std::path::PathBuf;
use std::io::{Read, stdin};
use std::collections::HashMap;
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct Input { desktop: HashMap<String, serde_json::Value>, base: Option<String> }
use anyhow::{Context, Result, ensure};
use codex_worktree::{CreateWorktree, WorktreeManager, WorktreeSettings};
fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let home = PathBuf::from(std::env::var_os("CODEX_HOME").context("missing CODEX_HOME")?);
    let mut document = Vec::new();
    stdin().take(65537).read_to_end(&mut document)?;
    ensure!(document.len() <= 65536, "worktree configuration exceeds budget");
    let input: Input = serde_json::from_slice(&document)?;
    let settings = WorktreeSettings::for_cli(&home, Some(&input.desktop))?;
    let manager = WorktreeManager::new(settings.clone());
    match args.as_slice() {
        [command, _source] if command == "settings" => {
            println!("{}", serde_json::json!({"root":settings.root,"autoCleanup":settings.auto_cleanup_enabled,"keepCount":settings.keep_count}));
        }
        [command, source] if command == "list" => {
            let mut rows = Vec::new();
            for checkout in manager.list(&PathBuf::from(source))? {
                let mut value = serde_json::to_value(&checkout)?;
                value["ownerThreadId"] = serde_json::to_value(manager.owner(&checkout.root)?)?;
                rows.push(value);
            }
            println!("{}", serde_json::to_string(&rows)?);
        }
        [command, source] if command == "create" => {
            let source_cwd = PathBuf::from(source);
            let base = match input.base { Some(value) => { ensure!(!value.is_empty() && value.len() <= 1024 && !value.chars().any(char::is_control), "invalid base"); value }, None => codex_worktree::default_worktree_base(&source_cwd)? };
            let checkout = manager.create(&CreateWorktree { source_cwd, base: Some(base) })?;
            println!("{}", serde_json::to_string(&checkout)?);
        }
        [command, source, root, thread] if command == "bind" => {
            let checkout = manager.list(&PathBuf::from(source))?.into_iter().find(|x| x.root == PathBuf::from(root)).context("unknown managed checkout")?;
            ensure!(manager.owner(&checkout.root)?.is_none(), "checkout already has a thread owner");
            ensure!(!thread.is_empty() && thread.len() <= 256, "invalid thread identity");
            manager.bind_thread(&checkout.root, thread)?;
            println!("{{\"bound\":true}}");
        }
        [command, source, root] if command == "remove" => {
            manager.remove(&PathBuf::from(source), &PathBuf::from(root))?;
            println!("{{\"removed\":true}}");
        }
        _ => anyhow::bail!("expected list/create SOURCE, bind SOURCE ROOT THREAD, or remove SOURCE ROOT"),
    }
    Ok(())
}
