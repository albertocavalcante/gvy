// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

#[path = "../../contract.rs"]
mod contract;

fn language() -> tree_sitter::Language {
    groovy::language()
}

fn events(
    highlighter: &mut tree_sitter_highlight::Highlighter,
    config: &tree_sitter_highlight::HighlightConfiguration,
    source: &[u8],
) -> Result<(Vec<tree_sitter_highlight::HighlightEvent>, bool), tree_sitter_highlight::Error> {
    let mut unsupported = false;
    let events = highlighter
        .highlight(config, source, None, |_| {
            unsupported = true;
            None
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok((events, unsupported))
}

fn main() {
    contract::main("secondary", "0.26.8", language(), events);
}
