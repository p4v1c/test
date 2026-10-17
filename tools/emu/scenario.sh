#!/usr/bin/env bash
# Enchaîne des phrases : chaque ligne du fichier est « attente_en_s|phrase ». Résultats ajoutés à tools/emu/logs/resultats.log.
# Usage : tools/emu/scenario.sh fichier.txt
cd "$(dirname "$0")/../.."; mkdir -p tools/emu/logs
while IFS='|' read -r w p; do
  [ -z "$p" ] && continue
  out=$(bash tools/emu/dire.sh "$p" "$w" </dev/null 2>&1 | grep -v "^>>>" | sed -E 's/^.{19}[0-9 ]+ [IWE] [A-Za-z]+: //')
  printf '### %s\n%s\n' "$p" "$out" | tee -a tools/emu/logs/resultats.log
done < "$1"
