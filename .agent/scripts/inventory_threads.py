#!/usr/bin/env python3
import json
import sys
import os


def main():
    if len(sys.argv) < 2:
        print("Usage: inventory_threads.py <json_file>")
        sys.exit(1)

    json_file = sys.argv[1]
    if not os.path.exists(json_file):
        print(f"Error: File {json_file} does not exist.")
        sys.exit(1)

    try:
        with open(json_file, "r") as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        print(f"Error: Failed to parse JSON: {e}")
        sys.exit(1)

    # Safe navigation to nodes
    try:
        # Handle potential structure variations (e.g. if root is data or not)
        root = data.get("data", data)
        nodes = (
            root.get("repository", {})
            .get("pullRequest", {})
            .get("reviewThreads", {})
            .get("nodes", [])
        )
    except AttributeError:
        print("Error: unexpected JSON structure.")
        sys.exit(1)

    if not nodes:
        print("No threads found.")
        return

    unresolved_count = 0
    print(f"{'ID':<25} {'Location':<30} {'Author':<15} {'Content'}")
    print("-" * 100)

    for node in nodes:
        if not node:
            continue

        is_resolved = node.get("isResolved", False)
        is_outdated = node.get("isOutdated", False)

        # Filter: Unresolved AND Not Outdated
        if not is_resolved and not is_outdated:
            unresolved_count += 1
            thread_id = node.get("id", "UNKNOWN")
            path = node.get("path") or "Global"
            line = node.get("line") or node.get("originalLine") or "-"

            # Get first comment safely
            comments = node.get("comments", {}).get("nodes", [])
            first_comment = comments[0] if comments and comments[0] else {}

            # Safe author handling
            author_node = first_comment.get("author")
            author = author_node.get("login") if author_node else "ghost"

            body = first_comment.get("body", "")
            # Truncate body
            snippet = (body[:50] + "...") if len(body) > 50 else body
            snippet = snippet.replace("\n", " ")

            location = f"{path}:{line}"
            # Truncate path if too long
            if len(location) > 28:
                location = "..." + location[-25:]

            print(f"{thread_id:<25} {location:<30} {author:<15} {snippet}")

    print("-" * 100)
    print(f"Total Unresolved Active Threads: {unresolved_count}")


if __name__ == "__main__":
    main()
