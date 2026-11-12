#!/usr/bin/env bash
# Fail if added lines contain leftover merge/rebase conflict markers, including
# the diff3 base marker (`|||||||`). Unlike `git diff --check` it does not flag
# whitespace, and it tolerates `=======` heading underlines in documentation.
#
# Usage: check-conflict-markers.sh --staged
#        check-conflict-markers.sh <base-ref>     (checks <base-ref>...HEAD)
#
# Only added lines are examined (git omits binary content), so existing text
# is never flagged. In documentation files (.md .rst .adoc .txt) a lone
# `=======` is a legitimate heading underline; it only counts when the same
# file also has a `<<<<<<<`, `|||||||` or `>>>>>>>` marker.
set -euo pipefail

usage() {
    echo "usage: check-conflict-markers.sh --staged | <base-ref>" >&2
    exit 2
}

[[ $# -eq 1 ]] || usage
if [[ "$1" == "--staged" ]]; then
    range=(--cached)
else
    range=("$1...HEAD")
fi

# --no-ext-diff/--no-textconv keep the output literal; -z is not used because
# the file name only labels the report.
git diff "${range[@]}" --unified=0 --no-color --no-ext-diff --no-textconv \
    --diff-filter=d |
    awk '
    function flush(   i, n, keep, doc) {
      for (i = 1; i <= count; i++) {
        keep = 1
        if (kind[i] == "sep" && isdoc[file_of[i]] && !strong[file_of[i]]) keep = 0
        if (keep) {
          printf "%s:%d: leftover conflict marker: %s\n", file_of[i], line_of[i], text[i]
          found = 1
        }
      }
    }
    /^diff --git / {
      inheader = 1
      next
    }
    inheader && /^\+\+\+ / {
      current = substr($0, 5)
      sub(/^b\//, "", current)
      isdoc[current] = (current ~ /\.(md|rst|adoc|txt)$/)
      next
    }
    /^@@ / {
      inheader = 0
      # @@ -a,b +c,d @@
      split($3, plus, ",")
      lineno = substr(plus[1], 2) + 0
      next
    }
    /^\+/ {
      body = substr($0, 2)
      sub(/\r$/, "", body) # CRLF files
      kind_now = ""
      if (body ~ /^(<<<<<<<|>>>>>>>|\|\|\|\|\|\|\|)( |$)/) kind_now = "strong"
      else if (body ~ /^=======$/) kind_now = "sep"
      if (kind_now != "") {
        count++
        file_of[count] = current
        line_of[count] = lineno
        text[count] = body
        kind[count] = kind_now
        if (kind_now == "strong") strong[current] = 1
      }
      lineno++
      next
    }
    END {
      flush()
      if (found) {
        print "Resolve the conflict, remove the marker lines, and amend/recommit." > "/dev/stderr"
        exit 1
      }
    }
  '
