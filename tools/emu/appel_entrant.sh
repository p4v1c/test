#!/usr/bin/env bash
# Sonnerie d'un appel entrant simulée (root) : l'assistant reçoit ce que CallStateReceiver lui aurait transmis.
# Aucun vrai appel n'existe : « décroche » ne peut donc rien décrocher. Usage : tools/emu/appel_entrant.sh 0612345678 [IDLE]
S="${2:-RINGING}"; PKG=fr.assistantvocal.app
adb shell "su -c \"am start-foreground-service -n $PKG/.AssistantService -a fr.assistantvocal.CALL_STATE --es state $S --es number '$1'\"" >/dev/null
