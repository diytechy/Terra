#!/usr/bin/env bash
#
# build-mac.command — macOS double-clickable build launcher for Terra.
#
# Installs the dependencies needed to build Terra (Git + JDK 25 via Homebrew),
# then runs the same build the Windows launcher (gradlew.bat) runs by default:
#
#     ./gradlew clean build publishToMavenLocal
#
# Double-click in Finder, or run from a terminal:  ./build-mac.command
#
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# --- Run from the script's own directory (matches gradlew.bat APP_HOME) -------
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Match gradlew.bat: DEFAULT_JVM_OPTS and the default double-click command.
DEFAULT_JVM_OPTS=("-Xmx64m" "-Xms64m")
REQUIRED_JAVA_MAJOR=25

# Use any arguments passed to the script; otherwise use the gradlew.bat default.
if [ "$#" -gt 0 ]; then
    GRADLE_ARGS=("$@")
else
    GRADLE_ARGS=("clean" "build" "publishToMavenLocal")
fi

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARNING:\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; }

# --- Ensure Homebrew is installed (used to install Git and the JDK) ----------
ensure_homebrew() {
    if command -v brew >/dev/null 2>&1; then
        return
    fi

    # Common install locations that may not yet be on PATH this session.
    for brew_bin in /opt/homebrew/bin/brew /usr/local/bin/brew; do
        if [ -x "$brew_bin" ]; then
            eval "$("$brew_bin" shellenv)"
            return
        fi
    done

    log "Homebrew not found. Installing Homebrew..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

    # Load brew into this shell after install (Apple Silicon vs Intel paths).
    if [ -x /opt/homebrew/bin/brew ]; then
        eval "$(/opt/homebrew/bin/brew shellenv)"
    elif [ -x /usr/local/bin/brew ]; then
        eval "$(/usr/local/bin/brew shellenv)"
    fi

    command -v brew >/dev/null 2>&1 || { err "Homebrew installation failed."; exit 1; }
}

# --- Ensure Git is installed (gradlew.bat hard-fails if git is missing) ------
ensure_git() {
    if command -v git >/dev/null 2>&1; then
        log "Git command available."
        return
    fi
    log "Git not found. Installing git..."
    brew install git
    command -v git >/dev/null 2>&1 || { err "Git installation failed."; exit 1; }
    log "Git command available."
}

# --- Ensure a JDK >= 25 is installed and select it via JAVA_HOME -------------
java_major_of_home() {
    # Prints the major version of the JDK at the given JAVA_HOME, or nothing.
    local home="$1"
    [ -x "$home/bin/java" ] || return 0
    "$home/bin/java" -version 2>&1 \
        | awk -F'"' '/version/ {print $2; exit}' \
        | awk -F. '{ if ($1 == 1) print $2; else print $1 }'
}

ensure_java() {
    # Prefer an already-installed JDK 25 selected through macOS's java_home.
    if /usr/libexec/java_home -v "$REQUIRED_JAVA_MAJOR" >/dev/null 2>&1; then
        JAVA_HOME="$(/usr/libexec/java_home -v "$REQUIRED_JAVA_MAJOR")"
    else
        log "JDK $REQUIRED_JAVA_MAJOR not found. Installing Temurin $REQUIRED_JAVA_MAJOR..."
        # Temurin (Adoptium) provides the versioned JDK cask.
        brew install --cask "temurin@${REQUIRED_JAVA_MAJOR}"
        JAVA_HOME="$(/usr/libexec/java_home -v "$REQUIRED_JAVA_MAJOR" 2>/dev/null || true)"
    fi

    if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
        err "Could not locate a JDK $REQUIRED_JAVA_MAJOR installation."
        err "Please install JDK $REQUIRED_JAVA_MAJOR and set JAVA_HOME."
        exit 1
    fi

    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"

    local major
    major="$(java_major_of_home "$JAVA_HOME")"
    "$JAVA_HOME/bin/java" -version
    if [ -z "$major" ]; then
        warn "Could not parse Java version, attempting anyways."
    elif [ "$major" -lt "$REQUIRED_JAVA_MAJOR" ]; then
        warn "Java version appears to be below $REQUIRED_JAVA_MAJOR. Terra targets Java $REQUIRED_JAVA_MAJOR."
    else
        log "Java version OK (JDK $major at $JAVA_HOME)."
    fi
}

# --- Main --------------------------------------------------------------------
log "Preparing to build Terra in: $SCRIPT_DIR"
ensure_homebrew
ensure_git
ensure_java

chmod +x ./gradlew 2>/dev/null || true

log "Starting build command: ./gradlew ${GRADLE_ARGS[*]}"
# JAVA_OPTS carries the DEFAULT_JVM_OPTS that gradlew.bat passes to the launcher;
# the gradlew script forwards JAVA_OPTS to the Gradle wrapper JVM.
JAVA_OPTS="${JAVA_OPTS:-} ${DEFAULT_JVM_OPTS[*]}" ./gradlew "${GRADLE_ARGS[@]}"

status=$?
echo ""
if [ "$status" -eq 0 ]; then
    log "Build finished successfully."
else
    err "Build failed with exit code $status."
fi

# Keep the Terminal window open when launched via double-click in Finder.
if [ -t 0 ] && [ -t 1 ]; then
    printf '\nPress Return to close this window...'
    read -r _ || true
fi

exit "$status"
