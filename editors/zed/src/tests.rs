use super::*;
use serde_json::json;

#[test]
fn preserves_arguments_and_server_values() {
    let settings = parse_settings(json!({"launch": {"java_path": "/Java 17/bin/java", "jar_path": "/path with space/你好.jar", "jvm_args": ["-Dfoo=a b"]}, "server": {"groovy.foo": {"nested": [1, true]}}})).unwrap();
    let command = launch_command(&settings.launch, None, None).unwrap();
    assert_eq!(
        command,
        (
            "/Java 17/bin/java".to_owned(),
            vec!["-Dfoo=a b", "-jar", "/path with space/你好.jar"]
                .into_iter()
                .map(String::from)
                .collect()
        )
    );
    assert_eq!(
        settings.server,
        json!({"groovy.foo": {"nested": [1, true]}})
    );
}

#[test]
fn rejects_invalid_settings_and_explicit_java_without_fallback() {
    for value in [
        json!({}),
        json!({"launch": {"jar_path": "relative.jar"}}),
        json!({"launch": {"jar_path": "/a.jar", "jvm_args": "oops"}}),
        json!({"launch": {"jar_path": "/a.jar", "java_path": "java"}}),
        json!({"launch": {"jar_path": "/a.jar"}, "server": []}),
        json!({"launch": {"jar_path": "/a.jar"}, "server": {"typo": true}}),
        json!({"launch": {"jar_path": "/a.jar", "jvm_args": ["bad\0arg"]}}),
    ] {
        assert!(parse_settings(value).is_err());
    }
    let settings =
        parse_settings(json!({"launch": {"jar_path": "/a.jar", "java_path": "/bad/java"}}))
            .unwrap();
    assert_eq!(
        launch_command(&settings.launch, Some("/jdk"), Some("/bin/java"))
            .unwrap()
            .0,
        "/bad/java"
    );
}

#[test]
fn uses_worktree_java_home_then_path_and_default_heap() {
    let settings = parse_settings(json!({"launch": {"jar_path": "/a.jar"}})).unwrap();
    assert_eq!(
        launch_command(
            &settings.launch,
            Some("/jdk with space"),
            Some("/usr/bin/java")
        )
        .unwrap(),
        (
            "/jdk with space/bin/java".into(),
            vec!["-Xmx2g".into(), "-jar".into(), "/a.jar".into()]
        )
    );
    assert_eq!(
        launch_command(&settings.launch, None, Some("/usr/bin/java"))
            .unwrap()
            .0,
        "/usr/bin/java"
    );
    assert!(launch_command(&settings.launch, None, None).is_err());
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

#[test]
fn rejects_unknown_fields_bad_server_and_nul_values() {
    for value in [
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
fn explicit_java_wins_and_invalid_java_home_does_not_fallback() {
    let explicit =
        parse_settings(json!({"launch": {"jar_path": "/a.jar", "java_path": "/bad/java"}}))
            .unwrap();
    assert_eq!(
        launch_command(&explicit.launch, Some("relative"), Some("/bin/java"))
            .unwrap()
            .0,
        "/bad/java"
    );
    let implicit = parse_settings(json!({"launch": {"jar_path": "/a.jar"}})).unwrap();
    assert!(launch_command(&implicit.launch, Some("relative"), Some("/bin/java")).is_err());
    assert!(launch_command(&implicit.launch, Some("/bad\0home"), Some("/bin/java")).is_err());
}
