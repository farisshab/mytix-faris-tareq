#!/bin/bash
# Builds report/report.pdf from report.md: inlines the DDL (sql/schema.sql) into
# section 8, makes the ER diagram resolvable, and renders with pandoc + weasyprint.
# Run from anywhere: ./report/build.sh
set -e
cd "$(dirname "$0")"

# weasyprint's native libs (pango/cairo) live in the Homebrew prefix.
export DYLD_FALLBACK_LIBRARY_PATH="/opt/homebrew/lib:${DYLD_FALLBACK_LIBRARY_PATH}"

# The ER diagram is committed as ER_Diagram.png (already beside this script). It was
# rasterized once from docs/ER_Diagram_v2.svg with headless Chrome, because the
# drawio SVG puts its labels in <foreignObject>, which weasyprint cannot render.
# To regenerate it after editing the diagram:
#   "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless \
#     --force-device-scale-factor=2 --window-size=2190,1320 \
#     --default-background-color=FFFFFFFF --screenshot=report/ER_Diagram.png \
#     "file://$PWD/docs/ER_Diagram_v2.svg"

# Inline the schema at the build marker so the DDL never drifts from schema.sql.
python3 - <<'PY'
ddl = open('../sql/schema.sql').read().rstrip()
md = open('report.md').read()
marker = '<!-- BUILD: inline sql/schema.sql here as a fenced ```sql block at PDF generation. -->'
md = md.replace(marker, '```sql\n' + ddl + '\n```')
open('report_full.md', 'w').write(md)
PY

# Two steps on purpose: pandoc builds the HTML, then weasyprint is called directly.
# (macOS strips DYLD_* when pandoc spawns weasyprint as a subprocess, so we don't
# use pandoc's --pdf-engine.)
pandoc report_full.md -o report.html --standalone
python3 -m weasyprint report.html report.pdf -s report.css

echo "Wrote report/report.pdf"
