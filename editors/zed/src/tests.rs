use super::*;
use serde_json::json;

#[test]
fn preserves_arguments_and_server_values() {
    let settings = parse_settings(json!({"launch": {"java_path": "/Java 17/bin/java", "jar_path": "/path with space/你好.jar", "jvm_args": ["-Dfoo=a b"]}, "server": {"groovy.foo": {"nested": [1, true]}}})).unwrap();
    let command = launch_command(&settings.launch, None, |path| Some(path.into())).unwrap();
    assert_eq!(
        command,
        (
            "/Java 17/bin/java".into(),
            vec![
                "-Dfoo=a b".into(),
                "-jar".into(),
                "/path with space/你好.jar".into()
            ]
        )
    );
    assert_eq!(
        settings.server,
        json!({"groovy.foo": {"nested": [1, true]}})
    );
}

#[test]
fn java_preflight_obeys_precedence_without_fallback() {
    let explicit =
        parse_settings(json!({"launch": {"jar_path": "/a.jar", "java_path": "/missing java"}}))
            .unwrap();
    let missing = |path: &str| {
        if path == "/bin/java" || path == "java" {
            Some("/bin/java".into())
        } else {
            None
        }
    };
    let err = launch_command(&explicit.launch, Some("/jdk"), missing).unwrap_err();
    assert!(err.contains("java_path"));
    let implicit = parse_settings(json!({"launch": {"jar_path": "/a.jar"}})).unwrap();
    let err = launch_command(&implicit.launch, Some("/missing jdk"), missing).unwrap_err();
    assert!(err.contains("JAVA_HOME"));
    let err = launch_command(&implicit.launch, Some("relative"), missing).unwrap_err();
    assert!(err.contains("JAVA_HOME"));
    assert!(!err.contains("launch.JAVA_HOME"));
    assert!(launch_command(&implicit.launch, Some("/bad\0home"), missing).is_err());
    assert!(launch_command(&implicit.launch, None, |_| None).is_err());
    assert_eq!(
        launch_command(&implicit.launch, None, missing).unwrap(),
        (
            "/bin/java".into(),
            vec!["-Xmx2g".into(), "-jar".into(), "/a.jar".into()]
        )
    );
    assert_eq!(
        launch_command(&implicit.launch, Some("/jdk 你好"), |path| {
            if path == "/jdk 你好/bin/java" {
                Some(path.into())
            } else {
                None
            }
        })
        .unwrap()
        .0,
        "/jdk 你好/bin/java"
    );
    assert_eq!(
        launch_command(&explicit.launch, Some("relative"), |path| Some(path.into()))
            .unwrap()
            .0,
        "/missing java"
    );
}

#[test]
fn rejects_invalid_settings() {
    for value in [
        json!({}),
        json!({"launch": {"jar_path": "relative.jar"}}),
        json!({"launch": {"jar_path": "/a.jar", "jvm_args": "oops"}}),
        json!({"launch": {"jar_path": "/a.jar", "java_path": "java"}}),
        json!({"launch": {"jar_path": "/a.jar"}, "typo": true}),
        json!({"launch": {"jar_path": "/a.jar", "jvm_arg": []}}),
        json!({"launch": {"jar_path": "/a.jar"}, "server": []}),
        json!({"launch": {"jar_path": "/a.jar"}, "server": {"typo": true}}),
        json!({"launch": {"jar_path": "/bad\0.jar"}}),
        json!({"launch": {"jar_path": "/a.jar", "java_path": "/bad\0java"}}),
        json!({"launch": {"jar_path": "/a.jar", "jvm_args": ["bad\0arg"]}}),
    ] {
        assert!(parse_settings(value).is_err());
    }
}

#[test]
fn rejects_raw_initialization_options_to_avoid_startup_reversion() {
    let settings = json!({"launch": {"jar_path": "/gvy.jar"}, "server": {"groovy.mode": "auto"}});
    assert!(parse_lsp_settings(Some(settings.clone()), None).is_ok());
    assert!(parse_lsp_settings(Some(settings.clone()), Some(Value::Null)).is_ok());
    let error = match parse_lsp_settings(Some(settings), Some(json!({"groovy.mode": "script"}))) {
        Ok(_) => panic!("raw options should be rejected"),
        Err(error) => error,
    };
    assert!(error.contains("initialization_options"));
    assert!(error.contains("settings.server"));
}
