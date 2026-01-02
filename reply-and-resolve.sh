#!/bin/bash
set -e

PR_ID="PR_kwDOP2aaM867PmcC"
QUERIES_DIR="/Users/adsc/dev/ws/groovy-devtools/main/.agent/queries"

# Function to reply and resolve a thread
reply_and_resolve() {
    local thread_id="$1"
    local comment_id="$2"
    local message="$3"
    
    echo "Replying to thread $thread_id..."
    gh api graphql \
        -F pullRequestId="$PR_ID" \
        -F inReplyTo="$comment_id" \
        -F body="$message" \
        -f query="$(cat $QUERIES_DIR/reply-to-thread.graphql)" > /dev/null
    
    echo "Resolving thread $thread_id..."
    gh api graphql \
        -F threadId="$thread_id" \
        -f query="$(cat $QUERIES_DIR/resolve-review-thread.graphql)" > /dev/null
    
    echo "✓ Thread $thread_id resolved"
}

# Group 1: persist-credentials (54ac851)
reply_and_resolve "PRRT_kwDOP2aaM85nzjnh" "PRRC_kwDOP2aaM86eW2tM" "Fixed in \`54ac851\`."
reply_and_resolve "PRRT_kwDOP2aaM85nzjnp" "PRRC_kwDOP2aaM86eW2tV" "Fixed in \`54ac851\`."

# Group 2: Permissions - claude-code-review.yml pr:write
reply_and_resolve "PRRT_kwDOP2aaM85nzjjJ" "PRRC_kwDOP2aaM86eW2oO" "Fixed in \`12a47d6\`. Changed to \`pull-requests: write\` to enable \`gh pr comment\`."
reply_and_resolve "PRRT_kwDOP2aaM85nzjnw" "PRRC_kwDOP2aaM86eW2tc" "Fixed in \`12a47d6\`. Changed to \`pull-requests: write\`."

# Group 3: Permissions - claude.yml write permissions
reply_and_resolve "PRRT_kwDOP2aaM85nzjjL" "PRRC_kwDOP2aaM86eW2oP" "Fixed in \`12a47d6\`. Added \`contents: write\`, \`pull-requests: write\`, \`issues: write\` to enable Claude to create comments, branches, and commits."
reply_and_resolve "PRRT_kwDOP2aaM85nzjnj" "PRRC_kwDOP2aaM86eW2tP" "Fixed in \`12a47d6\`. Added write permissions."

# Group 4: Concurrency
reply_and_resolve "PRRT_kwDOP2aaM85nzjnW" "PRRC_kwDOP2aaM86eW2tA" "Fixed in \`12a47d6\`. Added concurrency group with \`cancel-in-progress: false\` to prevent duplicates while ensuring each review completes (avoids wasting API tokens)."
reply_and_resolve "PRRT_kwDOP2aaM85nzjn1" "PRRC_kwDOP2aaM86eW2ti" "Fixed in \`12a47d6\`. Added concurrency group with \`cancel-in-progress: false\`."

# Group 5: Fork security
reply_and_resolve "PRRT_kwDOP2aaM85nzjnm" "PRRC_kwDOP2aaM86eW2tS" "Fixed in \`12a47d6\`. Added author association check (OWNER, MEMBER, CONTRIBUTOR) to prevent untrusted fork PRs from running the workflow."

# Group 6: fetch-depth (REJECT with reasoning)
reply_and_resolve "PRRT_kwDOP2aaM85nzjug" "PRRC_kwDOP2aaM86eW21l" "Keeping \`fetch-depth: 1\` by design. For PR reviews, shallow clone is sufficient since \`gh pr diff\` provides full context without needing repository history. This optimizes for faster execution and lower bandwidth while maintaining review quality."
reply_and_resolve "PRRT_kwDOP2aaM85nzjuh" "PRRC_kwDOP2aaM86eW21m" "Keeping \`fetch-depth: 1\` by design. For PR reviews, shallow clone is sufficient since \`gh pr diff\` provides full context without needing repository history. This optimizes for faster execution and lower bandwidth."

echo ""
echo "✅ All 11 threads replied and resolved!"
