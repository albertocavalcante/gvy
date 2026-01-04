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
import shlex
import subprocess
import sys
import tempfile
import hashlib
from datetime import datetime
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


def get_pr_cache_dir(pr_number: int) -> Path:
    """Get a safe, multiplatform cache directory for a specific PR."""
    owner, repo = get_repo_info()
    # Use repo name in hash to avoid collisions across different repos for same PR#
    repo_hash = hashlib.md5(f"{owner}/{repo}".encode()).hexdigest()[:8]
    tmp_dir = Path(tempfile.gettempdir()) / f"gvy-pr-{repo_hash}-{pr_number}"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    return tmp_dir


def save_msg_version(pr_number: int, title: str, body: str, source: str = "ai") -> str:
    """Save a version of the merge message and return its short ID."""
    cache_dir = get_pr_cache_dir(pr_number)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    content = f"{title}\n\n{body}"
    # Use MD5 for simple, short content addressing (non-cryptographic)
    msg_hash = hashlib.md5(content.encode()).hexdigest()[:7]

    # Save version file
    version_file = cache_dir / f"{timestamp}-{source}-{msg_hash}.md"
    version_file.write_text(content)

    # Also update 'latest' symlink/pointer
    latest_file = cache_dir / "latest.md"
    latest_file.write_text(content)

    return msg_hash


def validate_reply(body: str):
    """Reply must contain Commit SHA (7 or 40 chars) or Issue Ref."""
    # Match 7 or 40 hex chars exactly, surrounded by word boundaries
    sha_pattern = r"\b[0-9a-f]{7,40}\b"
    if not (re.search(sha_pattern, body) or re.search(r"#\d+", body)):
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
    tmp_base = Path(tempfile.gettempdir())
    cache_file = tmp_base / f"gvy-pr-{owner}-{repo}-{pr_number}-threads.json"

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
        except json.JSONDecodeError as e:
            typer.echo(f"Error decoding GraphQL response: {e}", err=True)
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
            # Thread path/line are on the thread node itself, not the comment
            path = t.get("path", "unknown")
            line = t.get("line", "?")

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
def history(
    pr_number: int = typer.Argument(None, help="PR number (auto-detected if omitted)"),
):
    """List historical merge messages for this PR."""
    if pr_number is None:
        pr_number = get_current_pr_number()
        if pr_number is None:
            rprint("[bold red]Error: Could not detect PR.[/bold red]")
            raise typer.Exit(1)

    cache_dir = get_pr_cache_dir(pr_number)
    files = sorted(cache_dir.glob("msg-*.md"), reverse=True)

    if not files:
        rprint(f"\n[yellow]No history found for PR #{pr_number}[/yellow]")
        return

    rprint(f"\n[bold cyan]Message History for PR #{pr_number}:[/bold cyan]")
    for f in files:
        parts = f.stem.split("-")
        # msg-timestamp-source-hash
        ts = f"{parts[1]}-{parts[2]}" if len(parts) > 2 else "unknown"
        source = parts[3] if len(parts) > 3 else "unknown"
        v_id = parts[4] if len(parts) > 4 else f.stem[-7:]

        content = f.read_text().split("\n", 1)
        title = content[0][:60] + "..." if content else "no title"

        rprint(
            f"  [bold green]{v_id}[/bold green] | {ts} | [dim]{source:7}[/dim] | {title}"
        )


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
    pr_body = pr.get("body") or ""
    # Matches: Relates to #123, Towards #123, See #123, Part of #123
    # Case insensitive, handles various separators
    related_pattern = r"(?:relates|towards|part of|connects to|see)\s+(?:to\s+)?#(\d+)"
    found_related = re.findall(related_pattern, pr_body, re.IGNORECASE)

    # Filter out issues that are already "Fixes"
    related_numbers = [n for n in found_related if int(n) not in closing_numbers]

    # Add unique valid related numbers
    unique_related = sorted(list(set(related_numbers)), key=int)

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

    # Create diff file in multiplatform temp dir
    tmp_base = Path(tempfile.gettempdir())
    diff_file = tmp_base / f"gvy-pr-{pr_number}.diff"
    try:
        with open(diff_file, "w") as f:
            subprocess.run(["gh", "pr", "diff", str(pr_number)], stdout=f, check=True)
    except subprocess.CalledProcessError:
        return "", ""

    # Construct XML Prompt parts
    # Part 1: Header (Instructions, Context, Patch start)
    header = f"""
<root>
  <instructions>
    <instruction>OUTPUT ONLY the commit message. No conversational text.</instruction>
    <instruction>SOURCE OF TRUTH: The content within the &lt;patch&gt; tag is the DEFINITIVE source of truth. PR titles and descriptions may be outdated or incomplete. Base your summary primarily on the code changes.</instruction>
    <instruction>
      Format:
      TITLE: &lt;type&gt;(&lt;scope&gt;): &lt;description&gt; (#{pr_number})
      BODY:
      - &lt;bullet point summary of functional change&gt;
      - &lt;bullet point summary of functional change&gt;
    </instruction>
    <instruction>
      Rules:
      - Use strict Conventional Commits types: feat, fix, docs, style, refactor, test, chore, perf, ci.
      - Description must be lower case, imperative mood.
      - Body should be a CONCISE summary of functional changes. Focus on "What" and "Why".
      - FEATURES &amp; DOCS: When documenting new features, signatures, or important code changes, MUST use code blocks (e.g. ```kotlin) to make them standout.
      - ISSUE GUIDANCE: If referenced issues exist, pro-actively include guidance in the body (e.g. "See #N for full design specs").
      - Do NOT include footer links like "Fixes #N" (added automatically).
    </instruction>
  </instructions>
  <context>
    <pr_number>{pr_number}</pr_number>
    <title>{pr.get("title")}</title>
    <description>{pr.get("body")}</description>
  </context>
  <patch>
    <location>{diff_file}</location>
    <content>
"""

    # Part 3: Footer (Patch end, Root end)
    footer = """
    </content>
  </patch>
</root>
"""

    try:
        # Write parts to temp files
        header_file = tmp_base / f"gvy-pr-{pr_number}-header.xml"
        footer_file = tmp_base / f"gvy-pr-{pr_number}-footer.xml"

        header_file.write_text(header)
        footer_file.write_text(footer)

        # Stream: cat header diff footer | gemini
        # Note: shell=True is used here to construct the pipe.
        # All file paths are generated from tempfile.gettempdir() and are safe.
        cmd = f"cat {shlex.quote(str(header_file))} {shlex.quote(str(diff_file))} {shlex.quote(str(footer_file))} | gemini"

        ps = subprocess.Popen(
            cmd,
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        stdout, stderr = ps.communicate()

        # Cleanup
        for f in [diff_file, header_file, footer_file]:
            if f.exists():
                f.unlink()

        if ps.returncode != 0:
            typer.echo(f"⚠️ Gemini failed: {stderr}", err=True)
            return "", ""

        output = stdout.strip()

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
    version: Optional[str] = typer.Option(
        None, "--version", "-v", help="Use a specific historical message version"
    ),
    history_list: bool = typer.Option(
        False, "--history", help="List historical messages for this PR"
    ),
):
    """
    Squash merge a PR with enforced semantic commit message.
    """
    # Auto-detect PR if not provided
    if pr_number is None:
        pr_number = get_current_pr_number()
        if pr_number is None:
            rprint(
                "[bold red]Error: Could not detect PR. Provide PR number.[/bold red]"
            )
            raise typer.Exit(1)

    if history_list:
        history(pr_number)
        return

    try:
        pr = get_pr_details(pr_number)
    except subprocess.CalledProcessError as e:
        rprint(f"[bold red]Error fetching PR: {e.stderr}[/bold red]")
        raise typer.Exit(1)

    # Initial Title/Body logic
    merge_title = ""
    merge_body = ""
    source = "ai" if ai else "manual"

    # 1. Load from history if requested
    if version:
        cache_dir = get_pr_cache_dir(pr_number)
        v_files = list(cache_dir.glob(f"*{version}*"))
        if not v_files:
            rprint(f"[bold red]Error: Version {version} not found.[/bold red]")
            raise typer.Exit(1)
        content = v_files[0].read_text().split("\n\n", 1)
        merge_title = content[0].strip()
        merge_body = content[1].strip() if len(content) > 1 else ""
        source = f"v{version}"
    # 2. AI Generation
    elif ai:
        ai_title, ai_body = generate_ai_message(pr, pr_number)
        if ai_title:
            merge_title = ai_title
            merge_body = ai_body
        else:
            rprint(
                "[bold red]AI generation failed. Falling back to default.[/bold red]"
            )

    # 3. Manual Fallback / Base
    if not merge_title:
        if title:
            merge_title = title
        else:
            existing = pr.get("title", "")
            merge_title = (
                existing
                if f"(#{pr_number})" in existing
                else f"{existing} (#{pr_number})"
            )

        if not merge_body:
            body_text, _ = generate_merge_body(pr, pr_number)
            merge_body = body_text

    # Title Override (Manual always wins if provided via CLI flag)
    if title and not version:
        merge_title = title

    # Validate title
    valid, error = validate_semantic_title(merge_title, pr_number)
    if not valid:
        typer.echo(f"\n❌ Invalid commit title:\n{error}", err=True)
        typer.echo(f"\nCurrent title: {merge_title}", err=True)
        typer.echo("\nUse --title to provide a valid semantic title.", err=True)
        raise typer.Exit(1)

    # Append Fixes if not present (DETERMINISTIC)
    linked_issues = pr.get("closingIssuesReferences", [])
    if linked_issues:
        if "## Fixes" not in merge_body:
            merge_body += "\n\n## Fixes\n"
        for issue in linked_issues:
            num = issue.get("number")
            if num and f"Fixes #{num}" not in merge_body:
                merge_body += f"\nFixes #{num}"

    # Related issues from PR body
    _, auto_related = generate_merge_body(pr, pr_number)
    if auto_related:
        if "## Related Issues" not in merge_body:
            merge_body += "\n\n## Related Issues\n"
        for num in auto_related:
            if f"Relates to #{num}" not in merge_body:
                merge_body += f"\nRelates to #{num}"

    # Explicitly related issues (from CLI)
    if relates_to:
        if "## Related Issues" not in merge_body:
            merge_body += "\n\n## Related Issues\n"
        for issue in relates_to:
            clean_issue = issue.strip().lstrip("#")
            if (
                clean_issue not in auto_related
                and f"Relates to #{clean_issue}" not in merge_body
            ):
                merge_body += f"\nRelates to #{clean_issue}"

    # Final Validation
    valid, error = validate_semantic_title(merge_title, pr_number)
    if not valid:
        rprint(f"\n❌ [bold red]Invalid commit title:[/bold red]\n{error}")
        rprint(f"\nCurrent title: {merge_title}")
        raise typer.Exit(1)

    # Save version before potential edit
    v_id = save_msg_version(pr_number, merge_title, merge_body, source=source)
    if source == "ai":
        rprint(
            f"\n✨ [bold cyan]AI Generated Version:[/bold cyan] [bold green]{v_id}[/bold green]"
        )
    elif version:
        rprint(
            f"\n📦 [bold cyan]Loaded Version:[/bold cyan] [bold green]{v_id}[/bold green]"
        )

    # Optional Editing
    if edit:
        full_msg = f"{merge_title}\n\n{merge_body}"
        edited_msg = typer.edit(full_msg, extension=".md")
        if edited_msg and edited_msg.strip() != full_msg.strip():
            lines = edited_msg.strip().split("\n", 1)
            merge_title = lines[0].strip()
            merge_body = lines[1].strip() if len(lines) > 1 else ""
            # Save edited version
            v_id = save_msg_version(pr_number, merge_title, merge_body, source="edit")
            rprint(
                f"📝 [bold cyan]Edited Version saved:[/bold cyan] [bold green]{v_id}[/bold green]"
            )

    # ---- FULL PREVIEW (EXACT CONTENT) ----
    if console.is_terminal:
        rprint("\n[bold cyan]" + "=" * 60 + "[/bold cyan]")
        rprint("[bold white]FINAL COMMIT MESSAGE PREVIEW[/bold white]")
        rprint("[bold cyan]" + "=" * 60 + "[/bold cyan]\n")

        rprint(
            Panel(
                merge_title,
                title="[bold white]Subject[/bold white]",
                border_style="green",
            )
        )
        rprint(
            Panel(
                Markdown(merge_body),
                title="[bold white]Body[/bold white]",
                border_style="blue",
            )
        )
        rprint("\n[bold cyan]" + "=" * 60 + "[/bold cyan]\n")
    else:
        typer.echo("\n" + "=" * 60)
        typer.echo("FINAL COMMIT MESSAGE PREVIEW")
        typer.echo("=" * 60)
        typer.echo(f"\nSUBJECT: {merge_title}")
        typer.echo(f"\nBODY:\n{merge_body}")
        typer.echo("\n" + "=" * 60 + "\n")

    if dry_run:
        rprint(
            f"\n[bold yellow]DRY RUN[/bold yellow]: Would merge PR #{pr_number} with version [bold green]{v_id}[/bold green]."
        )
        return

    if not typer.confirm(
        f"✨ Proceed with squash merge of PR #{pr_number}?", default=False
    ):
        rprint("[yellow]Merge cancelled.[/yellow]")
        return

    # Execute merge
    try:
        # Use tempfile for body to handle large content/newlines
        with tempfile.NamedTemporaryFile(mode="w", suffix=".md", delete=False) as tf:
            tf.write(merge_body)
            tf_path = tf.name

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
                tf_path,
            ],
            check=True,
        )
        rprint(f"\n✅ [bold green]PR #{pr_number} merged successfully![/bold green]")
        Path(tf_path).unlink()
    except subprocess.CalledProcessError as e:
        rprint(f"\n❌ [bold red]Merge failed: {e}[/bold red]")
        raise typer.Exit(1)


if __name__ == "__main__":
    app()
