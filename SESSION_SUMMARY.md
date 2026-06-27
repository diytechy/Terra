# Session Summary: Dependency Management System Design

**Date:** 2026-06-19 to 2026-06-20  
**Task:** Design a reusable system for understanding and updating Terra dependencies, with special handling for stock releases vs diytechy Repsy forks

---

## What Was Accomplished

### Part 1: Immediate Task (2026-06-19)

**Before:** Terra was using `cloud-paper:2.0.0-SNAPSHOT` (local build only)  
**Problem:** Needed Paper 26.2 compatibility, upstream fix not released yet

**Solution:** Temporarily switched to diytechy fork on Repsy
- Added repo: `https://repo.repsy.io/mvn/diytechy/cloud-minecraft`
- Used version: `2.0.0-beta.16-diytechy`
- Also bumped Fabric and NeoForge cloud variants

**After Learning:** Discovered upstream released `cloud-paper:2.0.0-beta.16` today with the fix!

**Final Action:** Reverted to stock releases for all three
- `Bukkit.cloud`: `2.0.0-SNAPSHOT` → `2.0.0-beta.16` (stock)
- `Fabric.cloud`: `2.0.0-beta.16` → `2.0.0-beta.17` (stock)
- `NeoForge.cloud`: `2.0.0-beta.15` → `2.0.0-beta.17` (stock)
- Removed Repsy fork repo dependency entirely

---

### Part 2: Reusable System (2026-06-20)

Designed a three-layer system so this decision-making and checking process becomes **systematic, repeatable, and automated** for future dependency updates.

#### Layer 1: Reference & Decision-Making

**File:** `CLAUDE.md` (project root)

Contains:
- **Dependency Matrix** — Which platform (Bukkit/Fabric/NeoForge) uses what, with status
- **Decision Tree** — When to use stock vs fork (flowchart)
- **Known Issues Registry** — Issues encountered, when fixed, where fix was released
- **Update Workflow** — 5-step manual process with verification commands
- **Fork Configuration Guide** — When and how to use Repsy fork

**Design principle:** Read once, bookmark, reference as needed. Encodes institutional knowledge.

#### Layer 2: Automated Checking

**Tool A: Bash Script** `scripts/check-cloud-updates.sh`
- Queries Maven Central for latest versions of cloud-paper, cloud-fabric, cloud-neoforge
- Shows release version + last updated timestamp
- Optional: GitHub commits for cloud-paper
- Runnable without Gradle, in CI/CD, offline-friendly

**Tool B: Gradle Task** `checkCloudDependencies` (template in docs)
- Same checking capability, Gradle-integrated
- Can be chained with other tasks
- Template provided with full implementation

**Design principle:** Two complementary tools for different workflows (quick bash check vs integrated Gradle check).

#### Layer 3: Implementation

**Files to edit:**
- `buildSrc/src/main/kotlin/Versions.kt` — Version constants (always edit here)
- `buildSrc/src/main/kotlin/DependencyConfig.kt` — Maven repos (only edit if using fork)

**Design principle:** Minimal, intentional changes. Fork usage is explicit and flagged for removal.

---

## System Architecture

```
┌─────────────────────────────────────────┐
│  DECISION LAYER (CLAUDE.md)             │
│  ├─ Dependency matrix                   │
│  ├─ Decision tree                       │
│  └─ Known issues                        │
└──────────────┬──────────────────────────┘
               ↓
┌──────────────────────────────────────────────┐
│  CHECKING LAYER (Automation)                │
│  ├─ Bash script: check-cloud-updates.sh    │
│  └─ Gradle task: checkCloudDependencies    │
│     Query: Maven Central, GitHub, Repsy    │
└──────────────┬───────────────────────────────┘
               ↓
┌──────────────────────────────────────────┐
│  IMPLEMENTATION LAYER (Code)             │
│  ├─ Versions.kt (change versions)        │
│  └─ DependencyConfig.kt (repos if fork)  │
└──────────────────────────────────────────┘
```

---

## Files Created

### Reference Documentation

1. **CLAUDE.md** (2.4 KB)
   - Dependency matrix for three platforms
   - Decision tree for stock vs fork
   - Known issues registry
   - Step-by-step update workflow

2. **docs/QUICK_START_DEPENDENCIES.md** (3.5 KB)
   - 5-minute TL;DR
   - Common scenarios
   - Quick reference table

3. **docs/DEPENDENCY_UPDATES.md** (9.2 KB)
   - Tool setup instructions
   - Complete workflow with examples
   - Troubleshooting guide
   - CI/CD integration examples
   - Gradle task full implementation

4. **docs/DEPENDENCY_MANAGEMENT_SYSTEM.md** (10+ KB)
   - Architecture overview
   - Three-layer system explanation
   - Complete workflow walkthrough
   - Advanced fork scenarios
   - Monitoring and escalation

### Automation

5. **scripts/check-cloud-updates.sh** (2.5 KB)
   - Executable bash script
   - Queries Maven Central metadata
   - Shows versions + timestamps
   - Optional GitHub commit history

### Memory (for Future Conversations)

6. **memory/cloud_dependencies_update_process.md**
   - Captured system design
   - Decision tree
   - When fork is necessary
   - Workflow checklist

7. **memory/MEMORY.md**
   - Index of memory files
   - Cross-references

---

## Design Principles

### Learnings from This Session

1. **Cloud-minecraft is a monorepo with uneven release cadence**
   - cloud-paper: Slowest (platform-specific constraints)
   - cloud-fabric/neoforge: Run 1+ versions ahead
   - **Action:** Always check cloud-paper first

2. **Forks are temporary bridges**
   - diytechy fork carries pre-release fixes until upstream releases them
   - Fix released upstream → switch back to stock immediately
   - **Action:** Flag fork versions with "TEMP" comments

3. **Three sources of truth**
   - Maven Central: Official releases
   - GitHub Incendo/cloud-minecraft: Upstream PRs and commits
   - Repsy diytechy/cloud-minecraft: Pre-release versions when needed
   - **Action:** Query all three when deciding stock vs fork

4. **Decision tree beats one-off analysis**
   - "Does upstream have the fix?" → Repeatable question
   - "When was it released?" → Query Maven metadata
   - "Should we use fork?" → Flowchart answers it
   - **Action:** Encode into CLAUDE.md, reference every time

---

## How to Use This System

### For Next Cloud Dependency Update

1. **Check what's available:**
   ```bash
   ./scripts/check-cloud-updates.sh
   ```

2. **Read decision tree in CLAUDE.md** (~2 min)

3. **Update Versions.kt** with new version

4. **Verify build:**
   ```bash
   ./gradlew :platforms:bukkit:common:dependencies | grep cloud-paper
   ```

5. **Commit:** `Bump cloud-paper to X.Y.Z (fixes: <issue>)`

**Expected time:** 5-10 minutes (vs. 1-2 hours of research this time)

### For Different Dependency Type

System is general enough to apply to other platform-specific dependencies:
- Other Incendo projects
- Minecraft modding libraries
- Paper/Fabric/NeoForge-specific APIs

Adapt CLAUDE.md format for your specific dependency, reuse script pattern.

---

## What Happens Next

### User Can

- [ ] Run `./scripts/check-cloud-updates.sh` to see it working
- [ ] Read `CLAUDE.md` to understand the decision framework
- [ ] Add Gradle task to build.gradle.kts for Gradle integration
- [ ] Set up GitHub Actions cron for monthly monitoring
- [ ] Update future cloud dependencies using the workflow

### System Improvements (Future)

- [ ] CI/CD: Automatic PR creation when new releases detected
- [ ] CI/CD: Automatic fork removal when upstream catches up
- [ ] Gradle plugin: Version compatibility analyzer
- [ ] CLAUDE.md: Auto-updated from GitHub releases (webhook)

---

## Lessons Learned

**What worked well:**
- Multiple tools for different contexts (bash for quick, Gradle for integration)
- Decision tree prevents analysis paralysis
- Known issues registry makes patterns visible
- Explicit "TEMP" flags prevent fork creep

**What to watch:**
- Fork versions can become stale if upstream changes API
- Monorepo release cadences are hard to predict
- GitHub API rate limits (optional auth recommended)
- Network issues querying Maven Central (script has timeouts)

---

## Related Files in This Project

- `CLAUDE.md` — Reference (bookmark this!)
- `docs/QUICK_START_DEPENDENCIES.md` — First read
- `docs/DEPENDENCY_UPDATES.md` — Full guide
- `docs/DEPENDENCY_MANAGEMENT_SYSTEM.md` — System design deep-dive
- `scripts/check-cloud-updates.sh` — Run this regularly
- `~/.claude/projects/c--Projects-Terra/memory/` — For future sessions

---

## Summary

This session delivered:

1. **Immediate fix:** Updated Terra's cloud dependencies from snapshot → stock releases (Bukkit, Fabric, NeoForge)

2. **Reusable system:** Three-layer architecture for future updates
   - **Reference:** CLAUDE.md (decisions, known issues, workflow)
   - **Checking:** Scripts + Gradle task (automation)
   - **Implementation:** Versions.kt + DependencyConfig.kt (minimal changes)

3. **Extensible design:** Applicable to other dependency decisions beyond cloud-minecraft

**Result:** Future cloud dependency updates should take 5-10 minutes instead of 1-2 hours of research.
