#!/bin/bash
# Script to download Jenkins plugin JARs for integration testing
# Uses gh CLI to get latest release versions

set -e

CACHE_DIR="$HOME/.groovy-lsp/cache/test-plugins"
mkdir -p "$CACHE_DIR"

echo "=== Jenkins Plugin Download Script ==="
echo "Target directory: $CACHE_DIR"
echo ""

# Plugin definitions: name|github-repo|maven-group|maven-artifact
# Maven coordinates are needed because Jenkins repo uses different paths
PLUGINS=(
    "kubernetes|kubernetes-plugin|org.csanchez.jenkins.plugins|kubernetes"
    "credentials-binding|credentials-binding-plugin|org.jenkins-ci.plugins|credentials-binding"
    "workflow-basic-steps|workflow-basic-steps-plugin|org.jenkins-ci.plugins.workflow|workflow-basic-steps"
    "workflow-durable-task-step|workflow-durable-task-step-plugin|org.jenkins-ci.plugins.workflow|workflow-durable-task-step"
    "workflow-scm-step|workflow-scm-step-plugin|org.jenkins-ci.plugins.workflow|workflow-scm-step"
    "workflow-cps|workflow-cps-plugin|org.jenkins-ci.plugins.workflow|workflow-cps"
    "docker-workflow|docker-workflow-plugin|org.jenkins-ci.plugins|docker-workflow"
    "pipeline-stage-step|pipeline-stage-step-plugin|org.jenkins-ci.plugins|pipeline-stage-step"
    "git|git-plugin|org.jenkins-ci.plugins|git"
    "ssh-agent|ssh-agent-plugin|org.jenkins-ci.plugins|ssh-agent"
    "pipeline-utility-steps|pipeline-utility-steps-plugin|org.jenkins-ci.plugins|pipeline-utility-steps"
)

download_plugin() {
    local name="$1"
    local repo="$2"
    local group="$3"
    local artifact="$4"
    
    local jar_file="$CACHE_DIR/${name}.jar"
    
    # Check if already exists and is valid
    if [ -f "$jar_file" ] && file "$jar_file" | grep -q "Zip archive"; then
        echo "✓ $name - already cached"
        return 0
    fi
    
    echo -n "Downloading $name... "
    
    # Get latest release version
    local version
    version=$(gh release list --repo "jenkinsci/$repo" --limit 1 --json tagName -q '.[0].tagName' 2>/dev/null)
    
    if [ -z "$version" ]; then
        echo "⚠ Failed to get version for $name"
        return 1
    fi
    
    # Clean version (remove prefixes like 'git-')
    version="${version#${artifact}-}"
    
    # Construct Maven URL
    local group_path="${group//./\/}"
    local url="https://repo.jenkins-ci.org/releases/${group_path}/${artifact}/${version}/${artifact}-${version}.jar"
    
    # Download
    if curl -fsSL -o "$jar_file" "$url" 2>/dev/null; then
        if file "$jar_file" | grep -q "Zip archive"; then
            local size=$(ls -lh "$jar_file" | awk '{print $5}')
            echo "✓ ($version, $size)"
        else
            echo "⚠ Invalid JAR - got $(file -b "$jar_file")"
            rm -f "$jar_file"
            return 1
        fi
    else
        echo "⚠ Download failed"
        rm -f "$jar_file"
        return 1
    fi
}

echo "Downloading plugins..."
echo ""

success_count=0
fail_count=0

for plugin in "${PLUGINS[@]}"; do
    IFS='|' read -r name repo group artifact <<< "$plugin"
    if download_plugin "$name" "$repo" "$group" "$artifact"; then
        ((success_count++))
    else
        ((fail_count++))
    fi
done

echo ""
echo "=== Summary ==="
echo "Success: $success_count"
echo "Failed:  $fail_count"
echo ""
echo "Plugins ready in: $CACHE_DIR"
ls -la "$CACHE_DIR"
