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
import os
import re
import shlex
import subprocess
import sys
import tempfile
import hashlib
from abc import ABC, abstractmethod
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Optional

import typer
from pydantic import BaseModel, Field
from rich.console import Console
from rich.panel import Panel
from rich.markdown import Markdown
from rich.table import Table
from rich.text import Text


# =============================================================================
# ENUMS
# =============================================================================


class OutputMode(str, Enum):
    """Output mode for thread display."""

    HUMAN = "human"
    LLM = "llm"
    JSON = "json"


class Severity(str, Enum):
    """Thread severity levels, ordered from highest to lowest."""

    CRITICAL = "critical"
    HIGH = "high"
    MAJOR = "major"
    MEDIUM = "medium"
    MINOR = "minor"
    LOW = "low"
    NITPICK = "nitpick"
    UNKNOWN = "unknown"

    @classmethod
    def priority(cls, sev: "Severity") -> int:
        """Return sort priority (lower = more severe)."""
        order = [
            cls.CRITICAL,
            cls.HIGH,
            cls.MAJOR,
            cls.MEDIUM,
            cls.MINOR,
            cls.LOW,
            cls.NITPICK,
            cls.UNKNOWN,
        ]
        try:
            return order.index(sev)
        except ValueError:
            return 999


class Reviewer(str, Enum):
    """Known AI reviewers."""

    CODERABBIT = "coderabbit"
    GEMINI = "gemini"
    COPILOT = "copilot"
    CURSOR = "cursor"
    HUMAN = "human"


# =============================================================================
# PYDANTIC MODELS
# =============================================================================


class ReviewerMetadata(BaseModel):
    """Extracted metadata from reviewer-specific formatting."""

    severity: Severity = Severity.UNKNOWN
    raw_severity_marker: Optional[str] = None
    has_analysis_chain: bool = False
    analysis_lines: int = 0


class ParsedComment(BaseModel):
    """A comment with extracted metadata and cleaned content."""

    id: str
    author: str
    reviewer: Reviewer
    body_raw: str
    body_clean: str  # Human-readable
    body_llm: str  # Token-efficient but complete
    metadata: ReviewerMetadata


class ReviewThread(BaseModel):
    """A review thread with parsed first comment."""

    thread_id: str
    comment_id: str
    path: str
    line: Optional[int] = None
    comment: ParsedComment
    primary_severity: Severity = Severity.UNKNOWN


class PRReviewSummary(BaseModel):
    """Complete review state for a PR."""

    pr_number: int
    pr_id: str
    total_threads: int
    threads: list[ReviewThread] = Field(default_factory=list)
    by_severity: dict[str, int] = Field(default_factory=dict)


# =============================================================================
# REVIEWER PARSERS
# =============================================================================


class ReviewerParser(ABC):
    """Base class for reviewer-specific parsing."""

    @abstractmethod
    def matches(self, author: str, body: str) -> bool:
        """Check if this parser handles the given content."""
        pass

    @abstractmethod
    def extract_metadata(self, body: str) -> ReviewerMetadata:
        """Extract severity, type, and other metadata."""
        pass

    @abstractmethod
    def clean_for_human(self, body: str) -> str:
        """Clean body for human display (preserve key formatting)."""
        pass

    @abstractmethod
    def clean_for_llm(self, body: str, compact: bool = False) -> str:
        """Clean body for LLM consumption.

        Args:
            body: Raw comment body
            compact: If True, collapse analysis sections to save tokens.
                     If False (default), show full content with markers.
        """
        pass


class CodeRabbitParser(ReviewerParser):
    """Parser for CodeRabbit review comments."""

    # _⚠️ Potential issue_ | _🟠 Major_
    SEVERITY_PATTERN = re.compile(
        r"_[⚠️⛔✅💡]*\s*(?:Potential issue|Bug|Critical|Suggestion)?_?\s*\|\s*_([🔴🟠🟡🟢⚪])\s*(\w+)_",
        re.IGNORECASE,
    )
    DETAILS_PATTERN = re.compile(
        r"<details>\s*<summary>(.*?)</summary>(.*?)</details>",
        re.DOTALL,
    )
    ANALYSIS_CHAIN_PATTERN = re.compile(r"🧩\s*Analysis chain")
    SCRIPT_EXECUTED_PATTERN = re.compile(r"🏁\s*Script executed:")
    NITPICK_PATTERN = re.compile(r"\[nitpick\]", re.IGNORECASE)

    SEVERITY_MAP = {
        "🔴": Severity.CRITICAL,
        "🟠": Severity.MAJOR,
        "🟡": Severity.MINOR,
        "🟢": Severity.NITPICK,
        "⚪": Severity.UNKNOWN,
    }

    def matches(self, author: str, body: str) -> bool:
        return "coderabbit" in author.lower()

    def extract_metadata(self, body: str) -> ReviewerMetadata:
        metadata = ReviewerMetadata()

        # Extract severity from emoji pattern
        match = self.SEVERITY_PATTERN.search(body)
        if match:
            emoji, text = match.groups()
            metadata.severity = self.SEVERITY_MAP.get(emoji, Severity.UNKNOWN)
            metadata.raw_severity_marker = f"{emoji} {text}"

        # Check for nitpick
        if self.NITPICK_PATTERN.search(body):
            metadata.severity = Severity.NITPICK

        # Check for analysis chain
        if self.ANALYSIS_CHAIN_PATTERN.search(body):
            metadata.has_analysis_chain = True
            # Count lines in details sections
            details_matches = self.DETAILS_PATTERN.findall(body)
            metadata.analysis_lines = sum(
                len(content.split("\n")) for _, content in details_matches
            )

        return metadata

    def clean_for_human(self, body: str) -> str:
        # Replace details with collapsible indicator
        def replace_details(m):
            summary = m.group(1).strip()
            return f"\n[{summary}] (expandable)\n"

        body = self.DETAILS_PATTERN.sub(replace_details, body)
        return body.strip()

    def clean_for_llm(self, body: str, compact: bool = False) -> str:
        if compact:
            # Compact mode: collapse analysis sections to save tokens
            def replace_with_collapsed(m):
                content = m.group(2)
                lines = len(content.strip().split("\n"))
                return f"\n[analysis: {lines} lines]\n"

            body = self.DETAILS_PATTERN.sub(replace_with_collapsed, body)
        else:
            # Default: expand with clear markers for LLM parsing
            def replace_with_markers(m):
                summary = m.group(1).strip()
                content = m.group(2).strip()
                return f"\n[analysis:start:{summary}]\n{content}\n[analysis:end]\n"

            body = self.DETAILS_PATTERN.sub(replace_with_markers, body)

        # Remove severity header line (already extracted to metadata)
        body = self.SEVERITY_PATTERN.sub("", body)

        # Clean up whitespace
        body = re.sub(r"\n{3,}", "\n\n", body)
        return body.strip()


class GeminiParser(ReviewerParser):
    """Parser for Gemini Code Assist comments."""

    # ![high](https://www.gstatic.com/codereviewagent/high-priority.svg)
    PRIORITY_BADGE_PATTERN = re.compile(
        r"!\[(high|medium|low|critical)\]\(https://www\.gstatic\.com/codereviewagent/\w+-priority\.svg\)",
        re.IGNORECASE,
    )

    SEVERITY_MAP = {
        "critical": Severity.CRITICAL,
        "high": Severity.HIGH,
        "medium": Severity.MEDIUM,
        "low": Severity.LOW,
    }

    def matches(self, author: str, body: str) -> bool:
        return "gemini" in author.lower() or "gstatic.com/codereviewagent" in body

    def extract_metadata(self, body: str) -> ReviewerMetadata:
        metadata = ReviewerMetadata()

        match = self.PRIORITY_BADGE_PATTERN.search(body)
        if match:
            priority = match.group(1).lower()
            metadata.severity = self.SEVERITY_MAP.get(priority, Severity.UNKNOWN)
            metadata.raw_severity_marker = f"[{priority}]"

        return metadata

    def clean_for_human(self, body: str) -> str:
        # Replace badge with readable text
        body = self.PRIORITY_BADGE_PATTERN.sub(r"[\1 priority] ", body)
        return body.strip()

    def clean_for_llm(self, body: str, compact: bool = False) -> str:
        # Remove badge (severity is in structured metadata)
        body = self.PRIORITY_BADGE_PATTERN.sub("", body)
        return body.strip()


class CursorParser(ReviewerParser):
    """Parser for Cursor AI comments."""

    # <!-- **Low Severity** -->
    SEVERITY_COMMENT_PATTERN = re.compile(
        r"<!--\s*\*\*(\w+)\s*Severity\*\*\s*-->",
        re.IGNORECASE,
    )
    HEADER_PATTERN = re.compile(r"^###\s+(.+)$", re.MULTILINE)

    SEVERITY_MAP = {
        "critical": Severity.CRITICAL,
        "high": Severity.HIGH,
        "medium": Severity.MEDIUM,
        "low": Severity.LOW,
    }

    def matches(self, author: str, body: str) -> bool:
        return "cursor" in author.lower()

    def extract_metadata(self, body: str) -> ReviewerMetadata:
        metadata = ReviewerMetadata()

        match = self.SEVERITY_COMMENT_PATTERN.search(body)
        if match:
            severity_text = match.group(1).lower()
            metadata.severity = self.SEVERITY_MAP.get(severity_text, Severity.UNKNOWN)
            metadata.raw_severity_marker = f"[{severity_text}]"

        return metadata

    def clean_for_human(self, body: str) -> str:
        # Remove HTML comments but keep headers
        body = self.SEVERITY_COMMENT_PATTERN.sub("", body)
        return body.strip()

    def clean_for_llm(self, body: str, compact: bool = False) -> str:
        # Remove HTML severity comments (severity is in structured metadata)
        body = self.SEVERITY_COMMENT_PATTERN.sub("", body)
        if compact:
            # Simplify headers in compact mode
            body = self.HEADER_PATTERN.sub(r"\1:", body)
        return body.strip()


class CopilotParser(ReviewerParser):
    """Parser for GitHub Copilot comments."""

    def matches(self, author: str, body: str) -> bool:
        return "copilot" in author.lower()

    def extract_metadata(self, body: str) -> ReviewerMetadata:
        # Copilot doesn't have explicit severity markers - infer from keywords
        metadata = ReviewerMetadata()
        body_lower = body.lower()

        if any(
            w in body_lower for w in ["bug", "error", "crash", "breaking", "security"]
        ):
            metadata.severity = Severity.HIGH
        elif any(w in body_lower for w in ["issue", "problem", "incorrect"]):
            metadata.severity = Severity.MEDIUM
        elif any(w in body_lower for w in ["suggest", "consider", "might", "could"]):
            metadata.severity = Severity.LOW
        else:
            metadata.severity = Severity.MEDIUM

        return metadata

    def clean_for_human(self, body: str) -> str:
        return body.strip()

    def clean_for_llm(self, body: str, compact: bool = False) -> str:
        return body.strip()


class HumanReviewerParser(ReviewerParser):
    """Fallback parser for human reviewers."""

    def matches(self, author: str, body: str) -> bool:
        return True  # Fallback

    def extract_metadata(self, body: str) -> ReviewerMetadata:
        return ReviewerMetadata(severity=Severity.MEDIUM)

    def clean_for_human(self, body: str) -> str:
        return body.strip()

    def clean_for_llm(self, body: str, compact: bool = False) -> str:
        return body.strip()


class ParserRegistry:
    """Registry of reviewer parsers."""

    def __init__(self):
        self.parsers: list[ReviewerParser] = [
            CodeRabbitParser(),
            GeminiParser(),
            CursorParser(),
            CopilotParser(),
            HumanReviewerParser(),  # Fallback
        ]

    def get_parser(self, author: str, body: str) -> ReviewerParser:
        for parser in self.parsers:
            if parser.matches(author, body):
                return parser
        return self.parsers[-1]  # Default to human parser

    def classify_reviewer(self, author: str) -> Reviewer:
        author_lower = author.lower()
        if "coderabbit" in author_lower:
            return Reviewer.CODERABBIT
        elif "gemini" in author_lower:
            return Reviewer.GEMINI
        elif "copilot" in author_lower:
            return Reviewer.COPILOT
        elif "cursor" in author_lower:
            return Reviewer.CURSOR
        return Reviewer.HUMAN

    def parse_comment(self, comment_data: dict, compact: bool = False) -> ParsedComment:
        """Parse a comment with reviewer-specific handling.

        Args:
            comment_data: Raw comment data from GitHub API
            compact: If True, collapse analysis sections to save tokens.
                     If False (default), show full content with markers.
        """
        author = comment_data.get("author", {}).get("login", "unknown")
        body = comment_data.get("body", "")

        parser = self.get_parser(author, body)
        metadata = parser.extract_metadata(body)

        return ParsedComment(
            id=comment_data.get("id", ""),
            author=author,
            reviewer=self.classify_reviewer(author),
            body_raw=body,
            body_clean=parser.clean_for_human(body),
            body_llm=parser.clean_for_llm(body, compact=compact),
            metadata=metadata,
        )


# Global parser registry
parser_registry = ParserRegistry()


# =============================================================================
# FORMATTERS
# =============================================================================


class HumanFormatter:
    """Rich-based formatter for human-readable output."""

    SEVERITY_STYLES = {
        Severity.CRITICAL: ("bold red", "CRIT"),
        Severity.HIGH: ("bold orange1", "HIGH"),
        Severity.MAJOR: ("bold yellow", "MAJR"),
        Severity.MEDIUM: ("yellow", "MED"),
        Severity.MINOR: ("cyan", "MINR"),
        Severity.LOW: ("dim", "LOW"),
        Severity.NITPICK: ("dim italic", "NIT"),
        Severity.UNKNOWN: ("white", "???"),
    }

    REVIEWER_STYLES = {
        Reviewer.CODERABBIT: ("magenta", "🐰"),
        Reviewer.GEMINI: ("blue", "💎"),
        Reviewer.COPILOT: ("green", "🤖"),
        Reviewer.CURSOR: ("cyan", "📍"),
        Reviewer.HUMAN: ("white", "👤"),
    }

    def __init__(self, console: Console):
        self.console = console

    def render_summary(self, summary: PRReviewSummary):
        """Render PR review summary with severity breakdown."""
        self.console.print()
        self.console.print(
            Panel(
                f"PR #{summary.pr_number} - {summary.total_threads} open threads",
                title="[bold cyan]Review Summary[/bold cyan]",
                border_style="cyan",
            )
        )

        if not summary.threads:
            self.console.print("\n✨ [bold green]All threads resolved![/bold green]")
            return

        # Severity breakdown table
        table = Table(show_header=False, box=None, padding=(0, 2))
        table.add_column("Severity", style="bold")
        table.add_column("Count", justify="right")

        row_items = []
        for severity in [
            Severity.CRITICAL,
            Severity.HIGH,
            Severity.MAJOR,
            Severity.MEDIUM,
            Severity.MINOR,
            Severity.LOW,
            Severity.NITPICK,
        ]:
            count = summary.by_severity.get(severity.value, 0)
            if count > 0:
                style, label = self.SEVERITY_STYLES[severity]
                row_items.append(f"[{style}]{label}={count}[/{style}]")

        if row_items:
            self.console.print("  " + "  ".join(row_items))
        self.console.print()

    def render_thread(self, thread: ReviewThread):
        """Render a single thread with reviewer styling."""
        comment = thread.comment
        severity_style, severity_label = self.SEVERITY_STYLES[thread.primary_severity]
        reviewer_style, reviewer_emoji = self.REVIEWER_STYLES[comment.reviewer]

        # Build title with metadata
        title = Text()
        title.append(f"[{severity_label}] ", style=severity_style)
        title.append(f"{reviewer_emoji} @{comment.author}", style=reviewer_style)
        title.append(" at ", style="dim")
        title.append(f"{thread.path}:{thread.line or '?'}", style="bold white")

        # Subtitle with thread ID for copying
        subtitle = f"T={thread.thread_id}"

        self.console.print(
            Panel(
                Markdown(comment.body_clean),
                title=title,
                subtitle=f"[dim]{subtitle}[/dim]",
                border_style=severity_style.split()[0]
                if " " in severity_style
                else severity_style,
            )
        )

    def render_all(self, summary: PRReviewSummary):
        """Render complete PR review."""
        self.render_summary(summary)
        for thread in summary.threads:
            self.render_thread(thread)


class LLMFormatter:
    """Token-efficient formatter for LLM consumption."""

    def render_summary(self, summary: PRReviewSummary) -> str:
        """Render compact summary header."""
        lines = [
            f"PR={summary.pr_number} PR_ID={summary.pr_id} THREADS={summary.total_threads}",
            "",
        ]

        # Severity breakdown
        sev_parts = []
        for sev in [
            Severity.CRITICAL,
            Severity.HIGH,
            Severity.MAJOR,
            Severity.MEDIUM,
            Severity.MINOR,
            Severity.LOW,
            Severity.NITPICK,
        ]:
            count = summary.by_severity.get(sev.value, 0)
            if count > 0:
                sev_parts.append(f"{sev.value.upper()}={count}")

        if sev_parts:
            lines.append("SEVERITY: " + " ".join(sev_parts))
            lines.append("")

        return "\n".join(lines)

    def render_thread(self, thread: ReviewThread) -> str:
        """Render thread in structured, parseable format."""
        comment = thread.comment

        lines = [
            "---",
            f"T={thread.thread_id}",
            f"FILE={thread.path}",
            f"LINE={thread.line or 'N/A'}",
            f"SEVERITY={thread.primary_severity.value.upper()}",
            f"REVIEWER={comment.reviewer.value}",
            f"AUTHOR=@{comment.author}",
            "",
            "BODY:",
            comment.body_llm,  # Full content, no truncation
            "---",
        ]

        return "\n".join(lines)

    def render_system_reminder(self) -> str:
        """Render action instructions using <system_reminder> tags."""
        return """
<system_reminder>
ACTION: FIX|DEFER|REJECT
CODE: Use imports, avoid FQNs
COMMIT: Use multiple -m flags
FIX: Make change, test, commit. Reply: 'Fixed in <SHA>.'
DEFER: Create issue via /defer. Reply: 'Created #<N>. Out of scope.'
REJECT: Reply with technical reasoning. Thread stays open.
RESOLVE: pr.py resolve <T> '<reply with SHA or #issue>'
REMINDER: Loop until all threads are resolved.
</system_reminder>
"""

    def render_all(self, summary: PRReviewSummary) -> str:
        """Render complete PR review."""
        parts = [self.render_summary(summary)]

        if not summary.threads:
            parts.append("All threads resolved!")
            return "\n".join(parts)

        for thread in summary.threads:
            parts.append(self.render_thread(thread))

        parts.append(self.render_system_reminder())
        return "\n".join(parts)


class JSONFormatter:
    """JSON formatter for programmatic parsing."""

    def render(self, summary: PRReviewSummary) -> str:
        """Render complete PR review as JSON."""
        return summary.model_dump_json(indent=2)


# =============================================================================
# MODE DETECTION
# =============================================================================


def detect_output_mode(explicit_mode: Optional[OutputMode] = None) -> OutputMode:
    """
    Detect output mode with precedence:
    1. Explicit --mode flag (highest priority)
    2. CLAUDE_CODE environment variable -> LLM mode
    3. CI environment variable -> LLM mode
    4. Piped output (not isatty) -> LLM mode
    5. TTY -> Human mode (default)
    """
    if explicit_mode:
        return explicit_mode

    # Environment-based detection
    if os.environ.get("CLAUDE_CODE"):
        return OutputMode.LLM

    if os.environ.get("CI") or os.environ.get("GITHUB_ACTIONS"):
        return OutputMode.LLM

    # Terminal detection
    if not sys.stdout.isatty():
        return OutputMode.LLM

    return OutputMode.HUMAN


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


def build_review_summary(
    pr_number: int,
    pr_id: str,
    raw_threads: list,
    compact: bool = False,
) -> PRReviewSummary:
    """Build a structured review summary from raw thread data.

    Args:
        pr_number: PR number
        pr_id: PR ID from GitHub
        raw_threads: Raw thread data from GitHub API
        compact: If True, collapse analysis sections to save tokens.
                 If False (default), show full content with markers.
    """
    parsed_threads: list[ReviewThread] = []

    for t in raw_threads:
        t_id = t["id"]
        comments = t.get("comments", {}).get("nodes", [])
        if not comments:
            continue

        first_comment = comments[0]
        parsed_comment = parser_registry.parse_comment(first_comment, compact=compact)

        path = t.get("path", "unknown")
        line = t.get("line")

        thread = ReviewThread(
            thread_id=t_id,
            comment_id=first_comment.get("id", ""),
            path=path,
            line=line,
            comment=parsed_comment,
            primary_severity=parsed_comment.metadata.severity,
        )
        parsed_threads.append(thread)

    # Sort by severity (most severe first)
    parsed_threads.sort(key=lambda t: Severity.priority(t.primary_severity))

    # Build severity counts
    by_severity: dict[str, int] = {}
    for thread in parsed_threads:
        sev = thread.primary_severity.value
        by_severity[sev] = by_severity.get(sev, 0) + 1

    return PRReviewSummary(
        pr_number=pr_number,
        pr_id=pr_id,
        total_threads=len(parsed_threads),
        threads=parsed_threads,
        by_severity=by_severity,
    )


@app.command()
def threads(
    pr_number: int = typer.Argument(None, help="PR number (auto-detected if omitted)"),
    refetch: bool = typer.Option(False, "--refetch", help="Force refetch from GitHub"),
    mode: Optional[str] = typer.Option(
        None,
        "--mode",
        "-m",
        help="Output mode: human (rich), llm (structured), json",
    ),
    json_output: bool = typer.Option(
        False,
        "--json",
        "-j",
        help="Shorthand for --mode json",
    ),
    compact: bool = typer.Option(
        False,
        "--compact",
        "-c",
        help="Collapse analysis sections to save tokens (default: expanded with markers)",
    ),
):
    """Inventory open threads and display summary."""
    if pr_number is None:
        pr_number = get_current_pr_number()
        if pr_number is None:
            rprint(
                "[bold red]Error: Could not detect PR. Provide PR number.[/bold red]"
            )
            raise typer.Exit(1)

    # Determine output mode
    explicit_mode = None
    if json_output:
        explicit_mode = OutputMode.JSON
    elif mode:
        try:
            explicit_mode = OutputMode(mode.lower())
        except ValueError:
            rprint(
                f"[bold red]Error: Invalid mode '{mode}'. Use: human, llm, json[/bold red]"
            )
            raise typer.Exit(1)

    output_mode = detect_output_mode(explicit_mode)

    try:
        pr_id, raw_threads = get_thread_inventory(pr_number, force_refetch=refetch)

        # Build structured summary
        summary = build_review_summary(pr_number, pr_id, raw_threads, compact=compact)

        # Render based on mode
        if output_mode == OutputMode.JSON:
            formatter = JSONFormatter()
            typer.echo(formatter.render(summary))
        elif output_mode == OutputMode.LLM:
            formatter = LLMFormatter()
            typer.echo(formatter.render_all(summary))
        else:  # HUMAN mode
            formatter = HumanFormatter(console)
            formatter.render_all(summary)

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


@app.command()
def reject(
    thread_id: str = typer.Argument(..., help="Thread ID (T=...)"),
    rationale: str = typer.Argument(..., help="Technical reasoning for rejection"),
):
    """Reply to reject a thread (false positive). Does NOT resolve - lets reviewer respond."""
    try:
        q_reply = load_query("reply-to-thread.graphql")

        subprocess.run(
            [
                "gh",
                "api",
                "graphql",
                "-F",
                f"threadId={thread_id}",
                "-F",
                f"body={rationale}",
                "-f",
                f"query={q_reply}",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        print(f"💬 {thread_id} (replied, not resolved)")
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
