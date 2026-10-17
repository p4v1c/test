# ARCHITECTURE.md — Choix techniques

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

**Solution retenue : l'app devient l'« assistant numérique par défaut »** (elle déclare un `VoiceInteractionService` et demande `RoleManager.ROLE_ASSISTANT`). D'après la documentation Android, un service démarré par l'app qui fournit le `VoiceInteractionService` est **exempté** de la restriction micro. Avantages en plus :
- appui long sur le bouton d'accueil / geste assistant → déclenche notre app ;
- fonctionne sur l'écran de verrouillage (`VoiceInteractionSession` avec `showOnLockscreen`) ;
- accès au journal d'appels et aux SMS associé au rôle.

Compléments :
- `RECEIVE_BOOT_COMPLETED` → relance le service ;
- demande d'exclusion de l'optimisation de batterie (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), qui lève aussi la restriction de démarrage en arrière-plan ;
- **root (bonus)** : installation en priv-app ou `appops`/`deviceidle whitelist` pour une protection maximale.

**Plan B** si le rôle assistant pose problème sur ce téléphone : notification persistante « Touchez pour réactiver l'écoute » + annonce vocale au démarrage demandant de toucher l'écran une fois (moins bien, mais fonctionnel).

## 4. Reconnaissance vocale

### 4.1 Mot d'activation

| Option | Avantages | Inconvénients | Choix |
|---|---|---|---|
| **Vosk small-fr en grammaire restreinte** `["<nom>", "[unk]"]` | Français natif, nom arbitraire sans entraînement, changeable à la voix, 41 Mo, Apache 2.0 | Plus de calcul qu'un vrai KWS → filtré par VAD | **Par défaut** |
| sherpa-onnx KWS | Très léger, nom arbitraire | Pas de modèle français (jetons anglais/chinois) | Expérimental, comparé pendant les tests |
| openWakeWord (modèle entraîné) | Très précis et léger | Entraînement d'1 h par nom | Option avancée |
| Porcupine | Excellent | Palier gratuit fermé | Seulement si clé fournie |

Pipeline : micro 16 kHz → **VAD Silero** (seule la parole réveille Vosk) → Vosk grammaire → **double contrôle** : score de confiance du mot ≥ seuil **et** durée plausible. Le seuil est **calibré automatiquement** à la configuration : on fait dire le nom 3 fois, puis 30 secondes de conversation/télé sans le nom pour mesurer les faux déclenchements.

Conseils de nom affichés et lus : 2-3 syllabes, sonorités rares (« Jarvis », « Orphée », « Balthazar »), pas un prénom de la famille, pas un mot courant. L'app **teste la distance phonétique** du nom avec un lexique de mots français fréquents et prévient si elle est trop faible.

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
| Chute | Accéléromètre, algorithme en 3 phases (chute libre < 0,5 g → impact > 2,5 g → immobilité 10 s), seuils réglables |

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
