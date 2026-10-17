#!/usr/bin/env bash
# SMS entrant simulé (root) sans genyshell payant : écrit le SMS dans la boîte de réception Android,
# puis transmet à l'assistant ce que son récepteur SmsReceiver lui aurait transmis. La réception radio n'est pas testée.
# Usage : tools/emu/sms_entrant.sh 0612345678 "texte"
N="$1"; T=$(printf "%s" "$2" | sed "s/'/'\\\\''/g"); NOW=$(date +%s%3N); PKG=fr.assistantvocal.app
adb shell "su -c \"content insert --uri content://sms/inbox --bind address:s:$N --bind body:s:'$T' --bind date:l:$NOW --bind read:i:0 --bind type:i:1\""
adb shell "su -c \"am start-foreground-service -n $PKG/.AssistantService -a fr.assistantvocal.SMS_RECEIVED --es number '$N' --es text '$T' --el time $NOW\"" >/dev/null
