#!/usr/bin/env bash
# The launcher/game profile contract, end to end, without opening a game.
#
#   1. The launcher (Rust) writes Default, Bedwars and Survival and chooses Survival.
#   2. The game (Java) opens the same files, starts on Survival, switches to
#      Bedwars with an edit, restarts, and is still on Bedwars.
#   3. The launcher reads back the game's edit and its live profile.
#
# Needs the Java test classes compiled: gradle compileTestJava.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT

module="$here/minecraft/fathom-optimizations"
separator=":"
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) separator=";" ;; esac
main="$module/build/classes/java/main"
tests="$module/build/classes/java/test"
# Windows java.exe cannot read MSYS paths such as /c/Users; hand it native ones.
if command -v cygpath >/dev/null 2>&1; then
    main="$(cygpath -w "$main")"
    tests="$(cygpath -w "$tests")"
    root_for_java="$(cygpath -w "$root")"
else
    root_for_java="$root"
fi
classpath="${main}${separator}${tests}"

echo "1. launcher writes"
FATHOM_CONTRACT_DIR="$root" FATHOM_CONTRACT_STAGE=launcher-writes \
    cargo test --quiet -p launcher-core --test profile_contract -- --nocapture

echo "2. game reads, switches, restarts"
java -cp "$classpath" net.fathom.optimizations.core.ProfileContract "$root_for_java"

echo "3. launcher reads back"
FATHOM_CONTRACT_DIR="$root" FATHOM_CONTRACT_STAGE=launcher-reads \
    cargo test --quiet -p launcher-core --test profile_contract -- --nocapture

echo "profile contract: ok"
