#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["typer", "pydantic", "rich", "httpx", "google-auth"]
# ///
"""
PR Review Manager - Token-efficient review thread CLI.

Features:
- Review thread management (list, resolve, reject)
- AI-powered semantic commit message generation
- Smart diff compression for large PRs (signature extraction, context removal)
- Resilient fallback chain: Gemini CLI → stats-only → direct API → Claude

All heavy lifting (GraphQL, caching, mutations) is handled here.
Agent only needs: thread_id, file, line, message.

Usage:
  pr.py threads [PR_NUMBER]       # List open review threads
  pr.py merge [PR_NUMBER] --ai    # Squash merge with AI commit message
  pr.py resolve <THREAD_ID> <MSG> # Resolve a thread with reply

Environment Variables:
  GVY_AI_TIMEOUT       - AI command timeout in seconds (default: 180)
  GVY_PROMPT_MAX_CHARS - Max prompt size before fallback (default: 400000)
  GOOGLE_API_KEY       - For direct Gemini API fallback (bypasses CLI OOM)
"""

import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
from abc import ABC, abstractmethod
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Iterator, Optional

import httpx
import typer
from pydantic import BaseModel, Field
from rich.console import Console
from rich.markdown import Markdown
from rich.panel import Panel
from rich.progress import Progress, SpinnerColumn, TextColumn
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


class AIProvider(str, Enum):
    """AI providers for commit message generation."""

    GEMINI = "gemini"
    CLAUDE = "claude"


class DiffMode(str, Enum):
    """How the diff was retrieved/processed."""

    FULL = "full"  # Complete diff, no truncation
    TRUNCATED = "truncated"  # Diff truncated for size
    SEMANTIC = "semantic"  # Compressed with signature extraction
    STATS_ONLY = "stats_only"  # Only file stats, no content


# =============================================================================
# DIFF RETRIEVAL TYPES
# =============================================================================


@dataclass(frozen=True)
class DiffResult:
    """Successful diff retrieval result."""

    content: str
    mode: DiffMode
    source: str  # Strategy that succeeded (e.g., "gh_cli", "local_git")
    file_count: int = 0
    total_additions: int = 0
    total_deletions: int = 0


@dataclass(frozen=True)
class DiffError:
    """Failed diff retrieval with fallback context."""

    message: str
    source: str  # Strategy that failed
    is_too_large: bool = False  # True if should try fallback strategies


# Union type for explicit success/failure handling (PEP 604)
DiffOutcome = DiffResult | DiffError


# =============================================================================
# AI PROVIDER CONFIGURATION
# =============================================================================
#
# KNOWN ISSUE: Gemini CLI OOM on Large Inputs
# ============================================
# The Gemini CLI is built on Node.js, which buffers entire stdin into memory
# before processing. For large prompts (>100KB), this causes V8 heap exhaustion:
#
#   FATAL ERROR: Ineffective mark-compacts near heap limit
#   Allocation failed - JavaScript heap out of memory
#
# Root cause: V8's String::SlowFlatten creates full copies during concatenation.
# Upstream issue: https://github.com/google-gemini/gemini-cli/issues/15917
#
# Claude CLI does NOT have this issue because it uses Bun (JavaScriptCore engine)
# which has better memory handling for large string operations.
#
# MITIGATION STRATEGY (Fallback Chain):
# 1. Try Gemini CLI with full/truncated diff
# 2. On OOM → Retry with stats-only mode (much smaller prompt)
# 3. On OOM → Try direct Google AI API (bypasses Node.js entirely)
# 4. On failure → Fall back to Claude CLI (Bun-based, handles large inputs)
#
# Users can also:
# - Use --stats-only flag proactively for large PRs
# - Use --provider claude to skip Gemini entirely
# - Set GVY_PROMPT_MAX_CHARS to a lower value (default: 400000)
# - Set GOOGLE_API_KEY for direct API fallback
# =============================================================================

# AI command timeout (in seconds) - generous but prevents infinite hangs
AI_TIMEOUT_SECONDS = int(os.environ.get("GVY_AI_TIMEOUT", "180"))


@dataclass(frozen=True)
class AIProviderConfig:
    """Configuration for an AI provider CLI."""

    cmd: str
    model_flag: str
    emoji: str
    default_model: str | None = None
    extra_args: tuple[str, ...] = ()
    # Runtime characteristics
    prone_to_oom: bool = False  # True for Node.js based CLIs


# Provider configurations as immutable dataclasses
AI_PROVIDERS: dict[AIProvider, AIProviderConfig] = {
    AIProvider.GEMINI: AIProviderConfig(
        cmd="gemini",
        model_flag="-m",
        default_model=None,  # Use gemini's default
        emoji="💎",
        prone_to_oom=True,  # Node.js based, OOMs on large stdin
    ),
    AIProvider.CLAUDE: AIProviderConfig(
        cmd="claude",
        model_flag="--model",
        default_model="sonnet",  # Fast and capable
        emoji="🤖",
        extra_args=("-p",),  # Print mode for non-interactive
        prone_to_oom=False,  # Bun-based, handles large inputs well
    ),
}

# Google AI API for direct HTTP fallback (bypasses Node.js CLI OOM issues)
GOOGLE_AI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models"
GOOGLE_AI_DEFAULT_MODEL = "gemini-2.0-flash"

# Thresholds for diff processing modes (in characters)
DIFF_SEMANTIC_THRESHOLD = 100_000  # Use semantic mode above this
DIFF_STATS_THRESHOLD = 300_000  # Use stats-only above this


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
        author = (comment_data.get("author") or {}).get("login", "unknown")
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


# =============================================================================
# TIMEOUT AND PROCESS HELPERS
# =============================================================================


class TimeoutError(Exception):
    """Raised when an operation times out."""

    pass


@contextmanager
def timeout_context(seconds: int, operation: str = "operation"):
    """Context manager for timing out operations (Unix only, graceful on Windows)."""

    def timeout_handler(signum, frame):
        raise TimeoutError(f"{operation} timed out after {seconds}s")

    # Only use SIGALRM on Unix systems
    if hasattr(signal, "SIGALRM"):
        old_handler = signal.signal(signal.SIGALRM, timeout_handler)
        signal.alarm(seconds)
        try:
            yield
        finally:
            signal.alarm(0)
            signal.signal(signal.SIGALRM, old_handler)
    else:
        # Windows: no timeout support, just yield
        yield


def run_with_timeout(
    cmd: list[str],
    *,
    timeout: int = AI_TIMEOUT_SECONDS,
    input_data: str | None = None,
    input_file: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    """
    Run a command with timeout support and flexible input handling.

    Args:
        cmd: Command and arguments
        timeout: Timeout in seconds
        input_data: String to pass via stdin (mutually exclusive with input_file)
        input_file: File path to use as stdin (mutually exclusive with input_data)

    Returns:
        CompletedProcess with stdout/stderr

    Raises:
        subprocess.TimeoutExpired: If command exceeds timeout
        subprocess.CalledProcessError: If command fails
    """
    stdin_source = None
    stdin_file = None

    try:
        if input_file:
            stdin_file = open(input_file, "r")
            stdin_source = stdin_file
        elif input_data is not None:
            stdin_source = subprocess.PIPE

        result = subprocess.run(
            cmd,
            stdin=stdin_source,
            input=input_data if input_data is not None and not input_file else None,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
        )
        return result
    finally:
        if stdin_file:
            stdin_file.close()


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


def run_gh(
    args: list[str],
    *,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    """
    Run gh CLI command with standard options.

    Centralizes subprocess handling for consistency across the codebase.

    Args:
        args (list[str]): Arguments to pass to gh CLI (without "gh" prefix).
            Example: ["pr", "diff", "123"] runs "gh pr diff 123"
        check (bool): If True, raise CalledProcessError on non-zero exit.
            Defaults to True.

    Returns:
        subprocess.CompletedProcess[str]: Completed process with stdout/stderr
            captured as text.

    Raises:
        subprocess.CalledProcessError: If check=True and command fails.
        FileNotFoundError: If gh CLI is not installed.
    """
    return subprocess.run(
        ["gh", *args],
        capture_output=True,
        text=True,
        check=check,
    )


def run_git(
    args: list[str],
    *,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    """
    Run git command with standard options.

    Centralizes subprocess handling for consistency with run_gh().

    Args:
        args (list[str]): Arguments to pass to git (without "git" prefix).
            Example: ["branch", "--show-current"] runs "git branch --show-current"
        check (bool): If True, raise CalledProcessError on non-zero exit.
            Defaults to True.

    Returns:
        subprocess.CompletedProcess[str]: Completed process with stdout/stderr
            captured as text.

    Raises:
        subprocess.CalledProcessError: If check=True and command fails.
        FileNotFoundError: If git is not installed.
    """
    return subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        check=check,
    )


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
    # Scope allows: lowercase letters, digits, hyphens, underscores, and forward slashes
    # Examples: feat(api), fix(semantics/native), refactor(core_utils)
    pattern = rf"^({'|'.join(COMMIT_TYPES)})(\([a-z0-9/_-]+\))?: .+ \(#{pr_number}\)$"
    if not re.match(pattern, title, re.IGNORECASE):
        return False, (
            f"Title must match: type(scope): description (#{pr_number})\n"
            f"Types: {', '.join(COMMIT_TYPES)}\n"
            f"Example: feat(api/auth): implement type inference (#{pr_number})"
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
            "title,body,commits,headRefName,baseRefName,state,mergeable,closingIssuesReferences",
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


# Diff size thresholds (in lines)
DIFF_FULL_THRESHOLD = 3000  # Use full diff if under this
DIFF_MAX_LINES = 8000  # Absolute max lines to send
DIFF_PER_FILE_MAX = 200  # Max lines per file in truncated mode

# Prompt size limit (configurable via env var)
# Default: 400KB - conservative for Node.js CLI stability (Gemini CLI)
# This prevents OOM errors when diffs are very large
try:
    PROMPT_MAX_CHARS = int(os.environ.get("GVY_PROMPT_MAX_CHARS", "400000"))
except ValueError:
    PROMPT_MAX_CHARS = 400_000

# GitHub API error patterns that indicate diff is too large
DIFF_TOO_LARGE_PATTERNS = [
    "exceeded the maximum number of files",
    "diff too large",
    "PullRequest.diff too_large",
    "HTTP 406",
]

# Files to exclude from diff content (show stats only)
# These files are auto-generated, have huge diffs, and add no semantic value
EXCLUDED_DIFF_PATTERNS = [
    "pnpm-lock.yaml",
    "package-lock.json",
    "yarn.lock",
    "Cargo.lock",
    "poetry.lock",
    "Gemfile.lock",
    "composer.lock",
    "go.sum",
    "MODULE.bazel.lock",
    "bun.lockb",
    "shrinkwrap.yaml",
    "npm-shrinkwrap.json",
    # Minified assets
    ".min.js",
    ".min.css",
    ".bundle.js",
    # Generated files
    ".generated.",
    ".g.dart",
    ".freezed.dart",
]

# File priority for large diffs (higher = more important, show more content)
# NOTE: Order matters! More specific patterns must come BEFORE general extensions
# so that "MyTest.kt" matches "Test.kt" (priority 25) before ".kt" (priority 100)
FILE_PRIORITY = {
    # Tests - lowest priority (must be checked FIRST due to specificity)
    "Test.kt": 25,
    "Test.java": 25,
    "_test.py": 25,
    ".test.ts": 25,
    ".spec.ts": 25,
    # Source files - highest priority
    ".kt": 100,
    ".java": 100,
    ".py": 100,
    ".ts": 90,
    ".tsx": 90,
    ".js": 85,
    ".jsx": 85,
    ".go": 100,
    ".rs": 100,
    ".scala": 100,
    # Config/build files - medium priority
    ".gradle": 70,
    ".gradle.kts": 70,
    ".toml": 60,
    ".yaml": 60,
    ".yml": 60,
    ".json": 50,
    ".xml": 40,
    # Docs - lower priority
    ".md": 30,
    ".txt": 20,
}


def get_file_priority(filepath: str) -> int:
    """Get priority score for a file (higher = more important).

    Relies on FILE_PRIORITY dict ordering: specific patterns (Test.kt)
    must come before general extensions (.kt) for correct matching.
    """
    for pattern, priority in FILE_PRIORITY.items():
        if filepath.endswith(pattern):
            return priority
    return 50  # Default priority


def is_excluded_from_diff(filepath: str) -> bool:
    """Check if a file should have its diff content excluded (stats only).

    Returns True for lock files, minified assets, and other auto-generated files
    that add noise without semantic value.
    """
    return any(pattern in filepath for pattern in EXCLUDED_DIFF_PATTERNS)


def get_file_diff_stats(file_diff: str) -> tuple[int, int]:
    """Extract additions and deletions count from a file diff."""
    adds, dels = 0, 0
    for line in file_diff.splitlines():
        if line.startswith("+") and not line.startswith("+++"):
            adds += 1
        elif line.startswith("-") and not line.startswith("---"):
            dels += 1
    return adds, dels


def format_excluded_files_summary(
    excluded_files: list[tuple[str, int, int]],
) -> list[str]:
    """Format excluded files as stats-only summary lines.

    Args:
        excluded_files: List of (filepath, additions, deletions) tuples

    Returns:
        List of formatted lines including header, or empty list if no excluded files
    """
    if not excluded_files:
        return []
    lines = ["=== EXCLUDED FILES (stats only, auto-generated) ==="]
    for filepath, adds, dels in excluded_files:
        lines.append(f"  {filepath} | +{adds} -{dels}")
    lines.append("")
    return lines


def iter_diff_files(diff_content: str) -> Iterator[tuple[str, str]]:
    """
    Generator that yields (filepath, file_diff) tuples from unified diff.

    Memory-efficient: processes line by line, yields as soon as a complete
    file diff is available. Useful for large diffs.
    """
    current_file: str | None = None
    current_lines: list[str] = []

    for line in diff_content.split("\n"):
        if line.startswith("diff --git"):
            # Yield previous file if exists
            if current_file:
                yield (current_file, "\n".join(current_lines))
            # Extract filepath from "diff --git a/path b/path"
            parts = line.split(" b/")
            current_file = parts[-1] if len(parts) > 1 else "unknown"
            current_lines = [line]
        else:
            current_lines.append(line)

    # Yield the last file
    if current_file:
        yield (current_file, "\n".join(current_lines))


def parse_diff_into_files(diff_content: str) -> list[tuple[str, str]]:
    """Parse unified diff into list of (filepath, file_diff) tuples."""
    return list(iter_diff_files(diff_content))


# =============================================================================
# SMART DIFF COMPRESSION ALGORITHMS
# =============================================================================
#
# These algorithms reduce diff size while preserving semantic meaning for AI.
# Used when diffs are too large for the AI provider's context window or cause OOM.
#
# Techniques:
# 1. Context Compression: Remove unchanged lines, keep only +/- with minimal context
#    - Typical reduction: 50-70% for diffs with lots of context
#
# 2. Signature Extraction: Parse added/removed function/class signatures
#    - Provides semantic overview without full implementation details
#    - Supports: Kotlin, Java, Python, TypeScript, JavaScript
#
# 3. Semantic Summary: Combines stats + signatures + priority-truncated diffs
#    - Prioritizes source files over tests, configs over docs
#    - Fits large PRs into reasonable prompt size
#
# Priority order (FILE_PRIORITY dict):
#   Source (.kt, .java, .py) > Config (.gradle, .yaml) > Tests > Docs
# =============================================================================


def compress_diff_content(file_diff: str, keep_context: int = 1) -> str:
    """
    Compress a file diff by removing excessive context lines.

    Keeps only +/- lines and minimal context around them.
    This can reduce diff size by 50-70% while preserving semantic meaning.

    Args:
        file_diff: Raw unified diff for a single file
        keep_context: Number of context lines to keep around changes (default: 1)

    Returns:
        Compressed diff string
    """
    lines = file_diff.split("\n")
    result: list[str] = []
    change_indices: set[int] = set()

    # First pass: identify lines with actual changes
    for i, line in enumerate(lines):
        if line.startswith(("+", "-")) and not line.startswith(("+++", "---")):
            change_indices.add(i)
            # Also mark context lines around changes
            for offset in range(-keep_context, keep_context + 1):
                change_indices.add(i + offset)

    # Second pass: keep headers and marked lines
    in_header = True
    last_kept = -10  # Track gaps for "..." markers

    for i, line in enumerate(lines):
        # Always keep file headers
        if line.startswith(("diff --git", "index ", "---", "+++", "@@")):
            result.append(line)
            in_header = line.startswith(("diff --git", "index ", "---", "+++"))
            last_kept = i
            continue

        if in_header:
            continue

        if i in change_indices:
            # Add gap marker if we skipped lines
            if i - last_kept > 1 and last_kept >= 0:
                result.append("  [...context...]")
            result.append(line)
            last_kept = i

    return "\n".join(result)


# Patterns for extracting signatures from code
SIGNATURE_PATTERNS = {
    # Kotlin/Java
    ".kt": [
        r"^\s*((?:public|private|protected|internal|override|suspend|inline|data|sealed|abstract|open)\s+)*(?:fun|class|interface|object|enum|typealias)\s+\w+",
        r"^\s*(?:val|var)\s+\w+\s*:",  # Properties
    ],
    ".java": [
        r"^\s*((?:public|private|protected|static|final|abstract|synchronized)\s+)*(?:class|interface|enum|void|\w+)\s+\w+\s*[({<]",
    ],
    # Python
    ".py": [
        r"^\s*(?:async\s+)?def\s+\w+",
        r"^\s*class\s+\w+",
    ],
    # TypeScript/JavaScript
    ".ts": [
        r"^\s*(?:export\s+)?(?:async\s+)?(?:function|class|interface|type|enum|const|let)\s+\w+",
        r"^\s*(?:public|private|protected)?\s*(?:async\s+)?\w+\s*\([^)]*\)\s*[:{]",  # Methods
    ],
    ".tsx": [
        r"^\s*(?:export\s+)?(?:async\s+)?(?:function|class|interface|type|enum|const|let)\s+\w+",
    ],
    ".js": [
        r"^\s*(?:export\s+)?(?:async\s+)?(?:function|class|const|let|var)\s+\w+",
    ],
}


def extract_changed_signatures(file_diff: str, filepath: str) -> list[str]:
    """
    Extract function/class signatures from added/removed lines.

    This provides a semantic summary of what changed without full code.

    Args:
        file_diff: Unified diff content for a single file
        filepath: Path to determine language

    Returns:
        List of signature strings with +/- prefix
    """
    signatures: list[str] = []

    # Determine patterns based on file extension
    ext = "." + filepath.rsplit(".", 1)[-1] if "." in filepath else ""
    patterns = SIGNATURE_PATTERNS.get(ext, [])

    if not patterns:
        return signatures

    compiled = [re.compile(p) for p in patterns]

    for line in file_diff.split("\n"):
        if not line.startswith(("+", "-")):
            continue
        if line.startswith(("+++", "---")):
            continue

        prefix = line[0]
        content = line[1:].rstrip()

        for pattern in compiled:
            if pattern.match(content):
                # Clean up and add
                sig = content.strip()
                # Truncate long signatures
                if len(sig) > 100:
                    sig = sig[:97] + "..."
                signatures.append(f"{prefix} {sig}")
                break

    return signatures


def create_semantic_summary(
    files: list[tuple[str, str]],
    max_chars: int = 50000,
) -> str:
    """
    Create a semantic summary of changes optimized for AI understanding.

    Strategy:
    1. For each file, extract signatures of changed functions/classes
    2. Include compressed diff for high-priority files
    3. Stats-only for low-priority files

    Args:
        files: List of (filepath, file_diff) tuples
        max_chars: Maximum characters for the summary

    Returns:
        Semantic summary string
    """
    sections: list[str] = []
    total_adds = 0
    total_dels = 0
    char_budget = max_chars

    # Sort by priority
    prioritized = sorted(
        [(get_file_priority(f), f, d) for f, d in files],
        key=lambda x: -x[0],
    )

    # Section 1: Overview stats
    file_stats: list[str] = []
    for _, filepath, diff in prioritized:
        adds, dels = get_file_diff_stats(diff)
        total_adds += adds
        total_dels += dels
        file_stats.append(f"  {filepath}: +{adds} -{dels}")

    overview = (
        f"=== CHANGE OVERVIEW ({len(files)} files, +{total_adds} -{total_dels}) ===\n"
        + "\n".join(file_stats[:20])  # Top 20 files
    )
    if len(file_stats) > 20:
        overview += f"\n  ... and {len(file_stats) - 20} more files"
    sections.append(overview)
    char_budget -= len(overview)

    # Section 2: Signatures for all files (very compact)
    sig_section: list[str] = ["", "=== KEY CHANGES (signatures) ==="]
    for _, filepath, diff in prioritized:
        sigs = extract_changed_signatures(diff, filepath)
        if sigs:
            sig_section.append(f"\n{filepath}:")
            sig_section.extend(f"  {s}" for s in sigs[:10])  # Max 10 per file
            if len(sigs) > 10:
                sig_section.append(f"  ... +{len(sigs) - 10} more")

    sig_text = "\n".join(sig_section)
    if len(sig_text) < char_budget * 0.3:  # Use max 30% for signatures
        sections.append(sig_text)
        char_budget -= len(sig_text)

    # Section 3: Compressed diff for top priority files
    sections.append("\n=== COMPRESSED DIFF (top changes) ===")
    for priority, filepath, diff in prioritized:
        if char_budget <= 1000:
            break
        if priority < 50:  # Skip low-priority files
            continue

        compressed = compress_diff_content(diff, keep_context=1)
        if len(compressed) > char_budget:
            # Further truncate
            compressed = compressed[: char_budget - 100] + "\n[...truncated...]"

        file_header = f"\n--- {filepath} ---\n"
        sections.append(file_header + compressed)
        char_budget -= len(file_header) + len(compressed)

    return "\n".join(sections)


def truncate_file_diff(file_diff: str, max_lines: int) -> tuple[str, bool]:
    """Truncate a file's diff to max_lines, return (content, was_truncated)."""
    lines = file_diff.split("\n")
    if len(lines) <= max_lines:
        return file_diff, False

    # Keep header (first 10 lines usually contain diff metadata)
    header_lines = min(10, max_lines // 4)
    # Split remaining between head and tail of actual changes
    remaining = max_lines - header_lines
    head_count = remaining * 2 // 3
    tail_count = remaining - head_count

    header = lines[:header_lines]
    middle_start = header_lines
    middle_end = len(lines) - tail_count

    truncated_count = max(0, middle_end - middle_start - head_count)
    head_section = lines[middle_start : middle_start + head_count]
    tail_section = lines[middle_end:]

    result = (
        header
        + head_section
        + [f"\n... [{truncated_count} lines truncated] ...\n"]
        + tail_section
    )
    return "\n".join(result), True


def generate_diff_stats(files: list[tuple[str, str]]) -> str:
    """Generate git-style stats from parsed diff files."""
    stats_lines = []
    total_adds = 0
    total_dels = 0

    for filepath, file_diff in files:
        adds = file_diff.count("\n+") - file_diff.count("\n+++")
        dels = file_diff.count("\n-") - file_diff.count("\n---")
        total_adds += adds
        total_dels += dels
        stats_lines.append(f" {filepath} | +{adds} -{dels}")

    stats_lines.append(
        f" {len(files)} files changed, {total_adds} insertions(+), {total_dels} deletions(-)"
    )
    return "\n".join(stats_lines)


# =============================================================================
# DIFF RETRIEVAL STRATEGIES
# =============================================================================


def _is_diff_too_large_error(error_text: str) -> bool:
    """Check if an error indicates the diff exceeded GitHub API limits."""
    error_lower = error_text.lower()
    return any(pattern.lower() in error_lower for pattern in DIFF_TOO_LARGE_PATTERNS)


def _calculate_diff_stats(content: str) -> tuple[int, int, int]:
    """
    Calculate file count, additions, and deletions from a diff string.

    Args:
        content: Unified diff content

    Returns:
        Tuple of (file_count, total_additions, total_deletions)
    """
    files = parse_diff_into_files(content)
    total_adds = 0
    total_dels = 0
    for _, file_diff in files:
        adds, dels = get_file_diff_stats(file_diff)
        total_adds += adds
        total_dels += dels
    return len(files), total_adds, total_dels


def _diff_via_gh_cli(pr_number: int) -> DiffOutcome:
    """
    Strategy 1: Fetch diff using `gh pr diff` command.

    This is the fastest and most common path. Works for ~99% of PRs.
    Fails with HTTP 406 when diff exceeds 300 files.
    """
    try:
        result = run_gh(["pr", "diff", str(pr_number)])
        content = result.stdout
        file_count, total_adds, total_dels = _calculate_diff_stats(content)

        return DiffResult(
            content=content,
            mode=DiffMode.FULL,
            source="gh_cli",
            file_count=file_count,
            total_additions=total_adds,
            total_deletions=total_dels,
        )
    except subprocess.CalledProcessError as e:
        error_text = e.stderr or e.stdout or ""
        return DiffError(
            message=f"gh pr diff failed (exit {e.returncode}): {error_text[:200]}",
            source="gh_cli",
            is_too_large=_is_diff_too_large_error(error_text),
        )
    except FileNotFoundError:
        return DiffError(
            message="'gh' CLI not found - is GitHub CLI installed?",
            source="gh_cli",
            is_too_large=False,
        )


def _diff_via_local_git(head_ref: str, base_ref: str) -> DiffOutcome:
    """
    Strategy 2: Fetch diff using local git when PR branch is checked out.

    Bypasses GitHub API entirely. Requires being on the PR's head branch.
    """
    try:
        # Check current branch
        current_branch = run_git(["branch", "--show-current"]).stdout.strip()

        if current_branch != head_ref:
            return DiffError(
                message=f"Not on PR branch (current: {current_branch}, need: {head_ref})",
                source="local_git",
                is_too_large=False,
            )

        # Find merge base for accurate diff
        merge_base_result = run_git(
            ["merge-base", f"origin/{base_ref}", "HEAD"],
            check=False,
        )

        if merge_base_result.returncode == 0:
            merge_base = merge_base_result.stdout.strip()
            diff_result = run_git(["diff", merge_base, "HEAD"])
        else:
            # Fallback: three-dot diff
            diff_result = run_git(["diff", f"origin/{base_ref}...HEAD"])

        content = diff_result.stdout
        file_count, total_adds, total_dels = _calculate_diff_stats(content)

        return DiffResult(
            content=content,
            mode=DiffMode.FULL,
            source="local_git",
            file_count=file_count,
            total_additions=total_adds,
            total_deletions=total_dels,
        )

    except subprocess.CalledProcessError as e:
        return DiffError(
            message=f"Local git diff failed: {e.stderr or e.stdout or 'unknown error'}",
            source="local_git",
            is_too_large=False,
        )
    except FileNotFoundError:
        return DiffError(
            message="git not found",
            source="local_git",
            is_too_large=False,
        )


def _fetch_pr_files_data(pr_number: int, source: str) -> list[dict] | DiffError:
    """
    Fetch file data for a PR from the GitHub API.

    Args:
        pr_number: PR number to fetch files for
        source: Source identifier for error messages (e.g., "files_api", "stats_only")

    Returns:
        List of file data dicts on success, DiffError on failure
    """
    try:
        owner, repo = get_repo_info()
    except (subprocess.CalledProcessError, OSError, json.JSONDecodeError) as e:
        return DiffError(
            message=f"Failed to get repo info ({type(e).__name__}): {e}",
            source=source,
            is_too_large=False,
        )

    try:
        result = run_gh(
            [
                "api",
                "--paginate",
                f"repos/{owner}/{repo}/pulls/{pr_number}/files",
            ]
        )

        files_data = json.loads(result.stdout)
        if not files_data:
            return DiffError(
                message="No files returned from API",
                source=source,
                is_too_large=False,
            )
        return files_data

    except subprocess.CalledProcessError as e:
        return DiffError(
            message=f"PR Files API failed: {e.stderr or e.stdout or 'unknown error'}",
            source=source,
            is_too_large=False,
        )
    except json.JSONDecodeError as e:
        return DiffError(
            message=f"Failed to parse API response: {e}",
            source=source,
            is_too_large=False,
        )


def _diff_via_files_api(pr_number: int) -> DiffOutcome:
    """
    Strategy 3: Fetch diff using GitHub's List PR Files API with pagination.

    Bypasses the 300-file limit of the diff endpoint. Returns file patches
    individually, though large file patches may be truncated by GitHub.
    """
    files_data = _fetch_pr_files_data(pr_number, "files_api")
    if isinstance(files_data, DiffError):
        return files_data

    # Build unified diff format from file patches
    diff_parts: list[str] = []
    total_adds = 0
    total_dels = 0
    files_with_patch = 0
    files_without_patch = 0

    for file_info in files_data:
        filename = file_info.get("filename", "unknown")
        status = file_info.get("status", "modified")
        patch = file_info.get("patch", "")
        additions = file_info.get("additions", 0)
        deletions = file_info.get("deletions", 0)

        total_adds += additions
        total_dels += deletions

        # Build diff header based on file status
        if status == "added":
            header = (
                f"diff --git a/{filename} b/{filename}\n"
                f"new file mode 100644\n"
                f"--- /dev/null\n"
                f"+++ b/{filename}"
            )
        elif status == "removed":
            header = (
                f"diff --git a/{filename} b/{filename}\n"
                f"deleted file mode 100644\n"
                f"--- a/{filename}\n"
                f"+++ /dev/null"
            )
        elif status == "renamed":
            prev = file_info.get("previous_filename", filename)
            header = (
                f"diff --git a/{prev} b/{filename}\n"
                f"rename from {prev}\n"
                f"rename to {filename}\n"
                f"--- a/{prev}\n"
                f"+++ b/{filename}"
            )
        else:
            header = (
                f"diff --git a/{filename} b/{filename}\n"
                f"--- a/{filename}\n"
                f"+++ b/{filename}"
            )

        if patch:
            diff_parts.append(f"{header}\n{patch}")
            files_with_patch += 1
        else:
            # No patch (binary file or too large)
            diff_parts.append(
                f"{header}\n@@ -0,0 +0,0 @@\n"
                f"# [Patch unavailable: +{additions} -{deletions}]"
            )
            files_without_patch += 1

    if files_without_patch > 0:
        typer.echo(
            f"📊 Retrieved {files_with_patch} patches via API "
            f"({files_without_patch} files had unavailable patches)"
        )

    return DiffResult(
        content="\n".join(diff_parts),
        mode=DiffMode.FULL,
        source="files_api",
        file_count=len(files_data),
        total_additions=total_adds,
        total_deletions=total_dels,
    )


def _diff_stats_only(pr_number: int) -> DiffOutcome:
    """
    Strategy 4: Create stats-only summary (last resort).

    Returns file list with change counts but no actual diff content.
    Always succeeds if the PR exists.
    """
    files_data = _fetch_pr_files_data(pr_number, "stats_only")
    if isinstance(files_data, DiffError):
        return files_data

    total_adds = 0
    total_dels = 0
    file_stats: list[tuple[str, str, int, int]] = []

    for file_info in files_data:
        filename = file_info.get("filename", "unknown")
        additions = file_info.get("additions", 0)
        deletions = file_info.get("deletions", 0)
        status = file_info.get("status", "modified")

        total_adds += additions
        total_dels += deletions

        status_marker = {"added": "A", "removed": "D", "renamed": "R"}.get(status, "M")
        file_stats.append((filename, status_marker, additions, deletions))

    # Sort by total changes (most changes first)
    file_stats.sort(key=lambda x: -(x[2] + x[3]))

    # Build summary
    summary_lines = [
        "=== DIFF SUMMARY (stats only - diff too large for API) ===",
        f"Total: {len(file_stats)} files, +{total_adds} -{total_dels}",
        "",
        "Files (sorted by change size):",
    ]

    for filename, status, adds, dels in file_stats:
        priority = get_file_priority(filename)
        marker = "★" if priority >= 80 else "·"
        summary_lines.append(f"  {marker} [{status}] {filename} | +{adds} -{dels}")

    summary_lines.extend(
        [
            "",
            "Legend: ★=high-priority source, ·=other, A=added, D=deleted, R=renamed, M=modified",
        ]
    )

    return DiffResult(
        content="\n".join(summary_lines),
        mode=DiffMode.STATS_ONLY,
        source="stats_only",
        file_count=len(file_stats),
        total_additions=total_adds,
        total_deletions=total_dels,
    )


def fetch_pr_diff(pr_number: int, pr_info: dict | None = None) -> DiffOutcome:
    """
    Fetch PR diff using deterministic fallback chain.

    Strategy order:
    1. gh pr diff - Fast, works for ~99% of PRs
    2. Local git - If (1) fails with "too large" AND we're on the PR branch
    3. PR Files API - Paginated endpoint, handles any number of files
    4. Stats only - Pure metadata, always works as last resort

    Args:
        pr_number: PR number to fetch
        pr_info: Optional PR details dict (must have headRefName, baseRefName)

    Returns:
        DiffResult on success, DiffError if all strategies fail
    """
    # Strategy 1: gh pr diff (most common path, works for ~99% of PRs)
    outcome = _diff_via_gh_cli(pr_number)
    if isinstance(outcome, DiffResult):
        return outcome

    # Log the initial failure
    typer.echo(f"⚠️ {outcome.message}", err=True)

    # Strategy 2: Local git diff (optional - only useful for "too large" errors)
    # This strategy requires being on the PR branch locally, so it's only
    # attempted when: (a) the error was "too large" AND (b) we have branch info.
    # For other errors (network, auth), this wouldn't help anyway.
    if outcome.is_too_large and pr_info:
        head_ref = pr_info.get("headRefName")
        base_ref = pr_info.get("baseRefName")
        if head_ref and base_ref:
            typer.echo("📂 Trying local git diff...", err=True)
            local_outcome = _diff_via_local_git(head_ref, base_ref)
            if isinstance(local_outcome, DiffResult):
                typer.echo(
                    f"✓ Local git diff succeeded ({local_outcome.file_count} files)"
                )
                return local_outcome
            typer.echo(f"   {local_outcome.message}", err=True)

    # Strategy 3: PR Files API (always attempted on any failure)
    # Uses a different API endpoint that may succeed when gh pr diff fails
    # for any reason (too large, network issues, rate limits, etc.)
    typer.echo("📡 Trying PR Files API with pagination...", err=True)
    api_outcome = _diff_via_files_api(pr_number)
    if isinstance(api_outcome, DiffResult):
        typer.echo(f"✓ PR Files API succeeded ({api_outcome.file_count} files)")
        return api_outcome
    typer.echo(f"   {api_outcome.message}", err=True)

    # Strategy 4: Stats only (last resort, always attempted)
    # Provides file list with change counts even when no diff content available
    typer.echo("📊 Falling back to stats-only summary...", err=True)
    stats_outcome = _diff_stats_only(pr_number)
    if isinstance(stats_outcome, DiffResult):
        typer.echo(f"✓ Stats summary created ({stats_outcome.file_count} files)")
        return stats_outcome
    typer.echo(f"   {stats_outcome.message}", err=True)

    # All strategies failed
    return DiffError(
        message="All diff retrieval strategies failed",
        source="fetch_pr_diff",
        is_too_large=outcome.is_too_large,
    )


def prepare_diff_for_ai(
    pr_number: int,
    pr_info: dict | None = None,
    force_stats_only: bool = False,
) -> tuple[str, str]:
    """
    Prepare diff content for AI, handling large diffs gracefully.

    This is the PUBLIC API that maintains backward compatibility.
    Internally uses fetch_pr_diff with fallback chain.

    Args:
        pr_number: PR number to fetch
        pr_info: Optional PR details (for local git fallback)
        force_stats_only: If True, skip diff content and return stats-only summary.
            Useful for very large PRs to avoid OOM errors.

    Returns:
        (diff_content, mode) where mode is 'full', 'truncated', 'stats_only', or 'error'

    Lock files (pnpm-lock.yaml, package-lock.json, etc.) are always excluded
    from diff content and shown as stats-only, regardless of diff size.
    """
    # Force stats-only mode (for OOM prevention or user override)
    if force_stats_only:
        typer.echo("📊 Using stats-only mode (forced)...", err=True)
        outcome = _diff_stats_only(pr_number)
        if isinstance(outcome, DiffError):
            typer.echo(f"⚠️ {outcome.message}", err=True)
            return "", "error"
        return outcome.content, outcome.mode.value

    # Fetch diff using strategy chain
    outcome = fetch_pr_diff(pr_number, pr_info)

    if isinstance(outcome, DiffError):
        typer.echo(f"⚠️ {outcome.message}", err=True)
        return "", "error"

    # Stats-only mode: return as-is (already formatted)
    if outcome.mode == DiffMode.STATS_ONLY:
        return outcome.content, outcome.mode.value

    # Full diff: apply filtering and truncation
    full_diff = outcome.content
    files = parse_diff_into_files(full_diff)

    # Separate excluded files (lock files, etc.) from regular files
    excluded_files: list[tuple[str, int, int]] = []
    regular_files: list[tuple[str, str]] = []

    for filepath, file_diff in files:
        if is_excluded_from_diff(filepath):
            adds, dels = get_file_diff_stats(file_diff)
            excluded_files.append((filepath, adds, dels))
        else:
            regular_files.append((filepath, file_diff))

    # Calculate lines after excluding lock files
    total_lines = sum(len(diff.split("\n")) for _, diff in regular_files)

    # Case 1: Small diff - use filtered diff
    if total_lines <= DIFF_FULL_THRESHOLD:
        output_parts = format_excluded_files_summary(excluded_files)
        for _, file_diff in regular_files:
            output_parts.append(file_diff)
        return "\n".join(output_parts), DiffMode.FULL.value

    # Case 2: Large diff - smart truncation
    typer.echo(
        f"📊 Large diff ({total_lines} lines after exclusions), using smart truncation..."
    )

    diff_stats = generate_diff_stats(files)

    # Sort by priority (highest first)
    files_with_priority = [(get_file_priority(f), f, diff) for f, diff in regular_files]
    files_with_priority.sort(key=lambda x: -x[0])

    output_parts = [
        "=== DIFF STATS (complete) ===",
        diff_stats,
        "",
    ]
    output_parts.extend(format_excluded_files_summary(excluded_files))
    output_parts.extend(
        [
            f"=== DIFF CONTENT (truncated from {total_lines} lines) ===",
            f"=== Showing {len(regular_files)} files, prioritized by importance ===",
            "",
        ]
    )

    lines_used = len("\n".join(output_parts).split("\n"))
    lines_budget = DIFF_MAX_LINES - lines_used

    included_files: list[tuple[str, str, bool, int, int]] = []
    for priority, filepath, file_diff in files_with_priority:
        if lines_budget <= 0:
            break

        file_lines = len(file_diff.split("\n"))
        priority_factor = priority / 100.0
        file_max = min(
            int(DIFF_PER_FILE_MAX * priority_factor * 1.5),
            lines_budget,
            file_lines,
        )
        file_max = max(file_max, 30)

        truncated_diff, was_truncated = truncate_file_diff(file_diff, file_max)
        truncated_lines = len(truncated_diff.split("\n"))

        included_files.append(
            (filepath, truncated_diff, was_truncated, file_lines, truncated_lines)
        )
        lines_budget -= truncated_lines

    for filepath, diff, truncated, orig_lines, kept_lines in included_files:
        marker = f" [TRUNCATED {orig_lines}→{kept_lines}]" if truncated else ""
        output_parts.append(f"--- FILE: {filepath}{marker} ---")
        output_parts.append(diff)
        output_parts.append("")

    included_count = len(included_files)
    total_count = len(regular_files)
    if included_count < total_count:
        skipped = [f for _, f, _ in files_with_priority[included_count:]]
        output_parts.append(
            f"=== {total_count - included_count} files skipped (low priority): ==="
        )
        for f in skipped[:10]:
            output_parts.append(f"  - {f}")
        if len(skipped) > 10:
            output_parts.append(f"  ... and {len(skipped) - 10} more")

    return "\n".join(output_parts), DiffMode.TRUNCATED.value


def _build_ai_prompt(
    pr: dict,
    pr_number: int,
    diff_content: str,
    diff_mode: str,
) -> str:
    """
    Build the AI prompt for commit message generation.

    Extracted to support retry logic when prompt is too large.

    Args:
        pr: PR details dict
        pr_number: PR number
        diff_content: Diff content (full, truncated, or stats-only)
        diff_mode: Mode of diff ('full', 'truncated', 'stats_only')

    Returns:
        Complete prompt string ready for AI processing
    """
    # Add context about truncation/stats-only mode to the prompt
    truncation_note = ""
    if diff_mode == "truncated":
        truncation_note = """
    <instruction>IMPORTANT: This diff has been TRUNCATED due to size. The DIFF STATS section shows complete file-level changes. Use both stats and available code context to understand the full scope of changes.</instruction>"""
    elif diff_mode == "stats_only":
        truncation_note = """
    <instruction>IMPORTANT: This PR is very large. Only FILE STATS are available (no actual diff content). Generate the commit message based on file names, change counts, and the PR title/description. Focus on the overall nature of the change (refactor, feature, etc.) rather than specific code changes.</instruction>"""

    return f"""<root>
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
      - Scope MUST contain only: lowercase letters, digits, hyphens, underscores, forward slashes.
      - Scope examples: api, semantics/native, core_utils, lsp/kotlin-ext
      - Description must be lowercase, imperative mood, no trailing period.
      - Body should be a CONCISE summary of functional changes. Focus on "What" and "Why".
      - FEATURES &amp; DOCS: When documenting new features, signatures, or important code changes, MUST use code blocks (e.g. ```kotlin) to make them standout.
      - ISSUE GUIDANCE: If referenced issues exist, pro-actively include guidance in the body (e.g. "See #N for full design specs").
      - Do NOT include footer links like "Fixes #N" (added automatically).
    </instruction>
    <instruction>
      SCOPE DERIVATION: Infer scope from the most affected module/directory.
      If most changes are in "src/lsp/", scope = "lsp".
      If in ".agent/scripts/", scope = "scripts".
      If mixed, use the top-level affected area or omit scope.
    </instruction>
    <instruction>
      BODY QUALITY: Each bullet should describe a FUNCTIONAL change (what changed + why).
      BAD: "- Modified pr.py" (just mentions file)
      GOOD: "- Add fallback chain for large diffs: gh CLI → local git → API → stats-only"
      Each bullet should be standalone and meaningful.
    </instruction>{truncation_note}
  </instructions>
  <context>
    <pr_number>{pr_number}</pr_number>
    <title>{pr.get("title")}</title>
    <description>{pr.get("body")}</description>
  </context>
  <patch>
    <content>
{diff_content}
    </content>
  </patch>
</root>
"""


# OOM error patterns for detection (specific patterns to avoid false positives)
OOM_ERROR_PATTERNS = [
    "ineffective mark-compact",
    "javascript heap out of memory",
    "fatal error: heap limit",
    "allocation failed",
    "out of memory",
    "heap limit reached",
]


def _is_oom_error(error_text: str) -> bool:
    """Check if error text indicates an out-of-memory condition."""
    error_lower = error_text.lower()
    return any(pattern in error_lower for pattern in OOM_ERROR_PATTERNS)


def _parse_ai_output(output: str, provider_name: str) -> tuple[str, str]:
    """
    Parse AI output into (title, body) tuple.

    Returns ("", "") if parsing fails completely.
    """
    if not output:
        return "", ""

    # Try structured TITLE/BODY format first
    title_match = re.search(r"TITLE:\s*(.+)", output)
    body_match = re.search(r"BODY:\s*(.*)", output, re.DOTALL)

    ai_title = title_match.group(1).strip() if title_match else ""
    ai_body = body_match.group(1).strip() if body_match else ""

    # Fallback: use first non-empty line as title
    if not ai_title:
        lines = [line for line in output.split("\n") if line.strip()]
        if lines:
            ai_title = lines[0].strip()
            ai_body = "\n".join(lines[1:]).strip()

    return ai_title, ai_body


def _call_ai_via_cli(
    prompt: str,
    provider: AIProvider,
    model: str | None = None,
) -> tuple[str, str, str | None]:
    """
    Call AI provider via CLI with resilient execution.

    Uses tempfile for prompt to stream via stdin, reducing Python memory pressure.
    Note: This doesn't fix Node.js OOM in Gemini CLI - use direct API for that.

    Args:
        prompt: The prompt text
        provider: AI provider enum
        model: Optional model override

    Returns:
        (stdout, stderr, error_message) - error_message is None on success
    """
    config = AI_PROVIDERS[provider]
    model_to_use = model or config.default_model

    # Security: validate cmd is an expected value (allowlist check)
    allowed_cmds = {"gemini", "claude"}
    if config.cmd not in allowed_cmds:
        return "", "", f"Invalid AI command: {config.cmd}"

    # Write prompt to tempfile - streams to stdin without holding in Python memory
    prompt_file = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False, encoding="utf-8"
        ) as f:
            f.write(prompt)
            prompt_file = Path(f.name)

        # Build command with provider-specific args
        cmd_args = [config.cmd]

        # Add extra args (e.g., -p for claude print mode)
        if config.extra_args:
            cmd_args.extend(config.extra_args)

        # Add model flag if model is specified
        if model_to_use:
            cmd_args.extend([config.model_flag, model_to_use])

        # Stream prompt from file to stdin
        result = run_with_timeout(
            cmd_args,
            timeout=AI_TIMEOUT_SECONDS,
            input_file=prompt_file,
        )

        if result.returncode != 0:
            error_detail = result.stderr.strip() or result.stdout.strip() or "No output"
            return (
                result.stdout,
                result.stderr,
                f"exit {result.returncode}: {error_detail[:300]}",
            )

        return result.stdout.strip(), result.stderr, None

    except subprocess.TimeoutExpired:
        return "", "", f"timed out after {AI_TIMEOUT_SECONDS}s"
    except FileNotFoundError:
        return "", "", f"{config.cmd} CLI not found - is it installed?"
    except Exception as e:
        return "", "", f"{type(e).__name__}: {e}"
    finally:
        if prompt_file and prompt_file.exists():
            prompt_file.unlink()


def _get_google_auth_token() -> str | None:
    """
    Get Google auth token using Application Default Credentials (ADC).

    This reuses the same credentials as the Gemini CLI (from `gcloud auth`
    or `gemini auth`). Falls back to API key if ADC not available.

    Returns:
        Access token string, or None if unavailable
    """
    try:
        import google.auth
        import google.auth.transport.requests

        # Get credentials from ADC (same as Gemini CLI uses)
        credentials, _ = google.auth.default(
            scopes=["https://www.googleapis.com/auth/generative-language"]
        )

        # Refresh to get valid access token
        credentials.refresh(google.auth.transport.requests.Request())
        return credentials.token

    except Exception as e:
        # ADC not available - will fall back to API key
        typer.echo(
            f"   ℹ️ ADC not available ({type(e).__name__}), trying API key...", err=True
        )
        return None


def _call_gemini_via_api(prompt: str, model: str | None = None) -> tuple[str, str]:
    """
    Direct HTTP call to Google AI API, bypassing Node.js CLI.

    This is the ultimate fallback when the CLI has OOM issues.

    Auth priority:
    1. ADC (Application Default Credentials) - reuses Gemini CLI auth
    2. GOOGLE_API_KEY or GEMINI_API_KEY environment variable

    Returns:
        (title, body) or ("", "") on failure
    """
    model_id = model or GOOGLE_AI_DEFAULT_MODEL

    # Try ADC first (reuses Gemini CLI credentials)
    access_token = _get_google_auth_token()

    if access_token:
        # Use OAuth token with Vertex AI style URL
        url = f"{GOOGLE_AI_API_URL}/{model_id}:generateContent"
        headers = {"Authorization": f"Bearer {access_token}"}
    else:
        # Fall back to API key
        api_key = os.environ.get("GOOGLE_API_KEY") or os.environ.get("GEMINI_API_KEY")
        if not api_key:
            typer.echo(
                "   💡 No auth available. Run `gcloud auth application-default login` "
                "or set GOOGLE_API_KEY",
                err=True,
            )
            return "", ""
        url = f"{GOOGLE_AI_API_URL}/{model_id}:generateContent?key={api_key}"
        headers = {}

    try:
        with httpx.Client(timeout=AI_TIMEOUT_SECONDS) as client:
            response = client.post(
                url,
                headers=headers,
                json={
                    "contents": [{"parts": [{"text": prompt}]}],
                    "generationConfig": {
                        "temperature": 0.3,
                        "maxOutputTokens": 2048,
                    },
                },
            )
            response.raise_for_status()

            data = response.json()
            text = (
                data.get("candidates", [{}])[0]
                .get("content", {})
                .get("parts", [{}])[0]
                .get("text", "")
            )

            return _parse_ai_output(text, "gemini-api")

    except httpx.TimeoutException:
        typer.echo(f"   ⚠️ API request timed out after {AI_TIMEOUT_SECONDS}s", err=True)
        return "", ""
    except httpx.HTTPStatusError as e:
        typer.echo(f"   ⚠️ API error: {e.response.status_code}", err=True)
        return "", ""
    except Exception as e:
        typer.echo(f"   ⚠️ API call failed: {type(e).__name__}: {e}", err=True)
        return "", ""


def generate_ai_message(
    pr: dict,
    pr_number: int,
    provider: AIProvider = AIProvider.GEMINI,
    model: Optional[str] = None,
    force_stats_only: bool = False,
    auto_fallback: bool = True,
) -> tuple[str, str]:
    """
    Generate commit message using AI with resilient fallback chain.

    Fallback order on failure:
    1. Primary provider with full diff
    2. Primary provider with stats-only diff (if OOM)
    3. Fallback provider (Claude if Gemini failed, or vice versa)
    4. Direct HTTP API (for Gemini, bypasses Node.js)

    Args:
        pr: PR details dict
        pr_number: PR number
        provider: AI provider to use (default: gemini)
        model: Optional model override
        force_stats_only: If True, skip diff content and use stats-only mode
        auto_fallback: If True, automatically try fallback strategies on failure
    """
    config = AI_PROVIDERS[provider]
    model_to_use = model or config.default_model

    # Build display string for logging
    model_display = f" ({model_to_use})" if model_to_use else ""

    # Use rich progress spinner for better UX
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        console=console,
        transient=True,
    ) as progress:
        task = progress.add_task(
            f"{config.emoji} Generating with {provider.value}{model_display}...",
            total=None,
        )

        # Get diff content with smart truncation for large diffs
        diff_content, diff_mode = prepare_diff_for_ai(
            pr_number, pr_info=pr, force_stats_only=force_stats_only
        )

        if diff_mode == "error":
            typer.echo(
                "⚠️ Failed to retrieve PR diff; skipping AI generation.", err=True
            )
            return "", ""

        # Build prompt
        prompt = _build_ai_prompt(pr, pr_number, diff_content, diff_mode)

        # Auto-downgrade to stats-only if prompt exceeds limit
        if len(prompt) > PROMPT_MAX_CHARS and diff_mode != DiffMode.STATS_ONLY.value:
            progress.update(
                task, description="📊 Prompt too large, using stats-only..."
            )
            diff_content, diff_mode = prepare_diff_for_ai(
                pr_number, pr_info=pr, force_stats_only=True
            )
            if diff_mode == "error":
                typer.echo("⚠️ Stats-only fallback also failed.", err=True)
                return "", ""
            prompt = _build_ai_prompt(pr, pr_number, diff_content, diff_mode)

        # Final size check
        if len(prompt) > PROMPT_MAX_CHARS:
            typer.echo(
                f"❌ Prompt too large ({len(prompt):,} chars) even with stats-only.",
                err=True,
            )
            return "", ""

        # Attempt 1: Primary provider via CLI
        stdout, stderr, error = _call_ai_via_cli(prompt, provider, model_to_use)

        if error is None:
            title, body = _parse_ai_output(stdout, provider.value)
            if title:
                return title, body
            typer.echo(f"⚠️ {provider.value} returned unparseable output", err=True)

        # Handle failure
        if error:
            typer.echo(f"⚠️ {provider.value} failed: {error}", err=True)

            is_oom = _is_oom_error(stderr or error)

            if is_oom and auto_fallback:
                # Attempt 2: Retry with stats-only (if not already)
                if diff_mode != DiffMode.STATS_ONLY.value:
                    progress.update(
                        task, description="📊 OOM detected, retrying stats-only..."
                    )
                    diff_content, diff_mode = prepare_diff_for_ai(
                        pr_number, pr_info=pr, force_stats_only=True
                    )
                    if diff_mode != "error":
                        prompt = _build_ai_prompt(
                            pr, pr_number, diff_content, diff_mode
                        )
                        stdout, stderr, error = _call_ai_via_cli(
                            prompt, provider, model_to_use
                        )
                        if error is None:
                            title, body = _parse_ai_output(stdout, provider.value)
                            if title:
                                typer.echo("✓ Succeeded with stats-only mode")
                                return title, body

                # Attempt 3: Direct API call (Gemini only)
                if provider == AIProvider.GEMINI:
                    progress.update(task, description="🌐 Trying direct API...")
                    title, body = _call_gemini_via_api(prompt, model_to_use)
                    if title:
                        typer.echo("✓ Succeeded via direct API")
                        return title, body

                # Attempt 4: Fallback to alternate provider
                fallback_provider = (
                    AIProvider.CLAUDE
                    if provider == AIProvider.GEMINI
                    else AIProvider.GEMINI
                )
                fallback_config = AI_PROVIDERS[fallback_provider]
                progress.update(
                    task,
                    description=f"{fallback_config.emoji} Falling back to {fallback_provider.value}...",
                )
                typer.echo(f"🔄 Falling back to {fallback_provider.value}...")

                stdout, stderr, error = _call_ai_via_cli(
                    prompt, fallback_provider, fallback_config.default_model
                )
                if error is None:
                    title, body = _parse_ai_output(stdout, fallback_provider.value)
                    if title:
                        typer.echo(f"✓ Succeeded with {fallback_provider.value}")
                        return title, body

                if error:
                    typer.echo(
                        f"⚠️ {fallback_provider.value} also failed: {error}", err=True
                    )

            # Provide helpful guidance
            typer.echo(
                "   💡 Suggestions: --stats-only, --provider claude, "
                "or set GOOGLE_API_KEY for direct API",
                err=True,
            )

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
        False, "--ai", "-a", help="Generate commit message using AI"
    ),
    provider: str = typer.Option(
        "gemini",
        "--provider",
        "-P",
        help="AI provider (case-insensitive): gemini (default), claude",
    ),
    model: Optional[str] = typer.Option(
        None,
        "--model",
        "-M",
        help="AI model override (e.g. 'gemini-2.0-flash', 'opus', 'sonnet')",
    ),
    stats_only: bool = typer.Option(
        False,
        "--stats-only",
        "-S",
        help="Use stats-only diff for AI (skip content for very large PRs)",
    ),
    no_fallback: bool = typer.Option(
        False,
        "--no-fallback",
        help="Disable automatic fallback to alternate providers on failure",
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

    # Validate and convert provider string to enum
    try:
        ai_provider = AIProvider(provider.lower())
    except ValueError:
        rprint(
            f"[bold red]Error: Invalid provider '{provider}'. Use (case-insensitive): gemini, claude[/bold red]"
        )
        raise typer.Exit(1)

    # Initial Title/Body logic
    merge_title = ""
    merge_body = ""
    source = f"ai-{ai_provider.value}" if ai else "manual"

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
        ai_title, ai_body = generate_ai_message(
            pr,
            pr_number,
            ai_provider,
            model,
            force_stats_only=stats_only,
            auto_fallback=not no_fallback,
        )
        if ai_title:
            merge_title = ai_title
            merge_body = ai_body
        else:
            rprint(
                f"[bold red]{ai_provider.value} generation failed. Falling back to default.[/bold red]"
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
