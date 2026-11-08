#!/usr/bin/env bash
# Genera informe.pdf a partir de informe.html con Edge o Chrome en modo headless.
# Uso (Git Bash en Windows): bash generar_pdf.sh
# Si cambian los resultados, antes: python figuras.py
cd "$(dirname "$0")"
for b in "/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" \
         "/c/Program Files/Google/Chrome/Application/chrome.exe" \
         "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" google-chrome chromium; do
  if [ -x "$b" ] || command -v "$b" >/dev/null 2>&1; then BROWSER="$b"; break; fi
done
[ -n "${BROWSER:-}" ] || { echo "No encuentro Edge ni Chrome"; exit 1; }
html="$PWD/informe.html"; pdf="$PWD/informe.pdf"; profile="${TMPDIR:-/tmp}/informe-pdf-profile"
if command -v cygpath >/dev/null 2>&1; then html=$(cygpath -m "$html"); pdf=$(cygpath -w "$pdf"); profile=$(cygpath -w "$profile"); fi
rm -f informe.pdf
# perfil aparte: si el navegador ya está abierto, sin él no genera el PDF
"$BROWSER" --headless=new --disable-gpu --no-pdf-header-footer --user-data-dir="$profile" \
  --print-to-pdf="$pdf" "file:///${html#/}" 2>/dev/null
# desde Git Bash el navegador devuelve el control antes de terminar: se espera al fichero
for _ in $(seq 1 90); do [ -s informe.pdf ] && break; sleep 1; done
ls -la informe.pdf
