# Troubleshooting

This guide contains common issues and solutions for building and developing the Groovy LSP.

## Build Failures

### Gradle Configuration Cache Issues

**Symptoms:**
- Build fails with `Spotless error! All target files must be within the project dir.`
- Error message shows path mismatch (e.g., `/workspaces/...` vs `/Users/...`).
- Occurs after switching environments (e.g., from Codespaces/Docker to local machine).

**Cause:**
Gradle's configuration cache may retain environment-specific paths (like the project root directory) from a previous build in a different environment. When you run the build in a new environment, these cached paths are invalid.

**Solution:**

1. **Stop the Gradle Daemon** to clear in-memory cache:
   ```bash
   ./gradlew --stop
   ```

2. **Clean build artifacts** and the local `.gradle` cache:
   ```bash
   rm -rf .gradle
   find . -name "build" -type d -prune -exec rm -rf {} +
   ```

3. **Run build with configuration cache disabled** (once) to force a refresh:
   ```bash
   ./gradlew build --no-configuration-cache
   ```

4. **Resume normal building**:
   ```bash
   make build
   ```

### Lint/Formatting Issues

**Symptoms:**
- Build fails with `Spotless error!` or `Detekt error`.

**Solution:**
Run the auto-formatter to fix most issues automatically:
```bash
make format
```

