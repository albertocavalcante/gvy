#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["typer", "pydantic"]
# ///
"""
PR Review Manager - Token-efficient review thread CLI.
All heavy lifting (GraphQL, caching, mutations) is handled here.
Agent only needs: thread_id, file, line, message.
"""

import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Optional

import typer
from pydantic import BaseModel

app = typer.Typer(help="PR Review CLI", add_completion=False)

AGENT_DIR = Path(__file__).resolve().parent.parent
QUERIES_DIR = AGENT_DIR / "queries"


def load_query(name: str) -> str:
    """Load GraphQL query from .agent/queries/."""
    query_file = QUERIES_DIR / name
    if not query_file.exists():
        typer.echo(f"Error: Query file {query_file} not found.", err=True)
        raise typer.Exit(1)
    return query_file.read_text()


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
    text = re.sub(r"<!--[\s\S]*?-->", "", text)  # HTML comments
    text = re.sub(r"\[!\[(.*?)\]\(.*?\)\]\(.*?\)", "", text)  # Linked images
    text = re.sub(r"!\[(.*?)\]\(.*?\)", "", text)  # Images
    text = re.sub(r"\[(.*?)\]\(.*?\)", r"\1", text)  # Links -> text only
    text = re.sub(r"<[^>]+>", "", text)  # HTML tags
    text = re.sub(r"```[\s\S]*?```", "[CODE]", text)  # Code blocks -> [CODE]
    text = re.sub(r"`[^`]+`", "[code]", text)  # Inline code -> [code]
    text = re.sub(r"\s+", " ", text).strip()  # Whitespace
    return text


def validate_reply(body: str):
    """Reply must contain Commit SHA or Issue Ref."""
    if not (re.search(r"\b[0-9a-f]{7,40}\b", body) or re.search(r"#\d+", body)):
        typer.echo(
            "Error: Reply MUST contain SHA (e.g. 91a4699) or Issue (e.g. #123).",
            err=True,
        )
        raise typer.Exit(1)


class Thread(BaseModel):
    thread_id: str
    comment_id: str
    path: str
    line: Optional[int]
    author: str
    body: str


@app.command()
def threads(
    pr_number: int = typer.Argument(..., help="PR number"),
    author: Optional[str] = typer.Option(
        None, "--author", "-a", help="Filter by author"
    ),
    refetch: bool = typer.Option(False, "--refetch", "-r", help="Force refetch"),
):
    """List unresolved threads. Output optimized for LLM consumption."""
    cache_file = Path(f"/tmp/pr-{pr_number}-threads.json")

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
            typer.echo(f"Error: {e.stderr}", err=True)
            raise typer.Exit(1)

    pr_id = (
        data.get("data", {}).get("repository", {}).get("pullRequest", {}).get("id", "")
    )
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
        c_nodes = n.get("comments", {}).get("nodes", [])
        if not c_nodes:
            continue
        c = c_nodes[0]
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

    # Ultra-compact output
    print(f"PR={pr_number} PR_ID={pr_id} COUNT={len(threads)}")

    for t in threads:
        msg = clean_body(t.body)
        if len(msg) > 120:
            msg = msg[:117] + "..."
        loc = f"{t.path}:{t.line}" if t.line else t.path
        print(f"\nT={t.thread_id}")
        print(f"C={t.comment_id}")
        print(f"@={t.author} L={loc}")
        print(f">{msg}")

    # Minimal agent guidance
    print("\n<agent_rules>")
    print("ACTION: FIX|DEFER|REJECT")
    print("CODE: Use imports, avoid FQNs")
    print(
        "COMMIT: Use multiple -m flags for multi-line messages. Avoid multiline strings."
    )
    print("FIX: Make change, test, commit. Reply: 'Fixed in <SHA>.'")
    print(
        "HOOK: If commit fails, READ the hook output. Fix lint/format errors before retry."
    )
    print("DEFER: Create issue via /defer. Reply: 'Created #<N>. Out of scope.'")
    print("REJECT: Reply with technical reasoning. Do NOT resolve.")
    print("RESOLVE: uv run .agent/scripts/pr.py resolve <T> '<reply>'")
    print("</agent_rules>")


@app.command()
def resolve(
    thread_id: str = typer.Argument(..., help="Thread ID (T=...)"),
    reply: str = typer.Argument(..., help="Reply (must contain SHA or #issue)"),
):
    """Reply and resolve a thread."""
    validate_reply(reply)

    try:
        q_reply = load_query("reply-to-thread.graphql")
        q_resolve = load_query("resolve-review-thread.graphql")

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
                f"query={q_reply}",
            ],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            [
                "gh",
                "api",
                "graphql",
                "-F",
                f"threadId={thread_id}",
                "-f",
                f"query={q_resolve}",
            ],
            check=True,
            capture_output=True,
        )
        print(f"✅ {thread_id}")
    except subprocess.CalledProcessError as e:
        print(f"❌ {e.stderr}", file=sys.stderr)
        raise typer.Exit(1)


# --- Merge Command ---

COMMIT_TYPES = [
    "feat",
    "fix",
    "docs",
    "style",
    "refactor",
    "test",
    "chore",
    "perf",
    "ci",
]


def validate_semantic_title(title: str, pr_number: int) -> tuple[bool, str]:
    """Validate title follows: type(scope): description (#PR)"""
    # Pattern: type(optional-scope): description (#number)
    pattern = rf"^({'|'.join(COMMIT_TYPES)})(\([a-z0-9-]+\))?: .+ \(#{pr_number}\)$"
    if not re.match(pattern, title, re.IGNORECASE):
        return False, (
            f"Title must match: type(scope): description (#{pr_number})\n"
            f"Types: {', '.join(COMMIT_TYPES)}\n"
            f"Example: feat(semantics): implement type inference (#{pr_number})"
        )
    return True, ""


def get_pr_details(pr_number: int) -> dict:
    """Fetch PR title, body, and commits."""
    result = subprocess.run(
        [
            "gh",
            "pr",
            "view",
            str(pr_number),
            "--json",
            "title,body,commits,headRefName,state,mergeable",
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    return json.loads(result.stdout)


def generate_merge_body(pr: dict, pr_number: int) -> str:
    """Generate a beautiful merge commit body."""
    commits = pr.get("commits", [])
    commit_msgs = [
        c.get("messageHeadline", "") for c in commits if c.get("messageHeadline")
    ]

    body_lines = [
        f"PR #{pr_number}: {pr.get('title', 'No title')}",
        "",
        "## Changes",
        "",
    ]

    # Group commits by type
    for msg in commit_msgs[:10]:  # Limit to 10 commits
        body_lines.append(f"- {msg}")

    if len(commits) > 10:
        body_lines.append(f"- ... and {len(commits) - 10} more commits")

    return "\n".join(body_lines)


@app.command()
def merge(
    pr_number: int = typer.Argument(..., help="PR number to merge"),
    title: str = typer.Option(
        None, "--title", "-t", help="Override commit title (must be semantic)"
    ),
    dry_run: bool = typer.Option(
        False, "--dry-run", "-n", help="Preview without merging"
    ),
):
    """
    Squash merge a PR with enforced semantic commit message.

    Requirements:
    - Title: type(scope): description (#PR)
    - Types: feat, fix, docs, style, refactor, test, chore, perf, ci
    - PR number MUST be in title
    """
    try:
        pr = get_pr_details(pr_number)
    except subprocess.CalledProcessError as e:
        typer.echo(f"Error fetching PR: {e.stderr}", err=True)
        raise typer.Exit(1)

    if pr.get("state") != "OPEN":
        typer.echo(
            f"Error: PR #{pr_number} is not open (state: {pr.get('state')})", err=True
        )
        raise typer.Exit(1)

    if pr.get("mergeable") == "CONFLICTING":
        typer.echo(
            f"Error: PR #{pr_number} has conflicts. Resolve before merging.", err=True
        )
        raise typer.Exit(1)

    # Generate or validate title
    if title:
        merge_title = title
    else:
        # Try to make existing title semantic
        existing = pr.get("title", "")
        if f"(#{pr_number})" not in existing:
            merge_title = f"{existing} (#{pr_number})"
        else:
            merge_title = existing

    # Validate title
    valid, error = validate_semantic_title(merge_title, pr_number)
    if not valid:
        typer.echo(f"\n❌ Invalid commit title:\n{error}", err=True)
        typer.echo(f"\nCurrent title: {merge_title}", err=True)
        typer.echo("\nUse --title to provide a valid semantic title.", err=True)
        raise typer.Exit(1)

    # Generate body
    merge_body = generate_merge_body(pr, pr_number)

    # Preview
    print("\n" + "=" * 60)
    print("MERGE PREVIEW (Squash)")
    print("=" * 60)
    print(f"\n📝 Title:\n{merge_title}")
    print(f"\n📋 Body:\n{merge_body}")
    print("\n" + "=" * 60)

    if dry_run:
        print("\n[DRY RUN] Would merge with above message.")
        return

    # Confirmation
    confirm = typer.confirm("\n🚀 Proceed with squash merge?", default=False)
    if not confirm:
        typer.echo("Merge cancelled.")
        raise typer.Exit(0)

    # Execute merge
    try:
        subprocess.run(
            [
                "gh",
                "pr",
                "merge",
                str(pr_number),
                "--squash",
                "--subject",
                merge_title,
                "--body",
                merge_body,
            ],
            check=True,
        )
        print(f"\n✅ PR #{pr_number} merged successfully!")
    except subprocess.CalledProcessError as e:
        print(f"\n❌ Merge failed: {e}", file=sys.stderr)
        raise typer.Exit(1)


if __name__ == "__main__":
    app()
