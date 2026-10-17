# RESEARCH.md — Recherche préalable (phase 0)

Date de la recherche : 17 octobre 2026. Les chiffres de quotas et de prix changent souvent : ils sont à revérifier au moment de l'installation (voir « À vérifier sur place » en fin de document).

---

## 1. Besoins réels des personnes aveugles

### 1.1 Ce qui ressort des sources

- **Le smartphone est l'outil central.** Les associations françaises le décrivent comme « l'outil roi » des déficients visuels ([handicap.fr](https://informations.handicap.fr/a-smartphone-outil-roi-deficients-visuels-11722.php), [Okeenea](https://webzine.okeenea.com/smartphone-aveugle-malvoyant/)). L'enquête Access42 / FAF (2017, 5 pays francophones) indique que 89 % des répondants utilisent un lecteur d'écran sur mobile, avec une large majorité d'utilisateurs iPhone ; Android est minoritaire (14 %) ([Access42](https://access42.net/enquete-internationale-usage-technologies-assistance-deficients-visuels/?lang=fr)). Conséquence : les retours d'expérience Android en français sont rares, il faudra beaucoup tester sur le vrai téléphone.
- **L'apprentissage est la barrière principale.** Les gestes TalkBack/VoiceOver demandent « un long apprentissage » ([portail-handicap](https://portail-handicap.fr/handicap-visuel/smartphone-accessible-deficients-visuels)). Pour une personne peu à l'aise avec la technologie, la voix devient le mode principal. C'est exactement la cible du projet.
- **Déplacements :** l'étude Homère indique qu'environ 32 % des personnes aveugles ou malvoyantes utilisent un GPS pour se déplacer ([Okeenea](https://webzine.okeenea.com/smartphone-aveugle-malvoyant/)).
- **Lecture du courrier, des étiquettes, de l'argent, des couleurs** : ce sont les fonctions de base de toutes les applis de vision (Seeing AI, Lookout, Envision). Lookout reconnaît maintenant les euros, les étiquettes alimentaires (hors ligne après téléchargement) et les documents dans plus de 30 langues ([aide Google Lookout](https://support.google.com/accessibility/android/answer/9031274), [notes de version 2025](https://www.apkmirror.com/apk/google-inc/lookout)).
- **Lecture de livres :** la bibliothèque Éole de l'AVH propose gratuitement plus de 35 000 livres audio DAISY sur justificatif (carte d'invalidité ≥ 80 % ou certificat médical) ; la BNFA est une alternative ([BiblioPôle 49](https://www.bibliopole.maine-et-loire.fr/images/Lire_ecouter/lire_autrement/Ressources_DYS_BiblioPole.pdf), [EasyReader/Dolphin](https://yourdolphin.com/library?id=30)). Pas d'application Android officielle Éole trouvée ; EasyReader (Dolphin) sait lire Éole.
- **Lien social et aide humaine :** Be My Eyes (bénévoles en vidéo, plus de 10 millions de bénévoles en mars 2026) et Be My AI (description d'image conversationnelle, 36 langues) ([Be My Eyes App Store](https://apps.apple.com/app/id905177575), [presse-citron](https://www.presse-citron.net/telecharger/internet/be-my-eyes.html)).

### 1.2 Ce que disent les utilisateurs des assistants vocaux grand public

- **Gemini a remplacé Google Assistant sur Android (2025-2026) et beaucoup d'utilisateurs le trouvent moins fiable** pour les appels, minuteurs et alarmes : « Gemini as the Google Assistant is a disaster » ([Android Authority](https://www.androidauthority.com/gemini-vs-google-assistant-complaints-3721074/)). Selon Inc., la NFB (Fédération nationale des aveugles, États-Unis) a reçu des signalements de minuteurs, rappels, navigation et appels mal gérés, et de cas où « Gemini dit qu'une tâche est faite alors qu'elle ne l'est pas » ([Inc.](https://www.inc.com/georgia-fearn/google-is-finally-shutting-down-the-google-assistant-heres-what-android-users-need-to-know/91385340)). Je n'ai pas pu retrouver la source primaire de cette citation ; elle est cohérente avec les autres plaintes.
  → **Leçon n°1 : les actions simples (appel, minuteur, alarme) ne doivent PAS passer par un LLM.** Un routeur local déterministe, puis une vérification réelle du résultat (« l'alarme est bien enregistrée pour 7 h »), sont essentiels.
- **Siri a déjà perdu des commandes après une mise à jour** (lecture des messages vocaux, envoi d'emails), ce qui a fortement gêné les utilisateurs aveugles ([PhoneArena](https://www.phonearena.com/news/ios-15-update-removes-siris-features-for-the-blind_id135400), [teltarif](https://www.teltarif.de/arch/2021/kw39/s85910.html)).
  → **Leçon n°2 : ne pas dépendre d'un service externe qui peut changer sans prévenir.** Le cœur doit marcher hors ligne, et chaque dépendance externe doit avoir une solution de secours.
- **Les guides de conception des assistants commerciaux défavorisent les aveugles** : réponses limitées en longueur, sons d'interface (earcons) déconseillés, délais d'attente trop courts. Les utilisateurs expérimentés de lecteurs d'écran trouvent la voix trop lente ([synthèse « Reading Between the Guidelines »](https://a11y-paradise.onrender.com/reviews/69bc2ac17346cbd865b95fab)).
  → **Leçon n°3 : vitesse de parole réglable, délais d'écoute longs et réglables, earcons distincts, verbosité réglable.**
- **Les sons non verbaux accélèrent l'usage** : une étude avec 10 participants aveugles montre que des sons associés aux éléments d'interface réduisent le temps des tâches ; les sons « réels » sont mieux acceptés que les sons artificiels, et quelques signaux bien choisis valent mieux que beaucoup ([Petrie & Morley, Georgia Tech](https://repository.gatech.edu/entities/publication/dbed8318-ba1f-4112-95df-99d7b4935959), [Németh et al., ICAD 2011](https://smartlab.tmit.bme.hu/csapo/downloads/NemethOlaszyCsapo-icad2011-paper.pdf)).
  → **Leçon n°4 : 5 ou 6 earcons maximum, très différents les uns des autres, toujours les mêmes.**

### 1.3 Synthèse des besoins par domaine (et ce que le projet doit couvrir)

| Domaine | Difficultés typiques | Réponse proposée |
|---|---|---|
| Communication | Composer un numéro, lire SMS/WhatsApp, savoir qui appelle | Appel par nom/alias, annonce de l'appelant, lecture des notifications, réponse vocale |
| Courrier / administratif | Lire une lettre, comprendre ce qu'il faut faire, trouver un montant | OCR local + explication IA + navigation (« relis le montant ») |
| Courses / cuisine | Identifier un produit, dates de péremption, allergènes, minuteurs | Codes-barres + Open Food Facts, OCR des dates, minuteurs nommés |
| Argent | Reconnaître les billets | Vision IA + heuristique locale (chiffre + couleur) |
| Habillement | Couleurs, assortiment | Couleur dominante locale + avis IA |
| Santé | Lire les boîtes, ne pas oublier ses médicaments | Lecture boîte + rappels récurrents + **journal « ai-je pris mon médicament ? »** |
| Déplacements | Savoir où on est, aller quelque part, transports | Adresse (IGN/BAN), lieux proches (OSM), itinéraire (Maps), favoris |
| Sécurité | Chute, urgence, arnaques | SOS, détection de chute, **alerte arnaque SMS**, « je vais bien » quotidien |
| Loisirs | Radio, musique, livres audio, actualités | Radio Browser, MediaSession, livres audio avec reprise, RSS |
| Autonomie générale | Heure, date, batterie, météo | Routeur local, Open-Meteo |

---

## 2. Applications existantes : forces, frustrations, manques

| App | Forces | Limites constatées | Ce qu'on en retient |
|---|---|---|---|
| **Seeing AI** (Microsoft) | Texte, scènes, visages, très structurée par « canaux » | Description de scène et documents nécessitent le réseau ([UWM](https://sites.uwm.edu/mdlaug/?p=3799)) | Modes clairs et prévisibles |
| **Google Lookout** (Android) | Euros, étiquettes alimentaires hors ligne, documents, mode exploration continue | Pilotage tactile, pas vocal ; pas d'assistant conversationnel | **À installer en complément** ; on peut l'ouvrir par la voix |
| **Be My Eyes / Be My AI** | Humain disponible 24/7, IA conversationnelle | Pas de commande vocale globale | Commande « appelle un bénévole » qui ouvre l'app |
| **Envision / Ally** | Lunettes, assistant Ally basé sur Gemini ([Google AI](https://ai.google.dev/competition/projects/ally)) | Payant pour une partie | Preuve que Gemini convient à la description pour aveugles |
| **Soundscape → VoiceVista (iOS) / Soundscape Scottish Tech Army (iOS + Android depuis juin 2026)** | Audio 3D, balises sonores, cartes hors ligne ([Double Tap](https://doubletaponair.com/community-powered-accessible-apps-you-need-to-try-soundscape-comes-to-android-with-double-tap/)) | App séparée, à manipuler au toucher | **Ne pas réimplémenter l'audio 3D** : recommander l'app, ouvrir par la voix ; reprendre l'idée « qu'y a-t-il autour » en version vocale simple |
| **Gemini (assistant Google)** | Puissant, gratuit | Moins fiable que l'ancien Assistant pour les actions de base, confirme à tort | Routeur local + vérification des actions |
| **Jieshuo** | Lecteur d'écran très personnalisable | Propriétaire, fonctions payantes ([BRLTTY](https://brltty.app/pipermail/brltty/2020-April/017239.html)) | — |
| **Dicio** (open source) | Assistant vocal Android libre, français, hors ligne (Vosk), mot d'activation (openWakeWord) | Peu de fonctions, mot d'activation « Hey Dicio » à réentraîner | Bonne référence d'architecture (GPL : on s'inspire, on ne copie pas) |

**Manque principal du marché :** aucune app ne combine (1) un mot d'activation personnalisable en français, (2) des actions téléphone fiables et confirmées, (3) la vision, (4) le tout entièrement à la voix et (5) gratuitement. Les apps de vision sont tactiles ; les assistants vocaux ne sont pas pensés pour les aveugles.

---

## 3. Constats techniques importants (qui changent le cahier des charges)

1. **Palier gratuit Gemini beaucoup plus bas qu'attendu.** Google ne publie plus de tableau officiel ([doc rate-limits](https://ai.google.dev/gemini-api/docs/rate-limits) renvoie vers AI Studio). Mesure indépendante du 2 septembre 2026 sur un projet neuf ([DEV](https://dev.to/romeroyang/geminis-free-tier-measured-20-requests-a-day-and-google-no-longer-publishes-the-number-4gf2)) :
   - Flash 3.x (`gemini-3.8-flash`, `gemini-3.6-flash`…) : **5 requêtes/min, 20 requêtes/jour**.
   - Flash-Lite (`gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`) : **15 requêtes/min, 500 requêtes/jour**.
   - Les modèles 2.5 renvoient 404 pour les nouvelles clés. Remise à zéro à minuit heure du Pacifique (9 h du matin en France).
   - D'autres sources parlent de 1 500/jour ([klymentiev](https://klymentiev.com/blog/gemini-api-free-tier)) mais sans mesure. **Le chiffre réel se lit dans AI Studio ou dans la réponse 429 (`quotaValue`).**
   → **Défaut : Flash-Lite**, Flash seulement pour les cas difficiles (documents complexes), et le routeur local doit absorber la majorité des demandes.
2. **Recherche Google (grounding) non gratuite sur les modèles 3.x en palier gratuit** ([tarifs](https://ai.google.dev/gemini-api/docs/pricing)). En palier **payant**, 5 000 recherches/mois sont offertes. Brave a supprimé son offre gratuite en février 2026 ; Tavily offre ~1 000 recherches/mois gratuites ([Firecrawl](https://www.firecrawl.dev/blog/brave-search-api-alternatives), [Parallel](https://parallel.ai/articles/best-free-web-search-api)).
3. **Live API Gemini** (`gemini-3.8-live`) : gratuite en palier gratuit selon la page tarifs (audio, image ≤ 1 image/s, appel de fonctions) ([Live API](https://ai.google.dev/gemini-api/docs/live)). Quotas gratuits non publiés : à mesurer. Option pour la description continue.
4. **Le palier gratuit Gemini utilise les données pour améliorer les produits Google** ; pas le palier payant ([tarifs](https://ai.google.dev/gemini-api/docs/pricing)). À écrire dans le README.
5. **Claude Haiku 5.5** (`claude-haiku-5-5`) coûte 0,10 $ / M de jetons en entrée et 0,50 $ en sortie. Une requête typique (~2 000 jetons en entrée, 150 en sortie) coûte ~0,0003 $ ; 50 requêtes/jour ≈ **0,45 $/mois**. Pas de palier gratuit, mais c'est un secours très bon marché.
6. **Picovoice a fermé son palier gratuit le 30 juin 2026** ([forum Home Assistant](https://community.home-assistant.io/t/porcupine-free-tier-shutdown-alternatives-for-home-assistant-voice-users/1012382)). Porcupine reste une option uniquement pour qui possède déjà une clé et un modèle `.ppn`.
7. **sherpa-onnx : pas de modèle de mot-clé (KWS) français.** Les modèles KWS existants sont chinois et anglais ([doc KWS](https://k2-fsa.github.io/sherpa/onnx/kws/index.html)). Le principe « mot-clé arbitraire sans entraînement » fonctionne, mais à partir de jetons anglais/chinois : un prénom français y sera approximé. Il existe en revanche un modèle de **reconnaissance continue** français (`sherpa-onnx-streaming-zipformer-fr-2023-04-14`, encodeur int8 121 Mo, entraîné sur CommonVoice).
8. **Vosk** : modèle français léger `vosk-model-small-fr-0.22` (41 Mo, Apache 2.0, WER ~20-24 %) qui accepte une **grammaire restreinte à l'exécution** ([Vosk](https://alphacephei.com/vosk/models)). C'est le candidat le plus sûr pour un mot d'activation français arbitraire.
9. **openWakeWord** : très bon, mais exige d'entraîner un modèle par nom (1 h+ sur Colab, voix françaises Piper) ([colab 2026](https://github.com/alfiedennen/openwakeword-colab-2026)). Incompatible avec « changer de nom à la voix ». Gardé comme option avancée.
10. **Android 14+ interdit de démarrer un service micro depuis le démarrage du téléphone (BOOT_COMPLETED)**, et interdit de créer un service micro depuis l'arrière-plan ([Android 15](https://developer.android.com/about/versions/15/changes/foreground-service-types), [restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)). **Exception officielle : l'app qui fournit le `VoiceInteractionService`**, c'est-à-dire l'app choisie comme **assistant numérique par défaut**. → l'app doit s'enregistrer comme assistant par défaut. Bonus : l'appui long sur le bouton d'accueil/assistant la déclenche, et le rôle donne accès au journal d'appels et aux SMS ([RoleManagerCompat](https://developer.android.com/reference/androidx/core/role/RoleManagerCompat)).
11. **Gmail API en usage personnel** : en mode « Test », les jetons de rafraîchissement OAuth expirent **tous les 7 jours** pour les scopes Gmail ; la publication exige une vérification longue (CASA) ([Nango](https://www.nango.dev/docs/integrations/all/google-mail), [unified.to](https://unified.to/blog/why_google_oauth_returns)). → Pour la fiabilité, **IMAP/SMTP avec un mot de passe d'application** est préférable (n'expire pas).
12. **API Adresse (BAN) déplacée à l'IGN** : l'ancienne URL `api-adresse.data.gouv.fr` est décommissionnée depuis fin janvier 2026, remplacée par `data.geopf.fr/geocodage` (géocodage inverse inclus) ([data.gouv.fr](https://www.data.gouv.fr/fr/posts/lapi-adresse-de-la-base-adresse-nationale-est-transferee-a-lign-10/), [CNIG](https://cnig.gouv.fr/IMG/pdf/cnig_gt_assurances_19062026.pdf)).
13. **Open-Meteo** : gratuit pour usage non commercial, < 10 000 appels/jour, sans clé, CC-BY 4.0, inclut les modèles AROME de Météo-France ([conditions](https://open-meteo.com/en/terms)).
14. **Transports** : en Île-de-France, les prochains passages temps réel passent par **PRIM** (Île-de-France Mobilités, clé gratuite) ; ailleurs, transport.data.gouv.fr référence des flux GTFS-RT / SIRI pour 158 réseaux ([data.gouv.fr](https://www.data.gouv.fr/datasets/api-temps-reel-transilien/informations), [norimono](https://www.data.gouv.fr/reuses/norimono-live)). → **Dépend de la ville du père** (question ouverte).
15. **Billets en euros** : pas de modèle TFLite euro open source prêt à l'emploi trouvé ([recherche](https://journal.unnes.ac.id/journals/sji/article/download/28930/7400/125584), [Sensors 2017](https://pmc.ncbi.nlm.nih.gov/articles/PMC5298757)). → IA vision + heuristique locale (OCR du chiffre + couleur dominante), Lookout en complément.
16. **Détection de chute** : pas de bibliothèque Android maintenue ; les algorithmes à seuils avec phase de chute libre + impact + immobilité réduisent les faux positifs ; les seuils fixes anciens ne sont plus valables ([SciTePress 2015](https://www.scitepress.org/Papers/2015/52808)). → algorithme maison en 3 phases, réglable, désactivé par défaut au début.
17. **Reconnaissance vocale Android** : `createOnDeviceSpeechRecognizer` (API 31+) existe, la disponibilité du français hors ligne doit être testée sur l'appareil (`checkRecognitionSupport`, `triggerModelDownload`). L'API n'est pas faite pour l'écoute continue ([SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)). → OK pour la commande après le mot d'activation, pas pour le mot d'activation.

---

## 4. Bonnes pratiques d'interface vocale retenues

1. **Toujours un retour immédiat** (< 300 ms) : earcon « j'écoute » dès le mot d'activation, earcon « je réfléchis » si la réponse prend plus d'une seconde.
2. **Sons courts, distincts, constants** : écoute (montée), réflexion (tic discret en boucle), succès (deux notes montantes), erreur (deux notes descendantes), confirmation demandée (question), annulé. Chacun avec une vibration différente.
3. **Confirmation explicite pour tout ce qui sort du téléphone ou coûte** (appel, SMS, mail, SOS, achat) : relecture complète, puis « oui » / « non ». Le « oui » est reconnu par une grammaire restreinte (oui, vas-y, envoie, d'accord / non, annule, stop) pour éviter les erreurs.
4. **Ne jamais deviner** : en cas de doute (score faible, deux contacts possibles), poser une question fermée (« Paul Martin ou Paul Durand ? »).
5. **Dire ce qui a été fait réellement**, après vérification (alarme lue dans le système, SMS marqué envoyé).
6. **Réponses courtes par défaut**, détails sur demande (« dis-m'en plus »), « répète » toujours disponible.
7. **Interruption à tout moment** (nom + « stop », bouton volume, bouton du casque).
8. **Délais généreux** : laisser 8 secondes pour commencer à parler, prolonger si la personne parle lentement.
9. **Coexistence avec TalkBack** : utiliser le focus audio, ne pas parler en même temps que TalkBack, ne pas capter les gestes de TalkBack.
10. **Erreurs utiles** : dire quoi faire (« pas de réseau, je réessaierai quand il reviendra »).

---

## 5. À vérifier sur place (le jour de l'installation)

- Quotas réels Gemini du projet dans AI Studio (page « Rate limit »).
- Disponibilité de la reconnaissance vocale Google française hors ligne sur le téléphone.
- Qualité du mot d'activation choisi dans le bruit (voir checklist de tests manuels).
- Ville et réseau de transport du père (pour l'API temps réel).
- Comportement des boutons volume et du casque écran éteint sur ce modèle de téléphone.

## 6. Sources

Toutes les sources sont citées en lien dans le texte. Les principales :
- Google AI : [tarifs](https://ai.google.dev/gemini-api/docs/pricing), [modèles](https://ai.google.dev/gemini-api/docs/models), [Live API](https://ai.google.dev/gemini-api/docs/live), [rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)
- Mesure du palier gratuit : [DEV Community, sept. 2026](https://dev.to/romeroyang/geminis-free-tier-measured-20-requests-a-day-and-google-no-longer-publishes-the-number-4gf2)
- Android : [restrictions FGS](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Android 15 FGS](https://developer.android.com/about/versions/15/changes/foreground-service-types), [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- sherpa-onnx : [KWS](https://k2-fsa.github.io/sherpa/onnx/kws/index.html), [modèles streaming](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)
- Vosk : [modèles](https://alphacephei.com/vosk/models) ; Dicio : [GitHub](https://github.com/Stypox/dicio-android)
- Accessibilité : [Access42](https://access42.net/enquete-internationale-usage-technologies-assistance-deficients-visuels/?lang=fr), [Android Authority](https://www.androidauthority.com/gemini-vs-google-assistant-complaints-3721074/), [Inc.](https://www.inc.com/georgia-fearn/google-is-finally-shutting-down-the-google-assistant-heres-what-android-users-need-to-know/91385340), [Lookout](https://support.google.com/accessibility/android/answer/9031274), [Houston Lighthouse](https://houstonlighthouse.org/?p=24718)
- Données : [Open-Meteo](https://open-meteo.com/en/terms), [IGN / BAN](https://www.data.gouv.fr/fr/posts/lapi-adresse-de-la-base-adresse-nationale-est-transferee-a-lign-10/), [PRIM / Transilien](https://www.data.gouv.fr/datasets/api-temps-reel-transilien/informations)
- Picovoice : [fin du palier gratuit](https://community.home-assistant.io/t/porcupine-free-tier-shutdown-alternatives-for-home-assistant-voice-users/1012382)
- Gmail OAuth : [Nango](https://www.nango.dev/docs/integrations/all/google-mail)


## Complément phase 6 : le Victor Reader Stream (17 octobre 2026)

Le père utilise un **Victor Reader Stream** (HumanWare). Le lecteur de l'app en reprend le fonctionnement pour ne pas changer ses habitudes :

- **Étagères et dossiers de la carte SD** : `$VRDTB` (livres DAISY/NISO, un sous-dossier par livre), `$VROtherBooks` (livres audio),
  `$VRMusic`, `$VRPodcasts`, `$VRText`, `$VRNotes` (notes vocales), `$VRAudible`, `$VRInternetRadio`
  ([guide d'utilisation Stream 4.8, HumanWare](https://www.humanware.com/wp-content/uploads/2024/11/Victor-Reader-Stream-4.8-User-Guide-6.html)).
- **Navigation** par niveaux (chapitre, section, page, phrase) et **sauts dans le temps** (30 s, 1, 5, 10, 30 min) ; avance/recul de 5 s.
- **Signets** numérotés (1 à 99 998), signets vocaux et surlignés ; liste, suppression d'un ou de tous.
- **Reprise automatique** de la position et des signets pour chaque livre.
- **Touche « où suis-je »** : titre, page ou section, pourcentage et temps restant.
- **Minuterie de mise en veille** : 15, 30, 45, 60 minutes ou personnalisée.
- **Enregistreur** : notes vocales numérotées dans `$VRNotes`.
- **Radio Internet et podcasts** (abonnements, épisodes conservés).
- Autres sources : [RNIB, Victor Stream 3](https://shop.rnib.org.uk/victor-stream-3-94322),
  [Accessolutions](https://accessolutions.fr/shop/lecteur-enregistreur-victor-stream3),
  [évaluation CERTAM / AVH](https://www.avh.asso.fr/implantations/certam/evaluations-de-produits/victor-reader-stream-3-version-131).

Conséquence : l'app lit directement le contenu de la carte du Victor copié sur le Pixel. Les signets du Victor ne sont pas
importés (format interne non documenté). Le Victor garde l'avantage des touches physiques ; le Pixel apporte la voix, l'agenda, les mails.
