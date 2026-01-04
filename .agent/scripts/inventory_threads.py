#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["typer", "pydantic"]
# ///
"""
Inventory PR review threads via GitHub CLI.
Caches results to /tmp/pr-{number}-threads.json.
Outputs a token-efficient summary for LLM agents.
"""

import json
import subprocess
from pathlib import Path
from typing import Optional

import typer
from pydantic import BaseModel

app = typer.Typer(add_completion=False)

GRAPHQL_QUERY = """
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


class Thread(BaseModel):
    thread_id: str
    comment_id: str
    path: str
    line: Optional[int]
    author: str
    body: str


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


def fetch_threads(owner: str, repo: str, pr_number: int) -> dict:
    """Fetch threads via gh api graphql."""
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
            f"query={GRAPHQL_QUERY}",
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    return json.loads(result.stdout)


def parse_threads(data: dict) -> tuple[str, list[Thread]]:
    """Parse GraphQL response into Thread objects."""
    pr = data.get("data", {}).get("repository", {}).get("pullRequest", {})
    pr_id = pr.get("id", "")
    nodes = pr.get("reviewThreads", {}).get("nodes", [])

    threads = []
    for n in nodes:
        if not n or n.get("isResolved") or n.get("isOutdated"):
            continue
        comments = n.get("comments", {}).get("nodes", [])
        if not comments:
            continue
        c = comments[0]
        threads.append(
            Thread(
                thread_id=n.get("id", ""),
                comment_id=c.get("id", ""),
                path=n.get("path") or "global",
                line=n.get("line"),
                author=(c.get("author") or {}).get("login", "ghost"),
                body=c.get("body", ""),
            )
        )
    return pr_id, threads


@app.command()
def main(
    pr_number: int = typer.Argument(..., help="PR number"),
    refetch: bool = typer.Option(False, "--refetch", "-r", help="Force refetch"),
    verbose: bool = typer.Option(False, "--verbose", "-v", help="Show full body"),
):
    """Inventory unresolved, non-outdated review threads for a PR."""
    cache_file = Path(f"/tmp/pr-{pr_number}-threads.json")

    if cache_file.exists() and not refetch:
        data = json.loads(cache_file.read_text())
    else:
        try:
            owner, repo = get_repo_info()
        except subprocess.CalledProcessError:
            typer.echo("Error: Failed to get repo info. Run from a git repo.", err=True)
            raise typer.Exit(1)
        try:
            data = fetch_threads(owner, repo, pr_number)
            cache_file.write_text(json.dumps(data, indent=2))
        except subprocess.CalledProcessError as e:
            typer.echo(f"Error fetching threads: {e.stderr}", err=True)
            raise typer.Exit(1)

    pr_id, threads = parse_threads(data)

    # Token-efficient output
    typer.echo(f"PR_ID={pr_id}")
    typer.echo(f"UNRESOLVED={len(threads)}")

    if not threads:
        typer.echo("NO_ACTIONABLE_THREADS")
        raise typer.Exit(0)

    for t in threads:
        loc = f"{t.path}:{t.line}" if t.line else t.path
        body = t.body.replace("\n", " ").strip()
        snippet = body if verbose else (body[:80] + "..." if len(body) > 80 else body)
        typer.echo("---")
        typer.echo(f"T={t.thread_id}")
        typer.echo(f"C={t.comment_id}")
        typer.echo(f"L={loc}")
        typer.echo(f"A={t.author}")
        typer.echo(f"B={snippet}")


if __name__ == "__main__":
    app()
