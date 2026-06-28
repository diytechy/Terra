#!/bin/bash
# Check for cloud-minecraft dependency updates
#
# Usage:
#   ./check-cloud-updates.sh              # Check all artifacts
#   ./check-cloud-updates.sh paper        # Check only cloud-paper
#   ./check-cloud-updates.sh --commits    # Show recent upstream commits

set -e

ARTIFACTS=("cloud-paper" "cloud-fabric" "cloud-neoforge")
SHOW_COMMITS=false
TARGET=""

# Parse arguments
if [[ $# -gt 0 ]]; then
    case "$1" in
        --commits) SHOW_COMMITS=true ;;
        paper|fabric|neoforge)
            TARGET="cloud-$1"
            ARTIFACTS=("$TARGET")
            ;;
        *)
            echo "Usage: $0 [paper|fabric|neoforge] [--commits]"
            exit 1
            ;;
    esac
fi

# Fetch Maven metadata for each artifact
echo "=== Cloud Minecraft Dependency Check ==="
echo

for artifact in "${ARTIFACTS[@]}"; do
    platform=$(case "$artifact" in
        cloud-paper) echo "Bukkit" ;;
        cloud-fabric) echo "Fabric" ;;
        cloud-neoforge) echo "NeoForge" ;;
    esac)

    echo "[$platform] $artifact"

    # Try to fetch from Maven Central
    metadata_url="https://repo.maven.apache.org/maven2/org/incendo/$artifact/maven-metadata.xml"

    release=$(curl -s "$metadata_url" 2>/dev/null | grep -o '<release>[^<]*</release>' | sed 's/<[^>]*>//g' || echo "?")
    updated=$(curl -s "$metadata_url" 2>/dev/null | grep -o '<lastUpdated>[^<]*</lastUpdated>' | sed 's/<[^>]*>//g' || echo "?")

    if [[ "$release" != "?" ]]; then
        echo "  Release: $release"
        # Format date (yyyymmddhhmmss -> yyyy-mm-dd hh:mm)
        if [[ ${#updated} -eq 14 ]]; then
            formatted_date="${updated:0:4}-${updated:4:2}-${updated:6:2} ${updated:8:2}:${updated:10:2}"
            echo "  Updated: $formatted_date"
        fi
    else
        echo "  ⚠ Could not fetch metadata"
    fi
    echo
done

# Optionally show recent upstream commits
if [[ "$SHOW_COMMITS" == true ]]; then
    echo "=== Recent Upstream Commits (cloud-paper) ==="
    echo
    curl -s "https://api.github.com/repos/Incendo/cloud-minecraft/commits?path=cloud-paper&per_page=10" 2>/dev/null | \
        python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    for c in data[:10]:
        msg = c['commit']['message'].split('\n')[0][:70]
        date = c['commit']['author']['date'].split('T')[0]
        print(f'  {date} | {msg}')
except:
    print('  ⚠ Could not fetch commits')
" || echo "  (requires Python 3 and network access)"
fi

echo
echo "✓ For update instructions, see CLAUDE.md"
