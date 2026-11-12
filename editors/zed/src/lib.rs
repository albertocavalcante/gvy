use serde::Deserialize;
use serde_json::{json, Value};
use zed_extension_api::{
    self as zed, settings::LspSettings, Extension, LanguageServerId, Worktree,
};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Settings {
    launch: Launch,
    #[serde(default = "empty_object")]
    server: Value,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Launch {
    jar_path: String,
    java_path: Option<String>,
    jvm_args: Option<Vec<String>>,
}
fn empty_object() -> Value {
    json!({})
}
fn parse_settings(value: Value) -> Result<Settings, String> {
    let settings: Settings =
        serde_json::from_value(value).map_err(|e| format!("Invalid lsp.gvy.settings: {e}"))?;
    let server = settings
        .server
        .as_object()
        .ok_or("lsp.gvy.settings.server must be an object")?;
    if server.keys().any(|key| !key.starts_with("groovy.")) {
        return Err("lsp.gvy.settings.server keys must start with groovy.".into());
    }
    validate_path("jar_path", &settings.launch.jar_path)?;
    if let Some(path) = &settings.launch.java_path {
        validate_path("java_path", path)?;
    }
    if settings
        .launch
        .jvm_args
        .as_ref()
        .is_some_and(|args| args.iter().any(|arg| arg.contains('\0')))
    {
        return Err("lsp.gvy.settings.launch.jvm_args cannot contain NUL".into());
    }
    Ok(settings)
}
fn validate_path(name: &str, path: &str) -> Result<(), String> {
    // The initial experiment targets macOS; WASM cannot inspect arbitrary host paths.
    if !path.starts_with('/') || path.contains('\0') {
        return Err(format!(
            "lsp.gvy.settings.launch.{name} must be an absolute host path without NUL"
        ));
    }
    Ok(())
}
fn launch_command(
    launch: &Launch,
    java_home: Option<&str>,
    path_java: Option<&str>,
) -> Result<(String, Vec<String>), String> {
    let java = if let Some(path) = &launch.java_path {
        path.clone()
    } else if let Some(home) = java_home.filter(|home| !home.is_empty()) {
        validate_path("JAVA_HOME", home)?;
        format!("{}/bin/java", home.trim_end_matches('/'))
    } else {
        path_java.ok_or("Java not found: set lsp.gvy.settings.launch.java_path or JAVA_HOME, or add Java to the worktree PATH")?.to_owned()
    };
    let mut args = launch
        .jvm_args
        .clone()
        .unwrap_or_else(|| vec!["-Xmx2g".into()]);
    args.extend(["-jar".into(), launch.jar_path.clone()]);
    Ok((java, args))
}
struct Gvy;
fn settings(_id: &LanguageServerId, worktree: &Worktree) -> Result<Settings, String> {
    let settings = LspSettings::for_worktree("gvy", worktree)?;
    parse_lsp_settings(settings.settings, settings.initialization_options)
}

fn parse_lsp_settings(
    settings: Option<Value>,
    initialization_options: Option<Value>,
) -> Result<Settings, String> {
    if initialization_options.is_some_and(|value| !value.is_null()) {
        return Err("lsp.gvy.initialization_options is unsupported; use lsp.gvy.settings.server for startup and live server configuration".into());
    }
    parse_settings(
        settings.ok_or("Set lsp.gvy.settings.launch.jar_path to an absolute local JAR path")?,
    )
}
impl Extension for Gvy {
    fn new() -> Self {
        Self
    }
    fn language_server_command(
        &mut self,
        id: &LanguageServerId,
        worktree: &Worktree,
    ) -> zed::Result<zed::Command> {
        let settings = settings(id, worktree)?;
        let env = worktree.shell_env();
        let java_home = env
            .iter()
            .find(|(key, _)| key == "JAVA_HOME")
            .map(|(_, value)| value.as_str());
        let path_java = worktree.which("java");
        let (command, args) = launch_command(&settings.launch, java_home, path_java.as_deref())?;
        Ok(zed::Command {
            command,
            args,
            env: vec![],
        })
    }
    fn language_server_initialization_options(
        &mut self,
        id: &LanguageServerId,
        worktree: &Worktree,
    ) -> zed::Result<Option<Value>> {
        Ok(Some(settings(id, worktree)?.server))
    }
    fn language_server_workspace_configuration(
        &mut self,
        id: &LanguageServerId,
        worktree: &Worktree,
    ) -> zed::Result<Option<Value>> {
        Ok(Some(settings(id, worktree)?.server))
    }
}
zed::register_extension!(Gvy);
#[cfg(test)]
mod tests;
