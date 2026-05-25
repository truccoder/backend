#!/usr/bin/env bash
# Installs git hooks from scripts/hooks/ into .git/hooks/
# Run this once after cloning: ./scripts/install-hooks.sh

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOKS_SRC="$REPO_ROOT/scripts/hooks"
HOOKS_DST="$REPO_ROOT/.git/hooks"

echo "Installing git hooks..."

for hook in "$HOOKS_SRC"/*; do
    name="$(basename "$hook")"
    dst="$HOOKS_DST/$name"

    cp "$hook" "$dst"
    chmod +x "$dst"
    echo "  ✓ Installed: $name"
done

echo "Done. Git hooks are active."
