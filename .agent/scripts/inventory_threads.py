#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["typer", "pydantic"]
# ///
"""
Advanced PR Review Manager.
- Inventory: Fetches and displays review threads with high token efficiency.
- Resolve: Replies and resolves threads, enforcing strict traceable closure rules.
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

# --- GraphQL Queries ---

QUERY_FETCH = """
query($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      id
      reviewThreads(first: 100) {
        nodes {
          id
          isResolved
          isOutdated
          path
          line
          comments(first: 1) {
            nodes {
              id
              body
              author { login }
            }
          }
        }
      }
    }
  }
}
"""

QUERY_REPLY = """
mutation($threadId: ID!, $body: String!) {
  addPullRequestReviewThreadReply(input: {pullRequestReviewThreadId: $threadId, body: $body}) {
    comment { id }
  }
}
"""

QUERY_RESOLVE = """
mutation($threadId: ID!) {
  resolveReviewThread(input: {threadId: $threadId}) {
    thread { isResolved }
  }
}
"""

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
    # Remove HTML comments (often used by automated tools for metadata)
    text = re.sub(r"<!--[\s\S]*?-->", "", text)
    # Remove images: ![alt](url) -> [IMG:alt]
    text = re.sub(r"!\[(.*?)\]\(.*?\)", r"[IMG:\1]", text)
    # Clean links: [text](url) -> text
    text = re.sub(r"\[(.*?)\]\(.*?\)", r"\1", text)
    # Remove simple HTML tags
    text = re.sub(r"<[^>]+>", "", text)
    # Compress whitespace
    text = re.sub(r"\s+", " ", text).strip()
    return text


def validate_reply(body: str):
    """Enforce rules: Reply must contain a Commit SHA or Issue ID."""
    # Commit hash (7+ hex chars) or Issue Ref (#123)
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
    """List actionable threads with agent guidance."""
    cache_file = Path(f"/tmp/pr-{pr_number}-threads.json")

    # 1. Fetch
    if cache_file.exists() and not refetch:
        data = json.loads(cache_file.read_text())
    else:
        try:
            owner, repo = get_repo_info()
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
                    f"query={QUERY_FETCH}",
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

        # System Reminder Injection
        print("<system_reminder>")
        print("1. CHECK: Does this require a new test case? Verify coverage.")
        print("2. STYLE: Is the fix idiomatic for this language?")
        print("3. RULE:  Follow TDD - Red, Green, Refactor.")
        print(
            "4. ACTION: If fixing, reply with 'Fixed in <SHA>'. If deferring, 'Created #<ISSUE>'."
        )
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
                f"query={QUERY_REPLY}",
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
                f"query={QUERY_RESOLVE}",
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
