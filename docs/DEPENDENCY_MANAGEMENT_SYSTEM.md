# Terra Dependency Management System

## Overview

This document describes the complete system for understanding, monitoring, and updating Terra's dependencies—particularly the cloud-minecraft platform modules that sometimes require custom forks published to Repsy.

**Problem it solves:**
- Terra often needs bleeding-edge fixes before they're released upstream
- When upstream releases a fix, know whether to stay on fork or switch to stock
- Systematically check which versions are available and relevant
- Make informed decisions: stock release vs diytechy fork vs wait for upstream

---

## Architecture: Three Layers

```
┌──────────────────────────────────────────────────────────────┐
│                    Decision & Reference                      │
│  CLAUDE.md: Dependency matrix, decision tree, known issues   │
└──────────────────────────────────────────────────────────────┘
                             ↑
┌──────────────────────────────────────────────────────────────┐
│                  Automated Checking                          │
│  Gradle task (checkCloudDependencies) + Bash script          │
│  Query Maven Central, GitHub, Repsy for latest versions      │
└──────────────────────────────────────────────────────────────┘
                             ↑
┌──────────────────────────────────────────────────────────────┐
│               Implementation: Actual Versions                │
│  Versions.kt: Version constants                              │
│  DependencyConfig.kt: Repository configuration (fork only)   │
└──────────────────────────────────────────────────────────────┘
```

---

## Layer 1: Decision & Reference (CLAUDE.md)

**File:** `CLAUDE.md` in project root

**Contents:**
1. **Dependency Matrix** — Which platform uses what
   - Bukkit → cloud-paper (Repsy fork required? Track status)
   - Fabric → cloud-fabric (Stock usually)
   - NeoForge → cloud-neoforge (Stock usually)

2. **Decision Tree** — Stock vs Fork
   ```
   New release lands
   → Is it relevant to our MC version? (YES/NO)
   → Is the issue fixed? (YES/NO/IN_FLIGHT)
   → Use: (Stock | Fork-with-Repsy | Wait)
   ```

3. **Known Issues Registry**
   - Issue: "Paper 26.2 CraftItemStack incompatibility"
   - Fixed in: PR #158 (Incendo/cloud-minecraft)
   - Released as: cloud-paper 2.0.0-beta.16
   - Fork had it as: 2.0.0-beta.16-diytechy
   - Status: ✓ Stock now available, fork no longer needed

4. **Repositories Configuration**
   ```kotlin
   // DependencyConfig.kt: Only add when using fork
   maven("https://repo.repsy.io/mvn/diytechy/cloud-minecraft")
   ```

5. **Update Workflow** — Step-by-step instructions

---

## Layer 2: Automated Checking

### Option A: Bash Script (Recommended for Quick Checks)

**File:** `scripts/check-cloud-updates.sh`

**What it does:**
- Queries Maven Central metadata for cloud-paper, cloud-fabric, cloud-neoforge
- Shows latest release version and timestamp
- Optional: Recent upstream commits

**Usage:**
```bash
./scripts/check-cloud-updates.sh              # All artifacts
./scripts/check-cloud-updates.sh paper --commits  # With GitHub commits
```

**Output:**
```
[Bukkit] cloud-paper
  Release: 2.0.0-beta.16
  Updated: 2026-06-19

[Fabric] cloud-fabric
  Release: 2.0.0-beta.17
  Updated: 2026-06-19
```

**Advantages:**
- No Gradle needed
- Runs offline if already cached
- Easy to customize
- Works in CI/CD via cron

### Option B: Gradle Task (Full Workflow)

**Add to root build.gradle.kts:**

```kotlin
tasks.register("checkCloudDependencies") {
    group = "verification"
    description = "Check for available cloud-minecraft dependency updates"
    // See docs/DEPENDENCY_UPDATES.md for full implementation
}
```

**Usage:**
```bash
./gradlew checkCloudDependencies
./gradlew checkCloudDependencies -Pverbose=true  # With GitHub commits
```

**Advantages:**
- Integrates with build system
- Familiar to Gradle-using teams
- Can be chained with other tasks
- Better error handling

---

## Layer 3: Implementation

### Edit When Updating

**Versions.kt:** Only file to change for stock releases
```kotlin
object Bukkit {
    const val cloud = "2.0.0-beta.16"  // ← Update this
}
object Fabric {
    const val cloud = "2.0.0-beta.17"  // ← Update this
}
object NeoForge {
    const val cloud = "2.0.0-beta.17"  // ← Update this
}
```

**DependencyConfig.kt:** Only when using fork
```kotlin
repositories {
    // Only add if using -diytechy version:
    maven("https://repo.repsy.io/mvn/diytechy/cloud-minecraft") {
        name = "RepsyCloudMinecraft"
    }
}
```

### Do NOT Edit

- Don't hardcode versions in `.gradle.kts` files (use Versions.kt)
- Don't use mavenLocal() for production builds (fork only for development)
- Don't keep fork dependencies after upstream fix is released

---

## Complete Workflow: From New Release to Deployed

### Trigger: New cloud-minecraft Release

1. **Monitor** → Check `https://github.com/Incendo/cloud-minecraft/releases`
   - Or run: `./scripts/check-cloud-updates.sh --commits`
   - Or schedule: `./gradlew checkCloudDependencies` via cron

2. **Evaluate** → Is it relevant?
   - Read CLAUDE.md: Does this version target our MC version?
   - Example: Is cloud-paper beta.17 relevant to Paper 26.1? Yes, beta.16 was.

3. **Decide** → Stock or fork?
   - Check decision tree in CLAUDE.md
   - Example: cloud-paper beta.16 includes Paper 26.2 fix → Use stock

4. **Update** → Versions.kt only
   ```kotlin
   const val cloud = "2.0.0-beta.16"  // Was beta.15
   ```

5. **Verify** → Build resolves
   ```bash
   ./gradlew :platforms:bukkit:common:dependencies | grep cloud-paper
   ```

6. **Commit** → `Bump cloud-paper to 2.0.0-beta.16 (includes Paper 26.2 fix)`

---

## Advanced: When Fork is Needed

### Scenario: Pre-Release Fix

Paper 26.2 has CraftItemStack incompatibility. Upstream PR #158 is merged but not released.

**Timeline:**
- 2026-06-13: diytechy fork gets the fix (from mainline or cherry-pick)
- 2026-06-16: diytechy publishes to Repsy as `2.0.0-beta.16-diytechy`
- 2026-06-19: Upstream officially releases `2.0.0-beta.16` with the fix
- 2026-06-20: Terra switches from fork to stock

**Terra's steps:**

```kotlin
// 2026-06-16: Use fork (upstream not released yet)
// DependencyConfig.kt
maven("https://repo.repsy.io/mvn/diytechy/cloud-minecraft") {
    name = "RepsyCloudMinecraft"
}

// Versions.kt
const val cloud = "2.0.0-beta.16-diytechy"  // TEMP: Remove when upstream releases 2.0.0-beta.16
```

```kotlin
// 2026-06-20: Switch to stock (upstream released the fix)
// DependencyConfig.kt - REMOVE the Repsy repo

// Versions.kt
const val cloud = "2.0.0-beta.16"  // Stock release with the fix
```

**Key practice:** Flag fork usage with "TEMP" comment + planned removal version.

---

## Monitoring & Escalation

### Monthly Check
```bash
./scripts/check-cloud-updates.sh
```
→ Alert if version diff from current > 2 betas

### Quarterly Review
- Check `CLAUDE.md` dependency matrix
- Update "Known Issues" if any fork versions are stale
- Consider removing obsolete entries

### Escalation Path
1. **Stock has fix?** → Use stock
2. **Fork has fix?** → Use fork temporarily
3. **Neither has fix?** → File GitHub issue upstream, wait, or contribute PR

---

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Monitor Cloud Dependencies
on:
  schedule:
    - cron: '0 9 * * 1'  # Weekly Monday

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Check cloud-minecraft updates
        run: scripts/check-cloud-updates.sh --commits
      - name: File issue if updates available
        # Parse output, file issue if new versions found
```

### CI Build Verification
Always run on PR:
```bash
./gradlew build -x test  # Verify dependencies resolve
```

---

## Related Documentation

| File | Purpose | Audience |
|------|---------|----------|
| **CLAUDE.md** | Reference + decision tree | Anyone updating deps |
| **docs/QUICK_START_DEPENDENCIES.md** | 5-minute guide | Impatient developers |
| **docs/DEPENDENCY_UPDATES.md** | Full workflow + troubleshooting | Deep dives |
| **docs/DEPENDENCY_MANAGEMENT_SYSTEM.md** (this file) | Architecture overview | System designers |
| **scripts/check-cloud-updates.sh** | Executable checker | Automation |
| **memory/cloud_dependencies_update_process.md** | Future conversation context | Future Claude |

---

## FAQ

**Q: When do I need the fork?**
A: Only when the fix you need isn't in the stock release but is in the diytechy fork. Always check stock first.

**Q: How long do fork versions last?**
A: Temporary, usually 1-4 weeks until upstream releases. Flag with "TEMP" comments.

**Q: What if upstream never releases my fix?**
A: Document in CLAUDE.md why fork is needed. Keep fork indefinitely or find workaround. Rare case.

**Q: Can I use mavenLocal() for development?**
A: Yes, but only for local builds, never production CI. Use Repsy fork instead for shared builds.

**Q: How do I know if I should update?**
A: Check CLAUDE.md decision tree: Does release target our MC version? Does it include a fix we need?

**Q: What if build breaks after updating?**
A: "Could not find artifact" → Check Maven Central has version. "Compile error" → May need code changes for new API.

---

## Summary

This system provides **three complementary tools**:

1. **CLAUDE.md** — Brain of the system (decisions, policies)
2. **Scripts + Gradle task** — Eyes of the system (monitoring)
3. **Versions.kt + DependencyConfig.kt** — Hands of the system (implementation)

Together they enable **informed, reproducible, low-friction dependency updates**—whether using stock releases or temporary forks to stay ahead of upstream.

The key insight: **Forks are temporary bridges**, not long-term dependencies. This system treats them as such.
