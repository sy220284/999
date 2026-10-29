/// Android命令附加限制只在宿主冻结策略存在时启用；不把它报告为原版Linux沙箱。
#[cfg(target_os = "android")]
struct AndroidCommandPolicy { version: u32, project: String, program: String }

#[cfg(target_os = "android")]
fn android_command_environment(source: &HashMap<String, String>, root: &Path, native: &Path) -> HashMap<String, String> {
    let mut output: HashMap<String,String> = source.iter().filter(|(key, _)| matches!(key.as_str(),
        "PATH" | "LANG" | "LC_ALL" | "LC_CTYPE" | "TZ" | "TERM" | "COLORTERM" | "ANDROID_ROOT" | "ANDROID_DATA"))
        .map(|(key,value)|(key.clone(),value.clone())).collect();
    output.insert("HOME".into(),root.to_string_lossy().into_owned());
    output.insert("TMPDIR".into(),root.to_string_lossy().into_owned());
    output.insert("SHELL".into(),"/system/bin/sh".into());
    output.insert("LD_LIBRARY_PATH".into(),native.to_string_lossy().into_owned());
    output
}

#[cfg(target_os = "android")]
fn android_command_paths(policy: &AndroidCommandPolicy, cwd: &Path) -> Result<(std::path::PathBuf,std::path::PathBuf,std::path::PathBuf)> {
    anyhow::ensure!(policy.version == 1, "unknown Android command policy version");
    anyhow::ensure!(Path::new(&policy.project).is_absolute() && Path::new(&policy.program).is_absolute(), "Android command paths must be absolute");
    let root=std::fs::canonicalize(&policy.project).context("Android command project is unavailable")?;
    let cwd=std::fs::canonicalize(cwd).context("Android command cwd is unavailable")?;
    let program=std::fs::canonicalize(&policy.program).context("Android command restriction program is missing")?;
    anyhow::ensure!(root.parent().is_some() && root.is_dir() && cwd.is_dir() && cwd.starts_with(&root), "command cwd is outside the frozen Android project");
    anyhow::ensure!(program.is_file() && program.file_name().is_some_and(|x| x == "libcodex_sandbox.so"), "unexpected Android restriction program");
    Ok((root,cwd,program))
}

#[cfg(target_os = "android")]
async fn android_restricted_spawn(request: &SpawnRequest<'_>) -> Result<Option<SpawnedProcess>> {
    let Some(document)=std::env::var_os("CODEX_ANDROID_COMMAND_SANDBOX") else { return Ok(None); };
    let document=document.to_str().context("Android command policy encoding is invalid")?;
    anyhow::ensure!(document.len() <= 8192, "Android command policy exceeds budget");
    let value:serde_json::Value=serde_json::from_str(document).context("Android command policy is invalid")?;
    let object=value.as_object().context("Android command policy must be an object")?;
    anyhow::ensure!(object.len()==3 && object.keys().all(|x| ["version","project","program"].contains(&x.as_str())), "unknown Android command policy fields");
    let version=object.get("version").and_then(|x|x.as_u64()).context("missing Android command policy version")?;
    anyhow::ensure!(version==1, "unknown Android command policy version");
    let policy=AndroidCommandPolicy{version:1,project:object.get("project").and_then(|x|x.as_str()).context("missing Android project")?.to_owned(),program:object.get("program").and_then(|x|x.as_str()).context("missing Android command helper")?.to_owned()};
    // 无法保留更严格原版策略、额外fd或特殊argv0语义时拒绝，不能静默变成普通执行。
    anyhow::ensure!(request.sandbox == SandboxType::None, "Android command restriction cannot replace another requested sandbox");
    anyhow::ensure!(request.inherited_fds.as_slice().is_empty() && request.arg0.is_none(), "Android restricted command cannot inherit descriptors or override argv0");
    anyhow::ensure!(!request.command.is_empty(), "missing Android restricted command");
    let(root,cwd,program)=android_command_paths(&policy,request.cwd)?;
    let native=program.parent().context("missing Android native program directory")?;
    let environment=android_command_environment(request.env,&root,native);
    let mut args=vec!["exec".to_owned(),root.to_string_lossy().into_owned(),native.to_string_lossy().into_owned(),cwd.to_string_lossy().into_owned(),"--".to_owned()];
    args.extend_from_slice(request.command);
    let no_arg0=None;
    let spawned=if request.tty {
        codex_utils_pty::pty::spawn_process(&program,&args,&cwd,&environment,&no_arg0,TerminalSize::default(),codex_utils_pty::ChildFds::Inherited(&[])).await?
    } else if request.stdin_open {
        codex_utils_pty::pipe::spawn_process(&program,&args,&cwd,&environment,&no_arg0,&[]).await?
    } else {
        codex_utils_pty::pipe::spawn_process_no_stdin(&program,&args,&cwd,&environment,&no_arg0,&[]).await?
    };
    Ok(Some(spawned))
}

#[cfg(all(test,target_os = "android"))]
mod android_command_policy_tests {
    use super::*;
    #[test]
    fn environment_never_copies_identity_or_vault_credentials() {
        let source=HashMap::from([("PATH".to_owned(),"/system/bin".to_owned()),("CODEX_ACCESS_TOKEN".to_owned(),"secret".to_owned()),("CODEX_ANDROID_VAULT_SOCKET".to_owned(),"bridge".to_owned()),("AWS_SECRET_ACCESS_KEY".to_owned(),"aws".to_owned())]);
        let env=android_command_environment(&source,Path::new("/project"),Path::new("/native"));
        assert_eq!(env.get("PATH").unwrap(),"/system/bin");
        assert!(!env.values().any(|x| ["secret","bridge","aws"].contains(&x.as_str())));
        assert_eq!(env.get("HOME").unwrap(),"/project");
    }
    #[test]
    fn symlinked_cwd_outside_frozen_project_is_rejected() {
        let temp=tempfile::tempdir().unwrap();let root=temp.path().join("project");let other=temp.path().join("other");
        std::fs::create_dir(&root).unwrap();std::fs::create_dir(&other).unwrap();
        let program=temp.path().join("libcodex_sandbox.so");std::fs::write(&program,b"not executed").unwrap();
        std::os::unix::fs::symlink(&other,root.join("escape")).unwrap();
        let policy=AndroidCommandPolicy{version:1,project:root.display().to_string(),program:program.display().to_string()};
        assert!(android_command_paths(&policy,&root).is_ok());assert!(android_command_paths(&policy,&root.join("escape")).is_err());
    }
}
