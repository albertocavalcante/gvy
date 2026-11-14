// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

use serde_json::json;
use std::{
    env, fs,
    hint::black_box,
    process,
    time::{Duration, Instant},
};
use tree_sitter::{Node, Parser, Tree};

fn parser() -> Parser {
    let mut parser = Parser::new();
    parser
        .set_language(&groovy::LANGUAGE.into())
        .expect("primary grammar");
    parser
}

fn checked_tree(parser: &mut Parser, bytes: &[u8]) -> Tree {
    let tree = parser.parse(bytes, None).expect("parse completed");
    assert_eq!(tree.root_node().end_byte(), bytes.len(), "incomplete parse");
    tree
}

fn check_nodes(node: Node<'_>) {
    assert!(
        !node.is_error() && !node.is_missing(),
        "invalid node: {}",
        node.kind()
    );
    let mut cursor = node.walk();
    for child in node.children(&mut cursor) {
        check_nodes(child);
    }
}

fn contains_kind(node: Node<'_>, kind: &str) -> bool {
    if node.kind() == kind {
        return true;
    }
    let mut cursor = node.walk();
    let found = node
        .children(&mut cursor)
        .any(|child| contains_kind(child, kind));
    found
}

fn controls(parser: &mut Parser, bytes: &[u8], input_id: &str) {
    let first = checked_tree(parser, bytes);
    check_nodes(first.root_node());
    assert!(contains_kind(first.root_node(), "class_declaration"));
    let name = match input_id {
        "sample" => "Sample",
        "cli-builder-exception" => "CliBuilderException",
        _ => panic!("unknown input id"),
    };
    let start = bytes
        .windows(name.len())
        .position(|window| window == name.as_bytes())
        .expect("expected class name");
    let identifier = first
        .root_node()
        .descendant_for_byte_range(start, start + name.len())
        .expect("class name node");
    assert_eq!(
        (identifier.start_byte(), identifier.end_byte()),
        (start, start + name.len())
    );
    // A/B/A uses valid, equal-length identifiers and proves the parser does not
    // reuse an earlier tree when the source contents change.
    let a = b"class Alpha {}";
    let b = b"class Bravo {}";
    let tree_a = checked_tree(parser, a);
    check_nodes(tree_a.root_node());
    let node_a = tree_a
        .root_node()
        .descendant_for_byte_range(6, 11)
        .expect("identifier");
    assert_eq!(node_a.utf8_text(a).expect("UTF-8"), "Alpha");
    let first_a = tree_a.root_node().to_sexp();
    let tree_b = checked_tree(parser, b);
    check_nodes(tree_b.root_node());
    let second_a = checked_tree(parser, a).root_node().to_sexp();
    assert_eq!(first_a, second_a);
    let node_b = tree_b
        .root_node()
        .descendant_for_byte_range(6, 11)
        .expect("identifier");
    assert_eq!(node_b.utf8_text(b).expect("UTF-8"), "Bravo");
    let invalid = checked_tree(parser, b"class {");
    assert!(
        invalid.root_node().has_error(),
        "malformed control unexpectedly accepted"
    );
}

fn run_batch(parser: &mut Parser, bytes: &[u8], duration: Duration) -> (u128, u64) {
    let start = Instant::now();
    let mut completed = 0u64;
    loop {
        let tree = checked_tree(parser, black_box(bytes));
        black_box(tree.root_node().kind_id());
        drop(tree);
        completed = completed.checked_add(1).expect("operation count overflow");
        if start.elapsed() >= duration {
            break;
        }
    }
    (start.elapsed().as_nanos(), completed)
}

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.len() != 10
        || args[0] != "--input"
        || args[2] != "--input-id"
        || args[4] != "--warmup"
        || args[6] != "--iterations"
        || args[8] != "--iteration-ns"
    {
        eprintln!(
            "usage: native-parse --input PATH --input-id ID --warmup N --iterations N --iteration-ns N"
        );
        process::exit(2);
    }
    let warmup: usize = args[5].parse().expect("warmup iterations");
    let iterations: usize = args[7].parse().expect("measurement iterations");
    let nanos: u64 = args[9].parse().expect("iteration nanoseconds");
    assert!(warmup <= 30 && (1..=10).contains(&iterations) && (1..=1_000_000_000).contains(&nanos));
    assert!(!args[3].is_empty() && args[3].len() <= 128);
    let bytes = fs::read(&args[1]).expect("input bytes");
    assert!(!bytes.is_empty() && bytes.len() <= 1024 * 1024);
    let mut parser = parser();
    controls(&mut parser, &bytes, &args[3]);
    let duration = Duration::from_nanos(nanos);
    for _ in 0..warmup {
        black_box(run_batch(&mut parser, &bytes, duration));
    }
    let samples: Vec<_> = (0..iterations).map(|_| {
        let (elapsed_ns, operations) = run_batch(&mut parser, &bytes, duration);
        json!({"elapsedNs": elapsed_ns, "operations": operations, "completedOperations": operations})
    }).collect();
    println!(
        "{}",
        json!({"schemaVersion": 1, "engine": "tree-sitter-primary", "inputId": args[3], "sourceByteLength": bytes.len(), "processId": process::id(), "runtime": {"treeSitterVersion": "0.27.2", "grammarRevision": "be0e3922cfcea075f37bdc1abd5463b953c5cd68"}, "warmupIterations": warmup, "measurementIterations": iterations, "iterationTimeNs": nanos, "samples": samples, "completedWarmupIterations": warmup})
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn controls_accept_valid_and_reject_invalid_source() {
        let mut p = parser();
        controls(
            &mut p,
            b"class Sample { int alpha() { return 17 } }",
            "sample",
        );
        assert!(
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| controls(
                &mut p, b"class {", "sample"
            )))
            .is_err()
        );
    }
}
