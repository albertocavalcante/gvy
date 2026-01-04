#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["typer", "pydantic", "rich"]
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
from rich.console import Console
from rich.panel import Panel
from rich.markdown import Markdown

console = Console()
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


def rprint(*args, **kwargs):
    """Print using rich if in a TTY, otherwise use plain typer.echo."""
    if console.is_terminal:
        console.print(*args, **kwargs)
    else:
        # Typer echo isn't quite the same as print, but for LLM it's fine
        res = " ".join(str(a) for a in args)
        typer.echo(res)


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


def get_thread_inventory(
    pr_number: int, force_refetch: bool = False
) -> tuple[str, list]:
    """Fetch all open threads for a PR."""
    owner, repo = get_repo_info()
    cache_file = Path(f"/tmp/pr-{owner}-{repo}-{pr_number}-threads.json")

    if cache_file.exists() and not force_refetch:
        data = json.loads(cache_file.read_text())
    else:
        try:
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

    filtered_threads = []
    for n in nodes:
        if not n or n.get("isResolved") or n.get("isOutdated"):
            continue
        c_nodes = n.get("comments", {}).get("nodes", [])
        if not c_nodes:
            continue
        filtered_threads.append(n)
    return pr_id, filtered_threads


@app.command()
def threads(
    pr_number: int = typer.Argument(None, help="PR number (auto-detected if omitted)"),
    refetch: bool = typer.Option(False, "--refetch", help="Force refetch from GitHub"),
):
    """Inventory open threads and display summary."""
    if pr_number is None:
        pr_number = get_current_pr_number()
        if pr_number is None:
            rprint(
                "[bold red]Error: Could not detect PR. Provide PR number.[/bold red]"
            )
            raise typer.Exit(1)

    try:
        pr_id, threads = get_thread_inventory(pr_number, force_refetch=refetch)

        # Output summary for LLM parse
        typer.echo(f"PR={pr_number} PR_ID={pr_id} COUNT={len(threads)}")

        if not threads:
            rprint("\n✨ [bold green]All threads resolved![/bold green]")
            return

        for t in threads:
            t_id = t["id"]
            comments = t.get("comments", {}).get("nodes", [])
            first = comments[0] if comments else {}
            body = first.get("body", "No body")
            author = first.get("author", {}).get("login", "unknown")
            path = first.get("path", "unknown")
            line = first.get("line", "?")

            # Machine readable for agent
            typer.echo(f"\nT={t_id}")
            typer.echo(f"C={comments[0]['id'] if comments else '?'}")

            # Human readable rich panel
            if console.is_terminal:
                rprint(
                    Panel(
                        clean_body(body),
                        title=f"[bold yellow]Thread {t_id}[/bold yellow]",
                        subtitle=f"[bold cyan]@{author}[/bold cyan] at [bold white]{path}:{line}[/bold white]",
                        border_style="yellow",
                    )
                )
            else:
                typer.echo(f"@{author} L={path}:{line}")
                typer.echo(f">{body[:100]}...")

        # System Reminder
        reminder = """
<agent_rules>
ACTION: FIX|DEFER|REJECT
CODE: Use imports, avoid FQNs
COMMIT: Use multiple -m flags. Don't use multiline strings.
FIX: Make change, test, commit. Reply: 'Fixed in <SHA>.'
DEFER: Create issue via /defer. Reply: 'Created #<N>. Out of scope.'
REJECT: Reply with technical reasoning. Do NOT resolve.
RESOLVE: pr resolve <T> '<reply>'
REMINDER: Loop until all threads are resolved. Do not merge with open threads.
</agent_rules>
"""
        typer.echo(reminder)

    except Exception as e:
        rprint(f"[bold red]Error checking threads: {e}[/bold red]")
        raise typer.Exit(1)


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
            text=True,
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
            text=True,
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


def get_current_pr_number() -> Optional[int]:
    """Auto-detect PR number from current branch."""
    try:
        result = subprocess.run(
            ["gh", "pr", "view", "--json", "number"],
            capture_output=True,
            text=True,
            check=True,
        )
        data = json.loads(result.stdout)
        return data.get("number")
    except subprocess.CalledProcessError:
        return None


def get_pr_details(pr_number: int) -> dict:
    """Fetch PR title, body, commits, and linked issues."""
    result = subprocess.run(
        [
            "gh",
            "pr",
            "view",
            str(pr_number),
            "--json",
            "title,body,commits,headRefName,state,mergeable,closingIssuesReferences",
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    return json.loads(result.stdout)


def get_unresolved_threads(pr_number: int) -> int:
    """Check for open review threads."""
    try:
        # Re-use threads command logic but simplified for check
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
        nodes = (
            data.get("data", {})
            .get("repository", {})
            .get("pullRequest", {})
            .get("reviewThreads", {})
            .get("nodes", [])
        )
        count = 0
        for n in nodes:
            if n and not n.get("isResolved") and not n.get("isOutdated"):
                count += 1
        return count
    except Exception:
        return 0


def generate_merge_body(pr: dict, pr_number: int) -> tuple[str, list[str]]:
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

    # Add Fixes references for linked issues
    closing_issues = pr.get("closingIssuesReferences", [])
    closing_numbers = {i.get("number") for i in closing_issues if i.get("number")}

    if closing_issues:
        body_lines.append("")
        body_lines.append("## Fixes")
        body_lines.append("")
        for issue in closing_issues:
            issue_number = issue.get("number")
            if issue_number:
                body_lines.append(f"Fixes #{issue_number}")

    # Auto-detect "Related/Towards" issues from PR body
    pr_body = pr.get("body", "")
    # Matches: Relates to #123, Towards #123, See #123, Part of #123
    # Case insensitive, handles various separators
    related_pattern = r"(?:relates|towards|part of|connects to|see)\s+(?:to\s+)?#(\d+)"
    found_related = re.findall(related_pattern, pr_body, re.IGNORECASE)

    # Filter out issues that are already "Fixes"
    related_numbers = [n for n in found_related if int(n) not in closing_numbers]

    # Add unique valid related numbers
    unique_related = sorted(list(set(related_numbers)), key=lambda x: int(x))

    if unique_related:
        body_lines.append("")
        body_lines.append("## Related Issues")
        body_lines.append("")
        for num in unique_related:
            body_lines.append(f"Relates to #{num}")

    return "\n".join(body_lines), unique_related


def generate_ai_message(pr: dict, pr_number: int) -> tuple[str, str]:
    """Generate commit message using Gemini CLI."""
    typer.echo("🤖 Generating semantic commit message with Gemini...")

    # Create diff file to handle large diffs
    diff_file = Path(f"/tmp/pr-{pr_number}.diff")
    try:
        with open(diff_file, "w") as f:
            subprocess.run(["gh", "pr", "diff", str(pr_number)], stdout=f, check=True)
    except subprocess.CalledProcessError:
        return "", ""

    # Construct prompt
    prompt = f"""
You are an expert Release Engineer. Generate a strict Semantic Squash Commit Message.

Context:
PR #{pr_number}
Title: {pr.get("title")}
Description: {pr.get("body")}

Instructions:
1. OUTPUT ONLY the commit message. No conversational text.
2. Format:
   TITLE: <type>(<scope>): <description> (#{pr_number})
   BODY:
   - <bullet point>
   - <bullet point>
   - Use strict Conventional Commits (feat, fix, docs, style, refactor, test, chore, perf, ci).
   - Scope is optional but recommended (e.g. semantics, parser, lsp).
   - Description must be lower case, imperative mood.
   - Body should be a concise summary of the functional changes. Use bullet points.
   - Focus on "What" and "Why", not "How".
   - Do NOT include "Fixes" or "Relates to" footers (added automatically).


The user handles the Diff separately.
"""

    try:
        # We need to construct a combined input stream: Prompt + Diff
        # Since gemini reads stdin, we can cat them together roughly.
        # But wait, gemini CLI takes the prompt as argument usually or stdin.
        # If we pipe, we pipe the whole context.

        # Write prompt to temp file
        prompt_file = Path(f"/tmp/pr-{pr_number}-prompt.txt")
        prompt_file.write_text(prompt)

        # Concatenate prompt + diff -> gemini
        # cat prompt.txt diff.txt | gemini

        ps = subprocess.Popen(
            f"cat {prompt_file} {diff_file} | gemini",
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        stdout, stderr = ps.communicate()

        if ps.returncode != 0:
            typer.echo(f"⚠️ Gemini failed: {stderr}", err=True)
            return "", ""

        output = stdout.strip()

        # Cleanup
        if diff_file.exists():
            diff_file.unlink()
        if prompt_file.exists():
            prompt_file.unlink()

        # Parse output
        title_match = re.search(r"TITLE:\s*(.+)", output)
        body_match = re.search(r"BODY:\s*(.*)", output, re.DOTALL)

        ai_title = title_match.group(1).strip() if title_match else ""
        ai_body = body_match.group(1).strip() if body_match else ""

        # Fallback if parsing fails
        if not ai_title:
            lines = output.split("\n")
            ai_title = lines[0]
            ai_body = "\n".join(lines[1:])

        return ai_title, ai_body

    except Exception as e:
        typer.echo(f"⚠️ AI Generation failed: {e}", err=True)
        return "", ""


@app.command()
def merge(
    pr_number: int = typer.Argument(
        None, help="PR number to merge (auto-detects from current branch if omitted)"
    ),
    title: str = typer.Option(
        None, "--title", "-t", help="Override commit title (must be semantic)"
    ),
    dry_run: bool = typer.Option(
        False, "--dry-run", "-n", help="Preview without merging"
    ),
    relates_to: Optional[list[str]] = typer.Option(
        None, "--relates-to", "-R", help="Issue numbers this PR relates to (e.g. '622')"
    ),
    ai: bool = typer.Option(
        False, "--ai", "-a", help="Generate commit message using AI (gemini)"
    ),
    edit: bool = typer.Option(
        False, "--edit", "-e", help="Edit the commit message before finalization"
    ),
):
    """
    Squash merge a PR with enforced semantic commit message.

    Requirements:
    - Title: type(scope): description (#PR)
    - Types: feat, fix, docs, style, refactor, test, chore, perf, ci
    - PR number MUST be in title
    - Linked issues automatically get "Fixes #N" in body
    - Related issues can be added via --relates-to
    """
    # Auto-detect PR if not provided
    if pr_number is None:
        pr_number = get_current_pr_number()
        if pr_number is None:
            typer.echo(
                "Error: Could not detect PR from current branch. Provide PR number.",
                err=True,
            )
            raise typer.Exit(1)
        typer.echo(f"📌 Auto-detected PR #{pr_number}")

    try:
        pr = get_pr_details(pr_number)
    except subprocess.CalledProcessError as e:
        typer.echo(f"Error fetching PR: {e.stderr}", err=True)
        raise typer.Exit(1)

    # AI Generation
    ai_title = ""
    ai_body = ""
    if ai:
        ai_title, ai_body = generate_ai_message(pr, pr_number)
        if ai_title:
            typer.echo(f"\n🧠 AI Suggested Title: {ai_title}")
            # Use AI title if valid (or let validation fail it later)
            if not title:
                title = ai_title

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

    # Check for open threads
    open_threads = get_unresolved_threads(pr_number)
    if open_threads > 0:
        typer.echo(
            f"\n⚠️  WARNING: PR #{pr_number} has {open_threads} unresolved threads!",
            err=True,
        )
        if not typer.confirm(
            f"Are you SURE you want to merge with {open_threads} open threads?",
            default=False,
        ):
            typer.echo("Merge cancelled.")
            raise typer.Exit(0)

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
    if ai and ai_body:
        merge_body = ai_body
        # re-scan for related issues for deterministic linking
        _, auto_related = generate_merge_body(pr, pr_number)
    else:
        merge_body, auto_related = generate_merge_body(pr, pr_number)

    # Append Fixes if AI body doesn't have them
    linked_issues = pr.get("closingIssuesReferences", [])
    if linked_issues and "Fixes #" not in merge_body:
        if "\n\n## Fixes" not in merge_body:
            merge_body += "\n\n## Fixes\n"
        for issue in linked_issues:
            num = issue.get("number")
            if num:
                merge_body += f"\nFixes #{num}"
    # Append explicitly related issues (from CLI)
    if relates_to:
        # Check if we need to add header
        if not auto_related:
            if "\n\n## Related Issues" not in merge_body:
                merge_body += "\n\n## Related Issues\n"

        for issue in relates_to:
            clean_issue = issue.strip().lstrip("#")
            # Avoid duplicates if auto-detected
            if clean_issue not in auto_related:
                merge_body += f"\nRelates to #{clean_issue}"

    # Persist message for potential manual tweaks or re-runs
    msg_cache_file = Path(f"/tmp/merge-msg-{pr_number}.md")
    full_msg = f"{merge_title}\n\n{merge_body}"
    msg_cache_file.write_text(full_msg)

    if edit:
        edited_msg = typer.edit(full_msg, extension=".md")
        if edited_msg:
            # Re-split title and body
            lines = edited_msg.strip().split("\n", 2)
            merge_title = lines[0].strip()
            merge_body = lines[2].strip() if len(lines) > 2 else ""
            # Re-save
            msg_cache_file.write_text(edited_msg)

    # Show Preview
    if console.is_terminal:
        rprint(
            "\n[bold cyan]============================================================[/bold cyan]"
        )
        rprint("[bold cyan]MERGE PREVIEW (Squash)[/bold cyan]")
        rprint(
            "[bold cyan]============================================================[/bold cyan]\n"
        )

        rprint(
            Panel(
                merge_title,
                title="[bold white]📝 Semantic Title[/bold white]",
                border_style="green",
            )
        )
        rprint(
            Panel(
                Markdown(merge_body),
                title="[bold white]📋 Body[/bold white]",
                border_style="blue",
            )
        )
        rprint(
            "\n[bold cyan]============================================================[/bold cyan]\n"
        )
    else:
        typer.echo("\n" + "=" * 60)
        typer.echo("MERGE PREVIEW (Squash)")
        typer.echo("=" * 60)
        typer.echo(f"\n📝 Title:\n{merge_title}")
        typer.echo(f"\n📋 Body:\n{merge_body}")
        typer.echo("\n" + "=" * 60 + "\n")

    # Show linked issues if any
    linked_issues = pr.get("closingIssuesReferences", [])
    if linked_issues:
        issue_nums = [f"#{i.get('number')}" for i in linked_issues if i.get("number")]
        rprint(f"🔗 [bold green]Linked issues:[/bold green] {', '.join(issue_nums)}")

    if dry_run:
        print("\n[DRY RUN] Would merge with above message.")
        return

    # Confirmation
    confirm = typer.confirm("\n✨ Proceed with squash merge?", default=False)
    if not confirm:
        typer.echo("Merge cancelled.")
        raise typer.Exit(0)

    # Execute merge
    try:
        # Write body to temp file to strict handle newlines and size
        body_file = Path(f"/tmp/pr-{pr_number}-merge.md")
        body_file.write_text(merge_body)

        subprocess.run(
            [
                "gh",
                "pr",
                "merge",
                str(pr_number),
                "--squash",
                "--subject",
                merge_title,
                "--body-file",
                str(body_file),
            ],
            check=True,
        )
        print(f"\n✅ PR #{pr_number} merged successfully!")
        if body_file.exists():
            body_file.unlink()
    except subprocess.CalledProcessError as e:
        print(f"\n❌ Merge failed: {e}", file=sys.stderr)
        raise typer.Exit(1)


if __name__ == "__main__":
    app()
