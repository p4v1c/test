# ARCHITECTURE.md — Choix techniques

## 0. État d'avancement

- Phase 0 (recherche) : faite.
- Phase 1 (socle) : faite. Modules `core`, `llm`, `app`.
- Phase 3 (appels, SMS, notifications, rappels, médicaments, SOS) : faite, 121 tests automatiques.
- Phase 2 (mot d'activation, déclencheurs) : faite. 80 tests automatiques + test d'intégration Vosk réel
  sur voix de synthèse (mesures dans `tools/wake/README.md`).
- Phase 4 (vision) : faite, 141 tests automatiques au total (caméra simulée ; non testée sur un vrai appareil photo).
- Phase 5 (orientation, météo, infos, recherche) : faite, 209 tests automatiques au total.
- Phase 6 (lecteur façon Victor, mémos, agenda, mails) : faite, 241 tests automatiques au total.
- Phase 7 (tutoriel, diagnostic, sauvegarde, journal) : faite, 255 tests automatiques au total.
- Phase 8 (livres texte et PDF, podcasts hors ligne) : faite, 271 tests automatiques au total.
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

## 9 ter. Choix de la phase 4 (vision)

- **Logique dans `core/vision`** (`VisionSkill`, `VisionLogic`) : guidage de cadrage, couleurs, billets, dates,
  GS1, mémoire du document, tout testable sans Android. Android fournit `Camera`, `OcrEngine`, `BarcodeScanner`
  (`app/vision/AndroidVision.kt`).
- **CameraX sans aperçu** : un `LifecycleOwner` propre à la caméra, flux d'analyse (guidage, ~1 image/s) + photo pleine
  qualité pour la lecture. Utilisable depuis le service (type de service en avant-plan `microphone|camera`), donc
  écran verrouillé. La caméra et la lampe sont **toujours** fermées en fin de commande (`finally`).
- **ML Kit sur le téléphone** (OCR latin, codes-barres/DataMatrix) : gratuit, hors ligne, rapide. L'IA n'est appelée que
  pour décrire une scène, expliquer un courrier, chercher un objet ou juger un assortiment.
- **Courrier : on envoie le texte lu, pas la photo** : moins cher, plus fiable sur les montants, et rien d'autre
  (visages, pièce) ne quitte le téléphone.
- **Médicaments** : le DataMatrix GS1 donne le CIP13, la date de péremption et le lot ; la table CIP13 → nom/boîte est
  construite à la compilation depuis la base publique des médicaments (tâche Gradle `buildMedicationDb`, ~21 000 lignes,
  hors ligne). Jamais de posologie inventée : rappel « demandez au pharmacien ».
- **Billets** : chiffre lu (5…500) + couleur dominante + mot EURO ; en cas de désaccord, il le dit au lieu de deviner.
- **Lookout** reste conseillé pour le texte en continu : on l'ouvre à la voix plutôt que de le refaire.

## 9 quater. Choix de la phase 5 (orientation et infos)

- **Logique dans `core/info` et `core/skills/InfoSkill`** : phrases d'adresse, distances, directions (heures d'horloge
  avec la boussole, points cardinaux sinon), météo (codes WMO → français, oui/non d'abord pour « va-t-il pleuvoir »),
  navigation dans les titres, calculatrice. Clients HTTP dans `llm/WebServices.kt`, capteurs et cartes dans `app/info`.
- **Aucune IA pour l'essentiel** : adresse, lieux, météo, titres, calculs coûtent 0 requête. L'IA ne sert qu'à résumer
  un article ou des résultats de recherche (1 requête), et n'est pas indispensable (lecture de l'extrait sinon).
- **Services gratuits sans clé** : Géoplateforme IGN (BAN, géocodage inverse et recherche ; vérifié en réel),
  Overpass/OpenStreetMap (deux serveurs, rayon 3 km puis 15 km pour un village), Open-Meteo (modèles Météo-France),
  RSS France Info, Wikipédia. Tavily seulement si un proche saisit une clé gratuite (chiffrée comme les clés IA).
  Le « grounding » Google Search de Gemini n'est pas utilisé : payant sur le palier gratuit.
- **Guidage** : on ne refait pas un GPS piéton ; Google Maps en mode marche est lancé par intent (autorisé depuis le
  service car l'app est l'assistant par défaut). Avant, l'assistant annonce le lieu et demande « oui ».
- **Partage de position** : même relecture + « oui » que les SMS ; le message contient l'adresse et un lien de carte,
  le lien n'est jamais lu à voix haute.
- **Lieux enregistrés** stockés sur le téléphone (JSON dans les préférences). « Où suis-je » fonctionne hors ligne près d'un lieu enregistré.
- **L'IA peut appeler** `get_weather`, `where_am_i`, `nearby_place`, `web_search`, `calculate` (lecture seule) et
  `navigate_to`, `share_location`, `news_headlines` (traduits en intentions locales, donc confirmés comme à la voix).
- **Textes d'internet = données** : flux RSS analysés sans entités externes (protection XXE), HTML réduit au texte,
  consignes rappelant de ne jamais exécuter d'instruction trouvée dans une page.

## 9 quinquies. Choix de la phase 6 (lecteur et organisation)

- **Calqué sur le Victor Reader Stream** (guide HumanWare 4.8) pour que les habitudes restent : étagères `$VR…`,
  reprise par livre, navigation par niveaux (chapitre / section / page / temps), signets numérotés, touche « où suis-je »
  devenue « où j'en suis ? », minuterie de sommeil, enregistreur de notes.
- **Analyse DAISY dans `core/media/Library.kt`** (sans Android, testée) : DAISY 2.02 (`ncc.html` + SMIL `clip-begin`)
  et DAISY 3/NISO (`.ncx` + SMIL `clipBegin`). Le livre devient une suite de fichiers audio, chaque titre et page
  un couple (fichier, décalage). Encodage windows-1252 des vieux livres détecté. La liste des livres ne lit que le titre ;
  le livre complet est analysé à l'ouverture (grandes bibliothèques).
- **Accès aux fichiers par le sélecteur Android** (`ACTION_OPEN_DOCUMENT_TREE`, accès gardé) : pas de permission
  « tous les fichiers », un proche choisit le dossier une fois.
- **Lecture par Media3 ExoPlayer** dans le service (type de premier plan `mediaPlayback`) : MP3/AAC, flux HLS des radios,
  vitesse sans changer la hauteur de la voix, pause si le casque est débranché, gestion du focus audio (les annonces
  font une pause puis la lecture reprend).
- **Cycle avec la conversation** (`MediaSkill.beforeConversation/afterConversation`) : la lecture se met en pause avant
  d'écouter, reprend après la réponse ; une commande de navigation relance la lecture, « pause » et « stop » la laissent arrêtée ;
  un livre demandé démarre **après** l'annonce vocale. « Lis plus vite » règle le livre pendant une lecture, « parle plus vite » la voix.
- **Radios** : flux officiels Radio France intégrés, les autres via Radio Browser (annuaire libre). `usesCleartextTraffic`
  est activé car beaucoup de radios sont encore en http ; tous les autres appels sont en https.
- **Podcasts** : recherche par l'annuaire iTunes (gratuit, sans clé), épisodes lus en continu depuis le flux RSS, position gardée par épisode.
- **Musique** : fichiers du téléphone (MediaStore), sinon recherche envoyée à YouTube Music ; la musique d'une autre
  application se commande par MediaSession (grâce à l'accès aux notifications déjà demandé en phase 3).
- **Mémos** : MediaRecorder AAC, arrêt automatique après 2,5 s de silence (niveau du micro), 5 minutes au plus ; le mot
  d'activation est coupé pendant l'enregistrement.
- **Agenda** : `CalendarContract` (instances, donc récurrences incluses) ; ajout dans l'agenda principal modifiable, après « oui ».
- **Gmail par IMAP/SMTP + mot de passe d'application** (RESEARCH.md : les jetons OAuth de test expirent tous les 7 jours).
  JavaMail version Android. Lecture des non-lus, marqués lus quand on les écoute ; réponses avec `In-Reply-To`.
  « Réponds » et « lis le premier » s'appliquent au dernier sujet lu (SMS, mails ou actualités).
- **IA** : non nécessaire au lecteur ni à l'agenda ; sert au résumé d'un long mail. Fonctions ajoutées pour l'IA :
  `open_book`, `play_radio`, `play_podcast`, `play_music`, `sleep_timer`, `record_memo`, `add_note`, `add_to_shopping_list`,
  `add_event` (confirmé), `get_agenda`, `read_mail`, `send_mail` (confirmé).

## 9 sexies. Choix de la phase 7 (prise en main et entretien)

- **Tutoriel** (`SupportSkill`) : une suite de questions ouvertes (`Dialog.ask`). La réponse est **classée** par le routeur
  local mais jamais exécutée (sauf l'heure) : on s'entraîne à « appelle Paul » sans appeler. Deux essais par leçon, puis
  on passe ; la leçon en cours est mémorisée pour « reprends le tutoriel ». Le secours est expliqué, jamais essayé.
- **Diagnostic** : les vérifications viennent de l'app (`HealthChecks` : permissions, rôle d'assistant, optimisation de
  batterie, clés, accès aux notifications, dossier des livres, Gmail) ; le cœur les dit, les importantes d'abord, avec la solution.
- **Sauvegarde** (`core/support/Backup`) : JSON lisible, format versionné, liste blanche de clés (motif strict) ;
  exclut les données volatiles (compteurs, « déjà lu jusqu'à », dossier des livres dont l'autorisation ne se transfère pas,
  journal) ; les secrets sont dans un autre stockage et n'y sont jamais. La restauration remplace les données
  sauvegardables, garde les volatiles, puis reprogramme les rappels. Un fichier étranger ou d'une version future est refusé.
- **Journal** : 60 derniers échanges sur le téléphone ; « signale un problème » marque le dernier ; un proche le partage
  (texte) depuis les réglages. Rien n'est envoyé automatiquement.

## 9 septies. Choix de la phase 8 (livres texte, podcasts hors ligne)

- **Un livre texte est un livre comme les autres** : `core/media/TextBooks` le découpe en segments d'environ 400 caractères
  (coupés entre deux phrases) ; chaque segment est une « piste ». Chapitres (titres HTML/DOCX/EPUB, « Chapitre … » dans
  un TXT ou un PDF) et pages (sauts de page EPUB, pages PDF) pointent vers un segment. Toute la logique du lecteur
  (reprise, signets, chapitres, pages, « où j'en suis », pause pendant la conversation) est donc réutilisée telle quelle.
- **`TtsTextPlayer`** implémente la même interface que le lecteur audio avec la synthèse vocale Android (flux média,
  focus audio), en gardant toujours un segment d'avance dans la file pour éviter les silences. `MediaSkill` choisit le lecteur
  selon le livre ; passer d'un document à la radio coupe la synthèse. « Avance de 5 minutes » = environ 12 segments.
- **Formats** analysés sans bibliothèque externe dans le cœur (testés) : TXT/MD, HTML/XHTML, DOCX (XML Word),
  EPUB (container → OPF → ordre du spine ; EPUB chiffrés refusés ; taille décompressée limitée contre les archives piégées).
  **PDF** côté Android : PdfBox-Android pour la couche texte, page par page (lignes recollées en paragraphes, mots coupés
  réparés) ; une page sans texte (scan) est rendue par `PdfRenderer` puis lue par ML Kit, hors ligne. Résultat mis en cache.
- **Podcasts hors ligne** : `MediaSkill.syncDownloads` garde les N (3) derniers épisodes de chaque abonnement + l'épisode
  commencé, supprime le reste et les podcasts quittés. Automatique toutes les 6 heures **seulement en réseau non facturé**
  (wifi) ; à la voix aussi, mais jamais en données mobiles. Téléchargement OkHttp dans le stockage privé, fichier partiel
  renommé à la fin, refus s'il reste moins de 300 Mo. La lecture prend le fichier local quand il existe (positions inchangées,
  clé = adresse de l'épisode) ; sans internet, seuls les épisodes téléchargés sont proposés.

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
