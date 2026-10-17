#!/usr/bin/env bash
# SMS entrant simulé (root) sans genyshell payant : écrit le SMS dans la boîte de réception Android,
# puis transmet à l'assistant ce que son récepteur SmsReceiver lui aurait transmis. La réception radio n'est pas testée.
# Usage : tools/emu/sms_entrant.sh 0612345678 "texte"
N="$1"; T=$(printf "%s" "$2" | sed "s/'/'\\\\''/g"); NOW=$(date +%s%3N); PKG=fr.assistantvocal.app
# Seule l'app SMS par défaut peut écrire dans la boîte : on passe par la base du fournisseur SMS (root).
SQL=$(printf "%s" "$2" | sed "s/'/''/g")
printf "INSERT INTO sms(address,body,date,date_sent,read,seen,type) VALUES('%s','%s',%s,%s,0,0,1);\n" "$N" "$SQL" "$NOW" "$NOW" \
  | adb shell "su -c 'sqlite3 /data/data/com.android.providers.telephony/databases/mmssms.db'"
adb shell "su -c \"am start-foreground-service -n $PKG/.AssistantService -a fr.assistantvocal.SMS_RECEIVED --es number '$N' --es text '$T' --el time $NOW\"" >/dev/null
