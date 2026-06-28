# Dependency Update Strategy for Terra

This guide documents the complete system for understanding and updating Terra's dependencies, particularly the cloud-minecraft platform modules which sometimes require custom forks.

## Quick Reference

**Three tools work together:**

1. **CLAUDE.md** — Reference documentation with decision tree, known issues, manual workflow
2. **Gradle task** — Automated checking for new releases
3. **Bash script** — Quick manual checking without full Gradle setup

---

## Tool 1: CLAUDE.md

**Location:** `CLAUDE.md` in project root

**What it contains:**
- Dependency matrix (which platform uses what)
- Known compatibility issues and when they were fixed
- Decision tree: when to use stock vs diytechy fork
- Step-by-step update workflow
- Repsy fork configuration

**Use when:** Making a dependency decision or understanding the history

---

## Tool 2: Gradle Task `checkCloudDependencies`

**Location:** Root `build.gradle.kts` (add to end of file)

**Setup:** Add this to the root build.gradle.kts:

```kotlin
tasks.register("checkCloudDependencies") {
    group = "verification"
    description = "Check for available cloud-minecraft dependency updates"

    doLast {
        val verbose = project.hasProperty("verbose")
        val artifacts = mapOf(
            "cloud-paper" to "Bukkit",
            "cloud-fabric" to "Fabric",
            "cloud-neoforge" to "NeoForge"
        )

        println("\n=== Cloud Minecraft Dependency Check ===\n")

        for ((artifact, platform) in artifacts) {
            print("Checking $platform ($artifact)... ")
            try {
                val metadataUrl = "https://repo.maven.apache.org/maven2/org/incendo/$artifact/maven-metadata.xml"
                val metadata = java.net.URL(metadataUrl).readText()
                
                val release = metadata
                    .substringAfter("<release>")
                    .substringBefore("</release>")
                
                val updated = metadata
                    .substringAfter("<lastUpdated>")
                    .substringBefore("</lastUpdated>")
                
                val formattedDate = if (updated.length == 14) {
                    "${updated.substring(0, 4)}-${updated.substring(4, 6)}-${updated.substring(6, 8)}"
                } else {
                    updated
                }

                println("\n  Release: $release")
                println("  Updated: $formattedDate")
                println()
            } catch (e: Exception) {
                println("⚠ ${e.message}\n")
            }
        }

        if (verbose) {
            println("=== Recent Upstream Commits (cloud-paper) ===\n")
            try {
                val commits = java.net.URL(
                    "https://api.github.com/repos/Incendo/cloud-minecraft/commits?path=cloud-paper&per_page=5"
                ).readText()
                
                // Simple regex extraction (avoid JSON parsing)
                val regex = "\"message\":\"([^\"]+)\".*?\"date\":\"([^T]+)".toRegex(RegexOption.DOT_MATCHES_ALL)
                regex.findAll(commits).take(5).forEach { match ->
                    val (msg, date) = match.destructured
                    println("  $date | ${msg.take(60)}")
                }
            } catch (e: Exception) {
                println("  ⚠ Could not fetch commits: ${e.message}")
            }
        }

        println("\nFor decision tree and full workflow, see CLAUDE.md\n")
    }
}
```

**Usage:**

```bash
# Check all three artifacts
./gradlew checkCloudDependencies

# With recent commit history
./gradlew checkCloudDependencies -Pverbose=true

# Quick check if already downloaded
./gradlew checkCloudDependencies --offline
```

**Output:**
```
=== Cloud Minecraft Dependency Check ===

Checking Bukkit (cloud-paper)...
  Release: 2.0.0-beta.16
  Updated: 2026-06-19

Checking Fabric (cloud-fabric)...
  Release: 2.0.0-beta.17
  Updated: 2026-06-19
```

---

## Tool 3: Bash Script `check-cloud-updates.sh`

**Location:** `scripts/check-cloud-updates.sh`

**Setup:** Already created. Make executable:

```bash
chmod +x scripts/check-cloud-updates.sh
```

**Usage:**

```bash
# All artifacts
./scripts/check-cloud-updates.sh

# Single artifact
./scripts/check-cloud-updates.sh paper
./scripts/check-cloud-updates.sh fabric
./scripts/check-cloud-updates.sh neoforge

# With recent commit history
./scripts/check-cloud-updates.sh paper --commits
```

**Advantages over Gradle task:**
- Runs without full Gradle setup
- Works offline (just shows cached values or network-only results)
- Simpler shell script (easy to modify)

---

## Workflow: Updating Cloud Dependencies

### Step 1: Check for Updates (Monthly)

```bash
# Quick check
./scripts/check-cloud-updates.sh

# Or with Gradle (shows GitHub commits too)
./gradlew checkCloudDependencies -Pverbose=true
```

### Step 2: Read Decision Tree (in CLAUDE.md)

When you see a new release:

1. **Does it affect your MC target?** (Check Versions.kt: `object Mod { const val minecraft = ... }`)
2. **Is there a known issue?** (Check CLAUDE.md dependency matrix)
3. **Is it fixed in this release?** (Read GitHub release notes)
   - If YES → Use stock
   - If NO → Check diytechy fork
   - If NOT in fork either → Escalate upstream

### Step 3: Update Versions.kt

Edit `buildSrc/src/main/kotlin/Versions.kt`:

```kotlin
object Bukkit {
    // Before:
    const val cloud = "2.0.0-beta.15"
    
    // After:
    const val cloud = "2.0.0-beta.16"
}
```

### Step 4: If Using Fork, Update DependencyConfig.kt

Only needed if using `-diytechy` suffix version:

```kotlin
// buildSrc/src/main/kotlin/DependencyConfig.kt
repositories {
    // ... existing repos ...
    maven("https://repo.repsy.io/mvn/diytechy/cloud-minecraft") {
        name = "RepsyCloudMinecraft"
    }
}
```

### Step 5: Verify Build

```bash
# Test dependency resolution
./gradlew :platforms:bukkit:common:dependencies --configuration runtimeClasspath | grep -A5 "cloud-paper"

# Full verification
./gradlew build -x test
```

---

## When to Use Diytechy Fork (Repsy)

Add custom version **only when**:

1. **Upstream bug is documented** but not released
   - Example: Paper 26.2 CraftItemStack incompatibility
   - Example fix: https://github.com/Incendo/cloud-minecraft/pull/158 (merged but not released)

2. **Fork carries the fix** as pre-release version
   - Example: `2.0.0-beta.16-diytechy` (fork was released first)
   - Original: `2.0.0-beta.15` (stock at that time)

3. **You flag it for removal**
   ```kotlin
   const val cloud = "2.0.0-beta.16-diytechy"  // TEMP: Remove when upstream releases 2.0.0-beta.16
   ```

**When to remove:**
- When stock Maven Central has the fix
- Then: Delete `-diytechy` suffix, remove Repsy repo from DependencyConfig.kt
- Example: `2.0.0-beta.16-diytechy` → `2.0.0-beta.16` once released

---

## Special Cases

### Case 1: Pre-Release Minecraft Version

If Terra targets Paper 26.2 but cloud-minecraft only supports 26.1:

1. Check diytechy fork for branch or pre-release version
2. If available, use fork temporarily
3. Monitor upstream for official support
4. Switch to stock when released

### Case 2: Unmerged Upstream PR

If a fix is in PR but not released:

1. Check PR for status (draft, approved, blocked?)
2. If approved but not released → will land in next release
3. If blocked → consider interim fork or alternative
4. File issue if no workaround exists

### Case 3: Fork Fix Never Upstreamed

If diytechy fork carries a fix that upstream rejects:

1. Document in CLAUDE.md why (under "Special Patterns")
2. Keep fork usage indefinitely
3. Monitor for alternative solutions
4. Consider contributing to alternative project

---

## Troubleshooting

**Problem: "Could not find org.incendo:cloud-paper:X"**

**Solution:**
1. Check version exists on Maven Central: `curl https://repo.maven.apache.org/maven2/org/incendo/cloud-paper/maven-metadata.xml`
2. If using fork version (`-diytechy` suffix):
   - Verify Repsy repo is in DependencyConfig.kt
   - Check Repsy has the version: `curl https://repo.repsy.io/mvn/diytechy/cloud-minecraft/org/incendo/cloud-paper/`
3. Run `./gradlew --refresh-dependencies` to clear cache

**Problem: "Gradle task checkCloudDependencies not found"**

**Solution:**
- Add the task to root build.gradle.kts (see Tool 2 section)
- Or use bash script instead: `./scripts/check-cloud-updates.sh`

---

## Integration with CI/CD

To automatically alert on new dependency releases:

**GitHub Actions example:**
```yaml
name: Check Dependency Updates
on:
  schedule:
    - cron: '0 9 * * 1'  # Weekly Monday morning
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Check cloud dependencies
        run: ./scripts/check-cloud-updates.sh --commits
```

Then open an issue if new releases are found.

---

## Summary

| Tool | Purpose | Frequency | Effort |
|------|---------|-----------|--------|
| CLAUDE.md | Reference + decision making | On-demand | Read once |
| Gradle task | Automated checking | Monthly | `./gradlew checkCloudDependencies` |
| Bash script | Manual quick check | On-demand | `./scripts/check-cloud-updates.sh` |

**Key insight:** This system handles both **stock releases** (just update version constant) and **fork scenarios** (where Terra needs temporary pre-release fixes until upstream catches up).
