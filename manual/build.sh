#!/bin/bash
# Builds manual/manual.pdf from manual.md, with report.pdf and rendered with
# pandoc + weasyprint. Run from anywhere: ./manual/build.sh
set -e
cd "$(dirname "$0")"

# weasyprint's native libs (pango/cairo) live in the Homebrew prefix.
export DYLD_FALLBACK_LIBRARY_PATH="/opt/homebrew/lib:${DYLD_FALLBACK_LIBRARY_PATH}"

# Two steps on purpose, same reasoning as reports' build.sh: pandoc builds the HTML, then
# weasyprint is called directly, since macOS strips DYLD_* when pandoc spawns
# weasyprint as a subprocess.
pandoc manual.md -o manual.html --standalone
python3 -m weasyprint manual.html ../manual.pdf -s ../report/report.css

echo "Wrote manual.pdf (repository root)"