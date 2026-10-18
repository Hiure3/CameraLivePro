#!/usr/bin/env bash
# Uso: bash scripts/make-zip.sh [saida.zip]   — empacota o projeto sem caches, binários ou segredos.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-CameraLivePro-GitHub.zip}"
OUT_ABS="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
bash scripts/check-repo.sh
rm -f "$OUT_ABS"
zip -rq "$OUT_ABS" . \
  -x ".git/*" "*/bin/*" "*/obj/*" "*/build/*" ".gradle/*" "*/.gradle/*" "publish/*" "dist/*" "out/*" "artifacts/*" \
     "TestResults/*" "*.apk" "*.exe" "*.keystore" "*.jks" "*.pfx" "local.properties" "*.zip" "__pycache__/*"
unzip -tq "$OUT_ABS" >/dev/null && echo "ZIP íntegro: $OUT_ABS"
unzip -l "$OUT_ABS" | grep -q "README.md" && unzip -l "$OUT_ABS" | grep -q ".github/workflows/ci.yml" && echo "README e workflows presentes."
