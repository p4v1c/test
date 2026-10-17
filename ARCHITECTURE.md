# ARCHITECTURE.md — Choix techniques

## 0. État d'avancement

- Phase 0 (recherche) : faite.
- Phase 1 (socle) : faite. Modules `core`, `llm`, `app`.
- Phase 3 (appels, SMS, notifications, rappels, médicaments, SOS) : faite, 121 tests automatiques.
- Phase 2 (mot d'activation, déclencheurs) : faite. 80 tests automatiques + test d'intégration Vosk réel
  sur voix de synthèse (mesures dans `tools/wake/README.md`).
- Téléphone cible : Google Pixel (voir FEATURES.md, « Décisions validées »).

## 1. Vue d'ensemble

```
 Micro ──► VAD (Silero) ──► Mot d'activation (Vosk, grammaire [nom]) ──┐
 Bouton casque / volume / accueil / notification / écran ──────────────┤
                                                                       ▼
                                                    Earcon « j'écoute » + vibration
                                                                       │
                                                         STT commande (voir §4)
                                                                       │
                                                                       ▼
                                   ┌─────────────── Routeur local (règles FR) ───────────────┐
                                   │  reconnu avec confiance ?                                │
                                   │     oui → Action locale            non → LlmProvider    │
                                   └──────────────┬──────────────────────────┬───────────────┘
                                                  │                          │ (function calling)
                                                  ▼                          ▼
                                       Registre d'outils (Tools) ◄──── appel d'outil
                                                  │
                                 action sensible ? ──► Machine de confirmation (relecture, oui/non)
                                                  │
                                                  ▼
                                       Exécution + vérification du résultat
                                                  │
                                                  ▼
                                    Réponse texte ──► TTS (+ earcon succès/erreur)
```

Un seul processus, un **service au premier plan** (`AssistantService`) qui possède le micro, le TTS et l'état de la conversation.

## 2. Modules Gradle

| Module | Contenu | Dépend d'Android ? |
|---|---|---|
| `core` | Routeur local, parsing des nombres/heures en français, machine de confirmation, gestion du nom, modèles de données, interfaces (`LlmProvider`, `Tool`, `SpeechOutput`…) | **Non** (Kotlin pur → tests rapides) |
| `llm` | `GeminiProvider`, `ClaudeProvider`, `FallbackLlmProvider`, compteur de quota, compression d'image | Non (OkHttp) |
| `app` | Service, wake word, STT, TTS, earcons, outils Android (appels, SMS, caméra…), UI réglages | Oui |

Séparer `core` et `llm` d'Android permet de tester 80 % de la logique sans émulateur.

## 3. Fonctionner en arrière-plan, écran verrouillé, au démarrage

**Problème** : depuis Android 14, une app ne peut pas créer un service qui utilise le micro depuis l'arrière-plan, ni depuis le démarrage du téléphone.

**Solution retenue (implémentée) : l'app devient l'« assistant numérique par défaut »** (elle déclare un `VoiceInteractionService` et demande `RoleManager.ROLE_ASSISTANT`). D'après la documentation Android, un service démarré par l'app qui fournit le `VoiceInteractionService` est **exempté** de la restriction micro. Avantages en plus :
- appui long sur le bouton d'accueil / geste assistant → déclenche notre app ;
- fonctionne sur l'écran de verrouillage (`VoiceInteractionSession` avec `showOnLockscreen`) ;
- accès au journal d'appels et aux SMS associé au rôle.

Compléments :
- `RECEIVE_BOOT_COMPLETED` → relance le service ;
- demande d'exclusion de l'optimisation de batterie (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), qui lève aussi la restriction de démarrage en arrière-plan ;
- **root (bonus)** : installation en priv-app ou `appops`/`deviceidle whitelist` pour une protection maximale.

Piège évité : l'assistant par défaut doit déclarer un service de reconnaissance vocale, qu'Android peut alors
utiliser comme reconnaissance par défaut du téléphone. Le nôtre (`ProxyRecognitionService`) relaie donc vers
Google, et l'app choisit explicitement le service Google pour elle-même (`RecognizerChooser`).

**Plan B** si le rôle assistant pose problème sur ce téléphone : notification persistante « Touchez pour réactiver l'écoute » + annonce vocale au démarrage demandant de toucher l'écran une fois (moins bien, mais fonctionnel).

## 4. Reconnaissance vocale

### 4.1 Mot d'activation (implémenté et mesuré en phase 2)

Choix : **Vosk small-fr**, sans entraînement, nom changeable à la voix. Détails et mesures : `tools/wake/README.md`.

1. Niveau sonore : Vosk n'est alimenté que quand quelqu'un parle (bruit de fond = minimum des 4 dernières secondes).
   Un VAD Silero n'a pas été nécessaire : le coût mesuré est de 3–4 % d'un cœur pendant la parole, 0 en silence.
2. Étape 1 : grammaire `[nom, "[unk]"]`, décodage continu, Vosk découpe lui-même les phrases.
3. Étape 2 : le passage exact du nom (horodatage Vosk) est revérifié face à 1000 mots fréquents.
4. Calibration : 3 enregistrements, seuil ajusté, test de 15 s de faux déclenchements → mode strict si besoin.

Pièges rencontrés et corrigés : grammaire JSON avec accents échappés (`\u00e9`) ignorée silencieusement par Vosk ;
horodatages Vosk cumulés depuis la création du décodeur (même après `reset`) ; double fermeture d'un décodeur
= plantage natif ; rebond (un nom déclenchait 8 fois).

Écartés après mesure : sherpa-onnx KWS (pas de modèle français), Porcupine (palier gratuit fermé),
openWakeWord (entraînement par nom), vérification par transcription libre (rate le nom trop souvent).

### 4.2 Commande après le mot d'activation

| Moteur | Qualité en français | Hors ligne | Rôle |
|---|---|---|---|
| **Android SpeechRecognizer (Google) fr-FR** | Très bonne | Oui si le pack français est installé (vérifié au lancement) | **Par défaut** |
| Vosk small-fr | Moyenne (WER ~20 %) | Oui | Repli automatique hors ligne / si Google indisponible ; suffisant pour les commandes du routeur local, pas pour dicter un long SMS |
| sherpa-onnx streaming zipformer fr | Moyenne à bonne | Oui | Option, à comparer pendant les tests |

Pour le **oui/non** des confirmations, Vosk en grammaire fermée (oui, vas-y, d'accord, envoie, confirme / non, annule, stop, attends) : plus fiable que la reconnaissance libre dans le bruit.

## 5. Synthèse vocale

- **Android TextToSpeech fr-FR** par défaut (voix Google, hors ligne si installée).
- **Option** : voix Piper française via sherpa-onnx (100 % hors ligne, indépendante de Google).
- Vitesse, hauteur, volume réglables à la voix ; découpage en phrases pour pouvoir interrompre entre deux phrases ; **focus audio** demandé (baisse la musique pendant la réponse) ; pas de parole simultanée avec TalkBack.

## 6. Routeur local

- Règles en français (expressions régulières + liste de synonymes), normalisation (accents, nombres en lettres « sept heures et quart » → 07:15).
- Chaque intention locale renvoie un **score**. Si le score est élevé → action directe ; s'il est moyen → question fermée (« voulez-vous dire… ? ») ; s'il est faible → IA (si en ligne et pas en mode privé) ; sinon « je n'ai pas compris, pouvez-vous reformuler ? ».
- Intentions locales couvertes : heure, date, batterie, réseau, appels, SMS, alarmes, minuteurs, rappels, médicaments, volume, vitesse de parole, verbosité, nom, aide, stop, répète, SOS, lampe, musique (pause/suivant), radio, lecture OCR, mode privé, « où suis-je » (pas d'IA, mais réseau pour l'adresse)…

## 7. Fournisseurs IA

```kotlin
interface LlmProvider {
    val id: String
    suspend fun chat(request: LlmRequest): LlmResult   // texte + images + outils
}
sealed interface LlmResult { Text, ToolCalls, Error(kind = QUOTA | NETWORK | AUTH | SERVER | REFUSED) }
```

- **`GeminiProvider`** (REST `v1beta/models/{model}:generateContent`, `functionDeclarations`) :
  - texte et vision courante : **`gemini-3.5-flash-lite`** (~500 req/jour gratuites) ;
  - documents difficiles : `gemini-3.8-flash` (~20 req/jour gratuites) ;
  - IDs de modèles **dans les réglages**, pas en dur dans la logique.
- **`ClaudeProvider`** : SDK officiel Java, modèle **`claude-haiku-5-5`**, outils en `tool_use`, pas de choix d'outil forcé.
- **`FallbackLlmProvider`** : essaie dans l'ordre configuré ; sur 429/5xx/réseau, passe au suivant et l'**annonce** (« Gemini a atteint sa limite du jour, je passe à Claude »). Mémorise le quota épuisé jusqu'à 9 h (heure de remise à zéro Gemini).
- Ajout facile d'autres fournisseurs : OpenRouter (API compatible), modèle local futur.
- **Compteur local** par fournisseur et par jour ; annonce à 80 % de la limite configurée.
- **Historique** : 6 derniers échanges ; **images** redimensionnées (côté long 1024 px, JPEG 75) avant envoi.
- **Prompt système** : celui fourni dans la demande, avec `[NOM]` remplacé, plus une ligne sur la verbosité courante et la date/heure.
- **Clés API** : chiffrées avec une clé AES de l'Android Keystore (EncryptedSharedPreferences étant déprécié).

## 8. Outils et confirmations

Chaque fonction est un `Tool` : nom, description, schéma JSON des paramètres, niveau de risque.

| Risque | Exemples | Comportement |
|---|---|---|
| `SAFE` | heure, météo, lire un SMS | exécution directe |
| `CONFIRM` | appeler, envoyer SMS/mail, partager position, changer de nom | relecture + oui/non ; « non » ou silence = annulé |
| `CRITICAL` | SOS | compte à rebours 10 s annulable (« stop », « annule ») puis exécution |

**Règle de sécurité : un outil `CONFIRM` ou `CRITICAL` ne peut jamais s'exécuter sans passer par la machine de confirmation, même si l'IA le demande.** La confirmation est gérée par le code, pas par l'IA. Les contenus lus (SMS, mails, pages web, images) sont marqués comme données non fiables dans le prompt pour limiter l'injection d'instructions.

## 9. Fonctions Android utilisées

| Besoin | API |
|---|---|
| Appels sortants | `ACTION_CALL` + `CALL_PHONE` |
| Décrocher / raccrocher | `TelecomManager.acceptRingingCall()` / `endCall()` + `ANSWER_PHONE_CALLS` (pas besoin d'être l'app Téléphone) |
| Haut-parleur | `AudioManager.setCommunicationDevice` (API 31+) |
| Annonce de l'appelant | `READ_PHONE_STATE`, `READ_CALL_LOG`, contacts |
| SMS | `SmsManager`, fournisseur `Telephony.Sms` |
| Notifications des autres apps | `NotificationListenerService` + `RemoteInput` pour répondre |
| Alarmes / minuteurs | Intents `AlarmClock` (app Horloge) ; rappels via `AlarmManager` exact |
| Agenda | `CalendarContract` |
| Boutons volume / casque | `AccessibilityService` (filtre des touches, sans gêner TalkBack) + `MediaSession` |
| Caméra / lampe | CameraX, `CameraManager.setTorchMode` |
| Position | `FusedLocationProvider` (Play Services) avec repli `LocationManager` |

## 9 bis. Choix de la phase 3

- **Compétences dans `core/skills`** (`CommunicationSkill`, `ReminderSkill`) : toute la logique (recherche de contact,
  relectures, confirmations, rappels, journal des médicaments) est testable sans Android. Android ne fournit que des
  accès (`ContactsSource`, `SmsSender`, `PhoneActions`, `AlarmScheduler`…).
- **L'IA ne fait jamais l'action** : ses fonctions `call_contact`, `send_sms`, `create_reminder`, `emergency_sos`… sont
  traduites en intentions locales (`IntentTool`) qui passent par les mêmes questions et confirmations.
- **Dialogue** (`Dialog`) : questions ouvertes (« Que voulez-vous dire à Paul ? ») et confirmations oui/non, avec expiration.
- **Appels** : `TelecomManager.placeCall` (pas d'activité à ouvrir, donc pas bloqué en arrière-plan), décrocher/raccrocher
  via `acceptRingingCall`/`endCall` (permission `ANSWER_PHONE_CALLS`) : l'app n'a pas besoin d'être l'application Téléphone.
- **SMS** : lecture du fournisseur SMS et envoi par `SmsManager` avec attente de l'accusé d'envoi ; l'app n'est pas
  l'application SMS par défaut (elle ne marque donc pas les SMS comme lus ; elle retient elle-même ce qui a été lu).
- **Rappels, réveils et minuteurs gérés par l'app** (`AlarmManager.setAlarmClock`, fiable en veille profonde) plutôt que
  par l'horloge système : l'annonce est parlée (« le minuteur des pâtes est terminé »), on peut répondre « c'est fait », et
  ouvrir l'horloge système depuis l'arrière-plan serait bloqué par Android. Reprogrammés au redémarrage.
- **SOS** : déroulé dans `core/safety/SosFlow` ; passé le délai d'annulation, l'envoi ne peut plus être interrompu
  (`NonCancellable`) ; sans contact d'urgence, appel du 112.
- **Compréhension des heures** (`core/time/FrenchTime`) : « dans un quart d'heure », « demain à 8 h », « tous les jours à 8 h
  et à 20 h », « en semaine », « lundi à 14 h 30 ». Une heure ambiguë (« à 3 heures » dit à 14 h) est relue avant d'être enregistrée.

## 10. Données locales

- Réglages : DataStore. Secrets : Keystore + AES-GCM.
- Alias de contacts, favoris de lieux, rappels, journal de médicaments, mémos, positions de lecture : base Room.
- **Export/import** des réglages en fichier JSON (sans les clés API, ou chiffré par un mot de passe choisi par l'aidant).

## 11. Vie privée

- Aucun son n'est envoyé à l'extérieur avant le mot d'activation (traitement 100 % local).
- Seule la commande transcrite (et l'image si fonction vision) part vers l'IA.
- Mode privé = pas d'IA, pas de Google STT en ligne.
- README : liste claire de ce qui part chez Google / Anthropic ; palier gratuit Gemini utilisé pour entraîner les modèles.

## 12. Stratégie de test

| Niveau | Outils | Ce qui est testé |
|---|---|---|
| Unitaires (`core`, `llm`) | JUnit, MockWebServer | Routeur, nombres/heures, confirmations, nom personnalisé, parsing Gemini/Claude, bascule 429, compteur |
| Android (sans émulateur) | Robolectric | Outils Android avec faux contacts/SMS, TTS simulé |
| Instrumentés | Émulateur x86_64 API 34 (si KVM dispo), Espresso | Service, permissions, accessibilité de l'écran |
| Scénarios | Texte injecté à la place du STT, images de test à la place de la caméra | « Il dit X → il entend Y » |
| Manuels | Checklist sur le vrai téléphone | Mot d'activation dans le bruit, casque, SOS, chute, écran verrouillé |

## 13. Risques principaux

| Risque | Mesure |
|---|---|
| Mot d'activation peu fiable dans le bruit | VAD + calibration + déclencheurs physiques toujours disponibles |
| Quotas Gemini changent | Compteur, bascule Claude, routeur local, IDs de modèles réglables |
| Le rôle assistant n'exempte pas sur ce téléphone | Plan B notification + root |
| Conflits avec TalkBack | Tests dédiés, focus audio, pas de capture de gestes |
| Batterie | VAD avant Vosk, mesure de consommation pendant 24 h |
| Mises à jour Android/Google cassent une API | Pas de dépendance unique, tests de bout en bout |
