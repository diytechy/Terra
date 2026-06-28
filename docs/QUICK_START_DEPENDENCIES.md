# Quick Start: Updating Terra Dependencies

## TL;DR

1. **Check for updates:**
   ```bash
   ./scripts/check-cloud-updates.sh
   ```

2. **Read decision tree:**
   - Open `CLAUDE.md` → "Cloud Minecraft Dependencies" section
   - Follow the flow chart: "Decision Tree: Stock vs Fork"

3. **Update Versions.kt:**
   ```kotlin
   // buildSrc/src/main/kotlin/Versions.kt
   object Bukkit {
       const val cloud = "2.0.0-beta.16"  // ← Change this version
   }
   ```

4. **Verify:**
   ```bash
   ./gradlew :platforms:bukkit:common:dependencies --configuration runtimeClasspath | grep cloud-paper
   ```

---

## When You Need the Fork

Add this **only if** the issue you need is **not** in the stock release:

**In DependencyConfig.kt:**
```kotlin
maven("https://repo.repsy.io/mvn/diytechy/cloud-minecraft") {
    name = "RepsyCloudMinecraft"
}
```

**In Versions.kt:**
```kotlin
const val cloud = "2.0.0-beta.16-diytechy"  // TEMP: Remove when upstream releases this
```

Then delete the fork repo and version suffix once upstream releases the fix.

---

## The System

Three tools work together:

| Tool | Command | When |
|------|---------|------|
| Bash script | `./scripts/check-cloud-updates.sh` | Quick check for new releases |
| CLAUDE.md | Read `CLAUDE.md` | Understanding why a fork exists |
| Docs | Read `docs/DEPENDENCY_UPDATES.md` | Full workflow and troubleshooting |

---

## Example: Real Session

**Scenario:** Paper 26.2 was released, need to update cloud-paper

**Session:**
```bash
# Step 1: Check what's available
$ ./scripts/check-cloud-updates.sh
[Bukkit] cloud-paper
  Release: 2.0.0-beta.16
  Updated: 2026-06-19

# Step 2: Read CLAUDE.md to understand context
# (It says cloud-paper beta.16 includes the Paper 26.2 fix from PR #158)

# Step 3: Update version
# Edit buildSrc/src/main/kotlin/Versions.kt
# Change: const val cloud = "2.0.0-beta.15" → "2.0.0-beta.16"

# Step 4: Verify build works
$ ./gradlew :platforms:bukkit:common:dependencies | grep cloud-paper
\--- org.incendo:cloud-paper:2.0.0-beta.16

# Done!
```

---

## Files

- **CLAUDE.md** — Full reference (read first time, then bookmarked)
- **docs/DEPENDENCY_UPDATES.md** — Detailed workflow and troubleshooting
- **scripts/check-cloud-updates.sh** — Executable check script
- **buildSrc/src/main/kotlin/Versions.kt** — Version constants (what you edit)
- **buildSrc/src/main/kotlin/DependencyConfig.kt** — Repos (only edit if using fork)

---

## Red Flags

🚩 **Don't do this:**
- Manually copy `.jar` files to local maven repo
- Use `mavenLocal()` without commenting why (temporary only)
- Leave `-diytechy` suffix versions without "TEMP" comments

✅ **Do this:**
- Use Maven Central when possible (stock)
- Use Repsy fork only when stock doesn't have the fix
- Flag fork usage with comments explaining why and planned removal
- Delete fork repo once upstream releases the fix

---

## Next: Monitoring

Want automatic alerts for new releases?

**Set up GitHub Actions** to run `scripts/check-cloud-updates.sh --commits` weekly and file an issue if new releases are found.

See `docs/DEPENDENCY_UPDATES.md` → "Integration with CI/CD" for the example workflow.

---

## Questions?

- **"Is version X available?"** → Run the bash script
- **"Should I use stock or fork?"** → Read CLAUDE.md decision tree
- **"Why does fork version exist?"** → Read CLAUDE.md dependency matrix
- **"How do I remove a fork?"** → Edit Versions.kt, delete fork repo line, verify build
