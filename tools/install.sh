#!/usr/bin/env bash
# Installe l'assistant sur le téléphone branché en USB et règle tout ce qui peut l'être sans toucher l'écran.
# Usage : ./tools/install.sh [chemin/vers/app-debug.apk]
set -euo pipefail
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="fr.assistantvocal.app"
KEYS_SERVICE="$PKG/$PKG.triggers.KeyAccessibilityService"

echo "1/6 Installation de l'application…"
adb install -r "$APK"

echo "2/6 Autorisations (micro, notifications, contacts, appels, SMS, position)…"
for P in RECORD_AUDIO POST_NOTIFICATIONS READ_CONTACTS CALL_PHONE ANSWER_PHONE_CALLS READ_PHONE_STATE READ_CALL_LOG \
         READ_SMS RECEIVE_SMS SEND_SMS CAMERA ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  adb shell pm grant "$PKG" "android.permission.$P" 2>/dev/null || echo "   (autorisation $P à accorder à la main)"
done
echo "   Accès aux notifications (lecture des notifications Gmail, Meet…)…"
adb shell cmd notification allow_listener "$PKG/$PKG.phone.AssistantNotificationListener" 2>/dev/null || \
  echo "   -> à faire à la main : Paramètres > Notifications > Accès aux notifications"

echo "3/6 Fonctionnement en arrière-plan (pas d'économie de batterie pour l'assistant)…"
adb shell dumpsys deviceidle whitelist +"$PKG" >/dev/null

echo "4/6 Assistant numérique par défaut…"
if ! adb shell cmd role add-role-holder android.app.role.ASSISTANT "$PKG" 2>/dev/null; then
  echo "   -> à faire à la main : Paramètres > Applications > Applications par défaut > Assistant numérique."
fi

echo "5/6 Boutons volume (service d'accessibilité), sans désactiver TalkBack…"
CURRENT="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ "$CURRENT" != *"$KEYS_SERVICE"* ]]; then
  if [[ -z "$CURRENT" || "$CURRENT" == "null" ]]; then NEW="$KEYS_SERVICE"; else NEW="$CURRENT:$KEYS_SERVICE"; fi
  adb shell settings put secure enabled_accessibility_services "$NEW"
  adb shell settings put secure accessibility_enabled 1
fi

echo "6/6 Lancement…"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo "Terminé. Ouvrez « Réglages, pour un proche » pour coller la clé Gemini et faire apprendre le nom."
