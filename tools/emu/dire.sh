#!/usr/bin/env bash
# Fait « entendre » une phrase à l'assistant (version de debug, émulateur rooté) et affiche ce qu'il répond.
# Usage : tools/emu/dire.sh "quelle heure est-il ?" [secondes d'attente, 8 par défaut]
set -euo pipefail
PHRASE="$1"; WAIT="${2:-8}"
PKG=fr.assistantvocal.app
adb logcat -c
# Le service n'est pas exporté : on passe par root. Les apostrophes de la phrase sont échappées pour le shell distant.
ESC=$(printf "%s" "$PHRASE" | sed "s/'/'\\\\''/g")
adb shell "su -c \"am start-foreground-service -n $PKG/.AssistantService -a fr.assistantvocal.DEBUG_HEAR --es text '$ESC'\"" >/dev/null
sleep "$WAIT"
echo ">>> Dit : $PHRASE"
adb logcat -d -s AssistantService:I Media:I TextBooks:I Vision:I Organizer:W AndroidPhone:W Receivers:I AndroidRuntime:E \
  | grep -E "Entendu :|Dit :|Lecture|Lecteur prêt|segment|Exception|FATAL|Error" || echo "(aucune réponse dans le journal)"
