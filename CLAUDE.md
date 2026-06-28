# Terra Dependency Management

## Cloud Minecraft Dependencies

Terra uses the [Incendo/cloud-minecraft](https://github.com/Incendo/cloud-minecraft) command framework across multiple platforms. These dependencies are managed with special care because Terra often needs fixes before they're available in official releases.

### Dependency Matrix

| Platform  | Artifact         | Version Source | Current Issue                | Repsy Fork Required |
|-----------|------------------|-----------------|------------------------------|---------------------|
| Bukkit    | cloud-paper      | Maven Central   | Paper 26.2 CraftItemStack    | No (fixed in beta.16) |
| Fabric    | cloud-fabric     | Maven Central   | None currently known         | No                  |
| NeoForge  | cloud-neoforge   | Maven Central   | None currently known         | No                  |
| Core API  | cloud-core       | Maven Central   | N/A (stable release)         | No                  |

### Version Schema

The cloud-minecraft monorepo publishes multiple artifacts with **coordinated but independent version numbers**:

- **cloud-paper**: Lags behind (e.g., beta.16 while fabric/neoforge at beta.17)
  - Paper-specific fixes gate releases
  - Most likely to need custom patches
  
- **cloud-fabric** & **cloud-neoforge**: Run 1+ versions ahead
  - Fewer platform-specific constraints
  - Newer fixes available sooner
  
- **cloud-core**: Separate stable channel (e.g., 2.0.0), not part of beta wave

### When to Use the diytechy Fork (Repsy)

The diytechy fork at `https://repo.repsy.io/mvn/diytechy/cloud-minecraft` exists for **temporary compatibility gaps**. Use it when:

1. **Upstream bug affects Terra** and no fix is released yet
   - Example: Paper 26.2 CraftItemStack incompatibility (fixed in cloud-paper 2.0.0-beta.16)
   - Terra's diytechy fork carried this fix as `2.0.0-beta.16-diytechy` until the upstream release

2. **Upstream release timeline misaligns with Terra release**
   - If Terra needs to ship before upstream beta lands, fork carries interim fix

3. **Upstream rejects the fix** (rare)
   - If maintainer declines PR, fork is permanent solution

### Decision Tree: Stock vs Fork

```
New Minecraft version lands (e.g., Paper 26.2)
├─ Does cloud-minecraft repo have related commit/PR?
│  ├─ YES → Is it merged?
│  │  ├─ YES → Is it released to Maven Central?
│  │  │  ├─ YES → Use stock, fetch latest version
│  │  │  └─ NO → Check diytechy fork, likely has pre-release version
│  │  └─ NO → Check if PR is active; if blocked, escalate
│  └─ NO → Check diytechy fork for unreported fixes
│
└─ Result: Use (stock | fork-with-repsy-repo | wait-for-upstream)
```

### How to Update Cloud Dependencies

#### Quick Check: Latest Versions

```bash
# Maven Central latest for each artifact
curl -s "https://repo.maven.apache.org/maven2/org/incendo/cloud-paper/maven-metadata.xml" | grep -o '<release>[^<]*</release>'
curl -s "https://repo.maven.apache.org/maven2/org/incendo/cloud-fabric/maven-metadata.xml" | grep -o '<release>[^<]*</release>'
curl -s "https://repo.maven.apache.org/maven2/org/incendo/cloud-neoforge/maven-metadata.xml" | grep -o '<release>[^<]*</release>'

# diytechy fork on Repsy (if needed)
curl -s "https://repo.repsy.io/mvn/diytechy/cloud-minecraft/org/incendo/cloud-paper/" | grep -o 'href="[^"]*diytechy[^"]*"'
```

#### Check Upstream for Compatibility Fixes

```bash
# Recent commits affecting cloud-paper (Paper compatibility)
curl -s "https://api.github.com/repos/Incendo/cloud-minecraft/commits?path=cloud-paper&per_page=30" | \
  python3 -c "import json,sys; [print(c['commit']['author']['date'], c['commit']['message'].split('\n')[0]) for c in json.load(sys.stdin)]"

# Search for specific fix (e.g., CraftItemStack)
curl -s "https://api.github.com/search/issues?q=repo:Incendo/cloud-minecraft+CraftItemStack"
```

#### Repositories to Add (if using fork)

In [buildSrc/src/main/kotlin/DependencyConfig.kt](buildSrc/src/main/kotlin/DependencyConfig.kt):

```kotlin
maven("https://repo.repsy.io/mvn/diytechy/cloud-minecraft") {
    name = "RepsyCloudMinecraft"
}
```

Add this **before** mavenCentral() so it resolves as a fallback.

### Update Workflow

1. **Monitor**: Check `https://github.com/Incendo/cloud-minecraft/releases` monthly
   - Focus on cloud-paper releases (most likely to be relevant to Terra)
   - Read release notes for Paper/Bukkit-specific fixes

2. **Evaluate**: When a new release lands
   ```
   a. What's the fix? (e.g., "Handle Paper 26.2 shift")
   b. Do we need it? (Does Terra target that MC version?)
   c. Is it already available? (Check maven-metadata.xml)
   ```

3. **Test**: Before updating
   ```bash
   # Temporarily edit Versions.kt with new version
   ./gradlew :platforms:bukkit:common:dependencies
   # Verify it resolves (no "Could not find" errors)
   ```

4. **Update Versions.kt**: Only the version constant
   - Do NOT add/remove repos unless switching from stock ↔ fork
   - Stock: no Repsy repo needed
   - Fork: add Repsy repo in DependencyConfig.kt

5. **Verify Build**: Run full build or at least platform dependencies
   ```bash
   ./gradlew build -x test  # Validate without running tests
   ```

---

## Special Patterns: When Forks Are Necessary

### Scenario: Unmerged or Rejected Upstream Fix

If diytechy carries a fix that upstream rejects:

1. **Document the reason** in this file (under "Dependency Matrix" decision column)
2. **Monitor upstream** for alternative solutions
3. **Maintain fork** until problem is resolved upstream or in an alternative way

### Scenario: Pre-Release Compatibility

If Terra needs to support a MC version before cloud-minecraft officially supports it:

1. diytechy fork carries interim fix
2. Add Repsy repo, use `-diytechy` suffixed version
3. **Plan for sunset**: Remove fork usage when upstream releases official fix
4. Include "TEMP" comment in Versions.kt to flag for removal

Example:
```kotlin
const val cloud = "2.0.0-beta.16-diytechy"  // TEMP: Remove when upstream cloud-paper beta.16 is released
```

---

## Related Files

- [buildSrc/src/main/kotlin/Versions.kt](buildSrc/src/main/kotlin/Versions.kt) — All version constants
- [buildSrc/src/main/kotlin/DependencyConfig.kt](buildSrc/src/main/kotlin/DependencyConfig.kt) — Maven repository configuration
- GitHub Issues: Use label `dependencies:cloud` if tracking specific cloud-minecraft blockers

---

## Future: Dependency Monitoring Skill

A `/check-cloud-updates` skill could automate steps 1–3:
- Query Maven Central for new releases
- Fetch recent commits from GitHub Incendo/cloud-minecraft
- Cross-reference with known Terra compatibility targets
- Report "Stock available", "Upstream has fix (unreleased)", or "Fork still needed"

See [related memory](../memory/) if one is created.
