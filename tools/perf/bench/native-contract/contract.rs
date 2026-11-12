use serde_json::{Value, json};
use std::io::{self, Read};
use tree_sitter::{Language, Node, Parser, Query};
use tree_sitter_highlight::{HighlightConfiguration, HighlightEvent, Highlighter};

const VALID: &str = include_str!("fixtures/valid.groovy");
const MALFORMED: &str = include_str!("fixtures/malformed.groovy");
const MAX_INPUT_BYTES: usize = 64 * 1024;
const MAX_EVENTS: usize = 8192;
type Result<T> = std::result::Result<T, &'static str>;
type Events = fn(
    &mut Highlighter,
    &HighlightConfiguration,
    &[u8],
) -> std::result::Result<(Vec<HighlightEvent>, bool), tree_sitter_highlight::Error>;

#[derive(Debug, PartialEq)]
struct Span {
    start: usize,
    end: usize,
    category: Option<usize>,
}

fn validate_events(
    source: &str,
    events: Vec<HighlightEvent>,
    names: &[String],
) -> Result<Vec<Span>> {
    if events.len() > MAX_EVENTS {
        return Err("EVENT_LIMIT");
    }
    let mut end = 0;
    let mut stack = Vec::new();
    let mut spans = Vec::new();
    for event in events {
        match event {
            HighlightEvent::HighlightStart(index) if index.0 < names.len() => stack.push(index.0),
            HighlightEvent::HighlightStart(_) => return Err("HIGHLIGHT_INDEX"),
            HighlightEvent::HighlightEnd if stack.pop().is_none() => {
                return Err("HIGHLIGHT_BALANCE");
            }
            HighlightEvent::HighlightEnd => {}
            HighlightEvent::Source { start, end: next } => {
                if start != end
                    || next <= start
                    || next > source.len()
                    || !source.is_char_boundary(start)
                    || !source.is_char_boundary(next)
                {
                    return Err("SOURCE_COVERAGE");
                }
                spans.push(Span {
                    start,
                    end: next,
                    category: stack.last().copied(),
                });
                end = next;
            }
        }
    }
    if end != source.len() || !stack.is_empty() {
        return Err("INCOMPLETE_HIGHLIGHTS");
    }
    Ok(spans)
}

fn query(language: &Language, source: &str) -> Result<Query> {
    let query = Query::new(language, source).map_err(|_| "QUERY_INVALID")?;
    if query.pattern_count() > 512 {
        return Err("QUERY_LIMIT");
    }
    for index in 0..query.pattern_count() {
        if !query.general_predicates(index).is_empty()
            || query
                .property_predicates(index)
                .iter()
                .any(|(property, _)| property.key.as_ref() != "local")
            || query.property_settings(index).iter().any(|property| {
                !matches!(
                    property.key.as_ref(),
                    "injection.language"
                        | "injection.include-children"
                        | "injection.combined"
                        | "injection.self"
                        | "injection.parent"
                        | "local.scope-inherits"
                )
            })
        {
            return Err("QUERY_SEMANTICS_UNSUPPORTED");
        }
    }
    Ok(query)
}

fn issues(node: Node<'_>, result: &mut Vec<Value>) {
    if node.is_error() || node.is_missing() {
        result.push(json!({
            "kind": if node.is_missing() { "MISSING" } else { "ERROR" },
            "startByte": node.start_byte(), "endByte": node.end_byte()
        }));
    }
    let mut cursor = node.walk();
    for child in node.children(&mut cursor) {
        issues(child, result);
    }
}

fn expect_category(spans: &[Span], names: &[String], word: &str, expected: &str) -> Result<()> {
    let start = VALID.find(word).ok_or("ORACLE_INVALID")?;
    if !spans.iter().any(|span| {
        span.start == start
            && span.end == start + word.len()
            && span.category.map(|index| names[index].as_str()) == Some(expected)
    }) {
        return Err("PREDICATE_ORACLE_FAILED");
    }
    Ok(())
}

fn run(
    engine: &str,
    runtime: &str,
    language: Language,
    events: Events,
    input: &Value,
) -> Result<Value> {
    let fields = input.as_object().ok_or("INPUT_INVALID")?;
    if fields.len() != 3
        || fields
            .keys()
            .any(|key| !["highlights", "locals", "injections"].contains(&key.as_str()))
    {
        return Err("INPUT_INVALID");
    }
    let highlights = fields["highlights"].as_str().ok_or("INPUT_INVALID")?;
    let locals = fields["locals"].as_str().ok_or("INPUT_INVALID")?;
    let injections = fields["injections"].as_str().ok_or("INPUT_INVALID")?;
    let highlight_query = query(&language, highlights)?;
    let local_query = query(&language, locals)?;
    query(&language, injections)?;
    let unsupported_local_captures: Vec<_> = local_query
        .capture_names()
        .iter()
        .filter(|name| name.starts_with("local.definition."))
        .copied()
        .collect();
    if query(
        &language,
        "((identifier) @variable (#gvy-unsupported? @variable))",
    )
    .err()
        != Some("QUERY_SEMANTICS_UNSUPPORTED")
    {
        return Err("PREDICATE_REJECTION_FAILED");
    }
    let mut parser = Parser::new();
    parser
        .set_language(&language)
        .map_err(|_| "GRAMMAR_ABI_UNSUPPORTED")?;
    let valid = parser.parse(VALID, None).ok_or("PARSE_FAILED")?;
    if valid.root_node().has_error() {
        return Err("VALID_PARSE_FAILED");
    }
    let malformed = parser.parse(MALFORMED, None).ok_or("PARSE_FAILED")?;
    let mut parse_issues = Vec::new();
    issues(malformed.root_node(), &mut parse_issues);
    let delimiter = MALFORMED.find('(').ok_or("ORACLE_INVALID")?;
    if !malformed.root_node().has_error()
        || !parse_issues.iter().any(|issue| {
            let start = issue["startByte"].as_u64().unwrap_or(u64::MAX) as usize;
            let end = issue["endByte"].as_u64().unwrap_or(0) as usize;
            (start <= delimiter && delimiter < end)
                || (start == MALFORMED.trim_end().len() && end == start)
                || (start == MALFORMED.len() && end == start)
        })
    {
        return Err("MALFORMED_LOCATION_FAILED");
    }
    let names: Vec<String> = highlight_query
        .capture_names()
        .iter()
        .filter(|name| !name.starts_with('_') && !["spell", "nospell"].contains(name))
        .map(|name| (*name).to_owned())
        .collect();
    let mut config =
        HighlightConfiguration::new(language.clone(), "groovy", highlights, injections, locals)
            .map_err(|_| "HIGHLIGHT_CONFIG_FAILED")?;
    config.configure(&names);
    let mut highlighter = Highlighter::new();
    let (valid_events, injection_requested) =
        events(&mut highlighter, &config, VALID.as_bytes()).map_err(|_| "HIGHLIGHT_FAILED")?;
    if injection_requested {
        return Err("UNSUPPORTED_INJECTION");
    }
    let spans = validate_events(VALID, valid_events, &names)?;
    match engine {
        "primary" => {
            expect_category(&spans, &names, "int", "type.builtin")?;
            expect_category(&spans, &names, "String", "type")?;
        }
        "secondary" => {
            expect_category(&spans, &names, "MAX_VALUE", "constant")?;
            expect_category(&spans, &names, "Max_Value", "variable")?;
        }
        _ => return Err("ENGINE_INVALID"),
    }
    let (recovered_events, injection_requested) =
        events(&mut highlighter, &config, MALFORMED.as_bytes())
            .map_err(|_| "RECOVERED_HIGHLIGHT_FAILED")?;
    if injection_requested {
        return Err("UNSUPPORTED_INJECTION");
    }
    validate_events(MALFORMED, recovered_events, &names)?;
    let injection_source = if engine == "primary" {
        "/** doc */\nint n = 1\n"
    } else {
        "// note\nint n = 1\n"
    };
    let (injection_events, requested) =
        events(&mut highlighter, &config, injection_source.as_bytes())
            .map_err(|_| "INJECTION_CONTROL_FAILED")?;
    validate_events(injection_source, injection_events, &names)?;
    if !requested {
        return Err("INJECTION_REJECTION_FAILED");
    }
    let rendered: Vec<_> = spans
        .iter()
        .map(|span| {
            json!({
                "startByte": span.start, "endByte": span.end,
                "category": span.category.map(|index| names[index].as_str())
            })
        })
        .collect();
    Ok(json!({
        "schemaVersion": 1, "scope": "native-feasibility-only", "engine": engine,
        "runtimeVersion": runtime, "grammarAbi": language.abi_version(),
        "queries": "upstream-unchanged", "controlCount": 6,
        "fixtureBytes": { "valid": VALID.as_bytes(), "malformed": MALFORMED.as_bytes() },
        "controls": ["valid-parse", "malformed-error-location", "complete-highlight-event-stream", "regex-positive-negative", "unknown-predicate-rejected", "embedded-injection-explicitly-unsupported"],
        "validSpans": rendered, "malformedIssues": parse_issues,
        "limitations": {
            "unrecognizedLocalDefinitionCaptures": unsupported_local_captures,
            "upstreamLocalBindingParity": "not-established",
            "embeddedLanguages": "unsupported-rejected",
            "prebuiltTreeHighlighting": "unavailable-in-official-api",
            "incrementalHighlighting": "not-tested", "performance": "not-measured"
        }
    }))
}

pub fn main(engine: &str, runtime: &str, language: Language, events: Events) {
    let result = (|| {
        let mut bytes = Vec::new();
        io::stdin()
            .take((MAX_INPUT_BYTES + 1) as u64)
            .read_to_end(&mut bytes)
            .map_err(|_| "INPUT_INVALID")?;
        if bytes.len() > MAX_INPUT_BYTES {
            return Err("INPUT_LIMIT");
        }
        let input: Value = serde_json::from_slice(&bytes).map_err(|_| "INPUT_INVALID")?;
        run(engine, runtime, language, events, &input)
    })();
    match result {
        Ok(report) => println!("{report}"),
        Err(code) => {
            eprintln!("NATIVE_CONTRACT_FAILED:{code}");
            std::process::exit(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tree_sitter_highlight::Highlight;

    #[test]
    fn incomplete_and_invalid_event_streams_are_rejected() {
        let names = vec!["variable".to_owned()];
        assert_eq!(
            validate_events("x", vec![], &names),
            Err("INCOMPLETE_HIGHLIGHTS")
        );
        assert_eq!(
            validate_events("x", vec![HighlightEvent::HighlightEnd], &names),
            Err("HIGHLIGHT_BALANCE")
        );
        assert_eq!(
            validate_events(
                "x",
                vec![HighlightEvent::HighlightStart(Highlight(1))],
                &names
            ),
            Err("HIGHLIGHT_INDEX")
        );
        assert_eq!(
            validate_events(
                "🙂",
                vec![HighlightEvent::Source { start: 0, end: 1 }],
                &names
            ),
            Err("SOURCE_COVERAGE")
        );
        assert_eq!(
            validate_events(
                "xy",
                vec![HighlightEvent::Source { start: 1, end: 2 }],
                &names
            ),
            Err("SOURCE_COVERAGE")
        );
    }

    #[test]
    fn balanced_stream_covers_utf8_bytes_and_keeps_exact_category() {
        let names = vec!["string".to_owned()];
        assert_eq!(
            validate_events(
                "🙂",
                vec![
                    HighlightEvent::HighlightStart(Highlight(0)),
                    HighlightEvent::Source { start: 0, end: 4 },
                    HighlightEvent::HighlightEnd
                ],
                &names
            ),
            Ok(vec![Span {
                start: 0,
                end: 4,
                category: Some(0)
            }])
        );
    }

    #[test]
    fn unimplemented_predicate_is_rejected_before_highlighting() {
        assert_eq!(
            query(
                &crate::language(),
                "((identifier) @variable (#gvy-unsupported? @variable))"
            )
            .err(),
            Some("QUERY_SEMANTICS_UNSUPPORTED")
        );
    }
}
