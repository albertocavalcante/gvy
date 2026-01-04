#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["typer", "pydantic"]
# ///
"""
Advanced PR Review Manager.
- Inventory: Fetches and displays review threads with high token efficiency.
- Resolve: Replies and resolves threads, enforcing strict traceable closure rules.
- Context-Aware: Injects workflow guidelines and loads external GraphQL queries.
"""

import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Optional

import typer
from pydantic import BaseModel

app = typer.Typer(help="PR Review Management CLI", add_completion=False)

# --- Configuration ---

AGENT_DIR = Path(__file__).resolve().parent.parent
QUERIES_DIR = AGENT_DIR / "queries"
WORKFLOWS_DIR = AGENT_DIR / "workflows"


def load_query(name: str) -> str:
    """Load GraphQL query from .agent/queries/."""
    query_file = QUERIES_DIR / name
    if not query_file.exists():
        typer.echo(f"Error: Query file {query_file} not found.", err=True)
        raise typer.Exit(1)
    return query_file.read_text()


def load_workflow_context(name: str, sections: list[str]) -> str:
    """Extract specific sections from a markdown workflow file."""
    workflow_file = WORKFLOWS_DIR / name
    if not workflow_file.exists():
        return ""

    content = workflow_file.read_text()
    extracted = []

    for section in sections:
        # Simple regex to find content between tags or headers
        # 1. XML-style tags <tag>...</tag>
        xml_match = re.search(f"<{section}>(.*?)</{section}>", content, re.DOTALL)
        if xml_match:
            extracted.append(f"<{section}>\n{xml_match.group(1).strip()}\n</{section}>")
            continue

        # 2. Markdown headers # Section ... (until next same-level header)
        # normalize section name for header search
        header_match = re.search(
            f"(?m)^#+ {re.escape(section)}.*$(.*?)(?=>^#+ |\\Z)", content, re.DOTALL
        )
        if header_match:
            extracted.append(f"### {section}\n{header_match.group(1).strip()}")

    return "\n\n".join(extracted)


# --- Data Models ---


class Thread(BaseModel):
    thread_id: str
    comment_id: str
    path: str
    line: Optional[int]
    author: str
    body: str


# --- Helper Functions ---


def get_repo_info() -> tuple[str, str]:
    """Get owner/repo from gh CLI."""
    result = subprocess.run(
        ["gh", "repo", "view", "--json", "owner,name"],
        capture_output=True,
        text=True,
        check=True,
    )
    data = json.loads(result.stdout)
    return data["owner"]["login"], data["name"]


def clean_body(text: str) -> str:
    """Aggressively clean text to save tokens."""
    # Remove HTML comments
    text = re.sub(r"<!--[\s\S]*?-->", "", text)
    # Remove images: ![alt](url) -> [IMG:alt]
    text = re.sub(r"!\[(.*?)\]\(.*?\)", r"[IMG:\1]", text)
    # Remove linked images: [![alt](url)](url) -> [IMG:alt]
    text = re.sub(r"\[!\[(.*?)\]\(.*?\)\]\(.*?\)", r"[IMG:\1]", text)
    # Clean links: [text](url) -> text
    text = re.sub(r"\[(.*?)\]\(.*?\)", r"\1", text)
    # Remove simple HTML tags
    text = re.sub(r"<[^>]+>", "", text)
    # Compress whitespace
    text = re.sub(r"\s+", " ", text).strip()
    return text


def validate_reply(body: str):
    """Enforce rules: Reply must contain a Commit SHA or Issue ID."""
    has_commit = re.search(r"\b[0-9a-f]{7,40}\b", body)
    has_issue = re.search(r"#\d+", body)

    if not (has_commit or has_issue):
        typer.echo(
            "Error: Reply MUST contain a Commit SHA (e.g. 91a4699) or Issue Ref (e.g. #123) to verify action.",
            err=True,
        )
        typer.echo(f"Body provided: {body}", err=True)
        raise typer.Exit(1)


# --- Commands ---


@app.command()
def inventory(
    pr_number: int = typer.Argument(..., help="PR number"),
    author: Optional[str] = typer.Option(
        None, "--author", "-a", help="Filter by author"
    ),
    refetch: bool = typer.Option(False, "--refetch", "-r", help="Force refetch"),
    verbose: bool = typer.Option(False, "--verbose", "-v", help="Show full content"),
):
    """List actionable threads with injected workflow guidance."""
    cache_file = Path(f"/tmp/pr-{pr_number}-threads.json")

    # 1. Fetch
    if cache_file.exists() and not refetch:
        data = json.loads(cache_file.read_text())
    else:
        try:
            owner, repo = get_repo_info()
            query = load_query("pr-review-threads.graphql")
            result = subprocess.run(
                [
                    "gh",
                    "api",
                    "graphql",
                    "-F",
                    f"owner={owner}",
                    "-F",
                    f"name={repo}",
                    "-F",
                    f"number={pr_number}",
                    "-f",
                    f"query={query}",
                ],
                capture_output=True,
                text=True,
                check=True,
            )
            data = json.loads(result.stdout)
            cache_file.write_text(json.dumps(data, indent=2))
        except subprocess.CalledProcessError as e:
            typer.echo(f"Error fetching: {e.stderr}", err=True)
            raise typer.Exit(1)

    # 2. Parse & Filter
    nodes = (
        data.get("data", {})
        .get("repository", {})
        .get("pullRequest", {})
        .get("reviewThreads", {})
        .get("nodes", [])
    )
    threads = []

    for n in nodes:
        if not n or n.get("isResolved") or n.get("isOutdated"):
            continue

        c_node = n.get("comments", {}).get("nodes", [])
        if not c_node:
            continue
        c = c_node[0]

        t_author = (c.get("author") or {}).get("login", "ghost")
        if author and t_author.lower() != author.lower():
            continue

        threads.append(
            Thread(
                thread_id=n.get("id"),
                comment_id=c.get("id"),
                path=n.get("path") or "global",
                line=n.get("line"),
                author=t_author,
                body=c.get("body", ""),
            )
        )

    # 3. Output
    typer.echo(f"PR={pr_number} UNRESOLVED={len(threads)}")

    for t in threads:
        clean_msg = clean_body(t.body)
        if not verbose and len(clean_msg) > 100:
            clean_msg = clean_msg[:97] + "..."

        loc = f"{t.path}:{t.line}" if t.line else t.path

        print("-" * 60)
        print(f"THREAD: {t.thread_id} | AUTHOR: {t.author}")
        print(f"LOC:    {loc}")
        print(f"MSG:    {clean_msg}")

    # 4. Inject System Reminder (Lazy Fetch)
    print("\n<system_reminder>")

    # Review Guidelines
    review_context = load_workflow_context(
        "review.md",
        [
            "ironclad_rules",
            "reply_templates",
            'decision_tree id="thread-classification"',
        ],
    )
    if review_context:
        print("=== REVIEW GUIDELINES ===")
        print(review_context)

    # Deferral Guidelines
    defer_context = load_workflow_context(
        "defer.md", ["How It Works", "Step 1: Create Issue"]
    )
    if defer_context:
        print("\n=== DEFERRAL GUIDELINES ===")
        print(defer_context)

    print("</system_reminder>")


@app.command()
def resolve(
    thread_id: str = typer.Argument(..., help="GraphQL Thread ID"),
    reply: str = typer.Argument(
        ..., help="Reply message (Must contain SHA or Issue #)"
    ),
):
    """Reply to and resolve a thread. Enforces traceability."""
    validate_reply(reply)

    try:
        # Load queries
        query_reply = load_query("reply-to-thread.graphql")
        query_resolve = load_query("resolve-review-thread.graphql")

        # 1. Reply
        subprocess.run(
            [
                "gh",
                "api",
                "graphql",
                "-F",
                f"threadId={thread_id}",
                "-F",
                f"body={reply}",
                "-f",
                f"query={query_reply}",
            ],
            check=True,
            capture_output=True,
        )

        # 2. Resolve
        subprocess.run(
            [
                "gh",
                "api",
                "graphql",
                "-F",
                f"threadId={thread_id}",
                "-f",
                f"query={query_resolve}",
            ],
            check=True,
            capture_output=True,
        )

        print(f"✅ Resolved thread {thread_id}")

    except subprocess.CalledProcessError as e:
        print(f"❌ Error: {e.stderr}", file=sys.stderr)
        raise typer.Exit(1)


if __name__ == "__main__":
    app()
