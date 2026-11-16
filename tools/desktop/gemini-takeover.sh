#!/bin/sh
# Hand the Skein desktop build to Gemini when Claude's usage runs out.
# Runs agy interactively in this repository so you can approve build commands (Gradle, Swift, git).
cd "$(dirname "$0")/../.." || exit 1
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Commit or stash your changes first." >&2
  exit 1
fi
exec ~/.local/bin/agy --model gemini-4-hydrogen-high --mode accept-edits -i "$(cat tools/desktop/gemini-takeover-prompt.md)"
