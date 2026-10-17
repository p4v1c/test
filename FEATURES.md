# FEATURES.md — Fonctionnalités priorisées

Légende :
- **Priorité** : P0 = indispensable (v1), P1 = important, P2 = bonus.
- **Hors ligne** : ✅ entièrement local · 🟡 local avec amélioration en ligne · ❌ réseau requis.
- **Coût** : « 0 » = aucun appel IA ; sinon nombre approximatif de requêtes IA. Avec Gemini Flash-Lite gratuit (~500 req/jour) tout reste gratuit ; avec Claude Haiku 5.5 en secours, ~0,0003 $ par requête texte, ~0,001 $ avec image.
- 💡 = idée ajoutée suite aux recherches (pas dans la demande initiale).

## État d'avancement

- Phase 1 (socle) ✅ · Phase 2 (mot d'activation, déclencheurs) ✅ · Phase 3 (communication, organisation, sécurité) ✅ · Phase 4 (vision) ✅ · Phase 5 (orientation, météo, infos, recherche) ✅ · Phase 6 (lecteur façon Victor, mémos, agenda, mails) ✅ · Phase 7 (finitions) ✅
- Réalisé en phase 3 : appels (nom, surnom, favori, numéro, rappel du dernier), annonce des appels entrants et décrocher
  à la voix ou au casque, journal d'appels, SMS (lecture, dictée, relecture, envoi vérifié, réponse), notifications
  (lecture, annonce, réponse rapide), alerte arnaque, réveils, minuteurs nommés, rappels ponctuels et récurrents,
  médicaments avec journal et relances, SOS (voix ou volume haut 3 s), alertes batterie et réseau, « tout va bien ? »
  quotidien, téléphone retrouvé par SMS.
- Réalisé en phase 4 : lecture guidée (cadrage à la voix, lampe auto), documents multipages et questions hors ligne
  (« relis le montant », date limite), explication de courrier (texte seul envoyé à l'IA), description de scène, billets
  (hors ligne), couleurs et assortiment, détecteur de lumière, codes-barres Open Food Facts (allergènes, Nutri-Score),
  dates de péremption, boîtes de médicaments (DataMatrix + base publique des médicaments, hors ligne), recherche d'objet,
  ouverture de Lookout.
- Vision reportée : description des photos reçues (avec Gmail, phase 6), mode continu Live API et reconnaissance de proches (P2).
- Réalisé en phase 5 : « où suis-je » (IGN), lieux proches (OpenStreetMap) avec distance et direction en heures
  d'horloge, lieux enregistrés à la voix, guidage à pied par Google Maps, partage de position par SMS, météo
  (Open-Meteo, conseils parapluie/verglas/chaleur), actualités France Info par thème + journal régional au choix,
  résumé d'article, recherche web (Wikipédia, Tavily si clé), calculatrice et conversion euros/francs.
- Reporté : vigilance météo annoncée le matin et horloge parlante (P2), repli Nominatim hors de France (inutile ici),
  ouverture de Soundscape (P2).
- Réalisé en phase 6 (sur le modèle du Victor Reader Stream que votre père utilise) : bibliothèque lue dans le dossier du
  Victor copié sur le téléphone ($VRDTB, $VROtherBooks, $VRMusic, $VRPodcasts, $VRNotes), livres DAISY 2.02 et 3 et
  dossiers MP3, reprise par livre, chapitres/sections/pages, sauts dans le temps, signets numérotés, « où j'en suis »,
  vitesse, minuterie de sommeil, pause automatique quand on parle à l'assistant ; radios (Radio France + Radio Browser,
  favoris) ; podcasts (abonnements, reprise par épisode) ; musique locale ou application de musique ; mémos vocaux ;
  notes écrites ; liste de courses ; agenda (lecture, ajout confirmé) ; point du jour ; Gmail (lire, résumer, répondre,
  écrire, alerte arnaque).
- Réalisé en phase 7 : tutoriel vocal en 8 leçons (exercices sans rien déclencher, reprise où on s'était arrêté, bouton
  à l'écran, proposé au premier lancement), « est-ce que tout marche ? » (14 vérifications dites à voix haute avec la
  solution), sauvegarde automatique hebdomadaire + à la voix + export/import depuis les réglages (sans clés ni mots de
  passe, avec résumé et confirmation), « signale un problème » + journal local partageable, README réorganisé
  (premiers pas, dépannage, sauvegarde).
- Reporté : livres en texte seul et PDF lus par la synthèse (étagère Texte du Victor), téléchargement des podcasts pour
  écoute hors ligne, import des signets du Victor, Google Meet (P2).

## Décisions validées (17 octobre 2026)

- **Téléphone cible : un Google Pixel neuf** (Android récent, reconnaissance vocale française hors ligne de Google, pas de root). Le SmartVision actuel (clavier physique) n'est pas la cible. Les fonctions « bonus root » passent en option lointaine.
- **Pas de WhatsApp** : les fonctions WhatsApp sont retirées. **Google Meet** éventuellement plus tard (ouvrir Meet ou appeler un proche via Meet, P2).
- **Gmail : oui**, via IMAP/SMTP avec mot de passe d'application (P1).
- **Pas de transports en commun** (petit village) : fonction retirée.
- **Pas de détection de chute** : fonction retirée.
- **Casque ou enceinte Bluetooth** : à prendre en charge (bouton du casque comme déclencheur, sortie audio sur l'enceinte).
- **Palier gratuit Gemini pour commencer** ; la facturation pourra être activée plus tard. L'utilisation des données par Google pour l'entraînement est acceptée.

Principe directeur : **tout ce qui peut être fait sans IA est fait sans IA** (routeur local). L'IA sert à comprendre les demandes libres, résumer, décrire des images et répondre aux questions.

---

## 1. Socle (P0)

| Fonction | Pourquoi | Hors ligne | Coût |
|---|---|---|---|
| Mot d'activation = nom personnalisable | Mains libres, sans regarder l'écran | ✅ | 0 |
| Déclencheurs alternatifs : bouton casque, appui long volume, appui long accueil (assistant par défaut), bouton dans la notification, gros bouton écran | Le mot d'activation peut échouer dans le bruit | ✅ | 0 |
| Earcons + vibrations par état (écoute, réflexion, succès, erreur, confirmation, annulé) | Savoir où on en est sans voir | ✅ | 0 |
| Routeur local (heure, date, batterie, réseau, volume, vitesse de parole, aide…) | Rapidité, fiabilité, quota | ✅ | 0 |
| IA interchangeable (Gemini Flash-Lite → Gemini Flash → Claude Haiku) avec bascule annoncée | Questions libres | ❌ | 1 |
| Confirmation vocale avec relecture pour toute action sensible | Sécurité | ✅ | 0 |
| « Stop » / interruption à tout moment | Contrôle | ✅ | 0 |
| « Répète » / « qu'est-ce que tu as dit ? » 💡 | On rate souvent une phrase dehors | ✅ | 0 |
| Vitesse, hauteur, volume, verbosité réglables à la voix | Confort, utilisateurs expérimentés parlent vite | ✅ | 0 |
| Mode hors ligne annoncé (« je n'ai pas internet, je peux quand même… ») | Fiabilité | ✅ | 0 |
| Mode privé (aucun envoi à l'IA) | Vie privée | ✅ | 0 |
| Compteur de requêtes/jour + annonce avant la limite | Coût nul garanti | ✅ | 0 |
| Aide vocale par catégories + tutoriel au premier lancement | Utilisable sans formation | ✅ | 0 |
| Réglages pour l'aidant (clés, nom, contacts, permissions) + export/import | Configuration une seule fois | ✅ | 0 |

## 2. Communication

| Fonction | P | Pourquoi | Hors ligne | Coût |
|---|---|---|---|---|
| Appeler un contact par nom ou alias (« mon fils »), confirmation | P0 | Besoin n°1 | ✅ | 0 |
| Annonce de l'appelant, décrocher / refuser / raccrocher / haut-parleur à la voix | P0 | Appels entrants | ✅ | 0 |
| « Qui m'a appelé ? » (journal d'appels), rappeler | P0 | Appels manqués | ✅ | 0 |
| SMS : lire, dicter, relire, envoyer (confirmation) | P0 | | ✅ (dictée 🟡) | 0 |
| Notifications des autres apps lues (WhatsApp, Messenger…), filtre par app | P0 | | ✅ | 0 |
| Réponse rapide via l'action de réponse de la notification | P1 | Répondre à un message sans ouvrir l'app | ✅ | 0 |
| 💡 **Alerte arnaque** à la lecture d'un SMS/notification (lien suspect, « colis », « CPF », « Ameli », urgence d'argent) | P0 | Les personnes âgées et aveugles sont des cibles fréquentes ; elles ne voient pas l'expéditeur | 🟡 (règles locales + avis IA) | 0-1 |
| 💡 Contacts favoris numérotés (« appelle le numéro 1 ») | P1 | Plus fiable que la reconnaissance d'un nom difficile | ✅ | 0 |
| ~~Appel vidéo WhatsApp~~ → 💡 appel Google Meet à un proche (« montre à ma fille ») | P2 | Aide humaine visuelle | ❌ | 0 |
| 💡 « Appelle un bénévole » → ouvre Be My Eyes | P1 | Aide humaine quand l'IA ne suffit pas | ❌ | 0 |
| Emails : lire, résumer, répondre, envoyer (relecture + confirmation), **via IMAP/SMTP + mot de passe d'application** | P1 | | ❌ | 1 par résumé |
| ~~Messages vocaux WhatsApp~~ | — | Retiré (pas de WhatsApp) | — | — |
| Messagerie vocale opérateur | P2 | Dépend de l'opérateur (appel au 888, etc.) | ❌ | 0 |

## 3. Vision (caméra arrière, lampe auto si sombre)

| Fonction | P | Pourquoi | Hors ligne | Coût |
|---|---|---|---|---|
| Lecture de texte instantanée (ML Kit OCR) | P0 | Courrier, étiquettes | ✅ | 0 |
| « C'est quoi cette lettre ? que dois-je faire ? » (OCR + IA) | P0 | Administratif | ❌ | 1 |
| Guidage de cadrage (« texte coupé à droite, recule un peu ») par voix et sons | P0 | Sans cela, 1 photo sur 2 est ratée | ✅ | 0 |
| Description de scène (« qu'est-ce qu'il y a devant moi ? ») | P0 | | ❌ | 1 |
| Codes-barres → Open Food Facts (nom, allergènes, Nutri-Score) | P1 | Courses, placard | 🟡 | 0 |
| Dates de péremption (OCR ciblé sur les motifs de date) | P1 | | ✅ | 0 |
| Billets en euros (IA + heuristique locale chiffre/couleur) | P1 | | 🟡 | 0-1 |
| Couleur dominante (local) + « est-ce que ça va ensemble ? » (IA) | P1 | Habillement | 🟡 | 0-1 |
| Détecteur de lumière (son dont la hauteur suit la luminosité) | P1 | Lampes restées allumées | ✅ | 0 |
| Boîte de médicament : nom, dosage, posologie écrite + rappel « vérifiez avec le pharmacien » | P1 | | 🟡 | 0-1 |
| Documents multipages + navigation (« relis le montant », « page suivante ») | P1 | Factures | 🟡 | 1 par document |
| Description des photos reçues (MMS, Gmail, galerie via « partager vers ») | P1 | Lien social | ❌ | 1 |
| Recherche d'objet (« où sont mes clés ? ») : détection ML Kit + guidage sonore gauche/droite | P2 | Fiabilité limitée pour les petits objets | 🟡 | 0-1 |
| Mode description continue (Live API, activation explicite, durée limitée) | P2 | Coûteux, quotas inconnus | ❌ | élevé |
| Reconnaissance de proches enregistrés (consentement) | P2 | Complexe, sensible (RGPD) | 🟡 | — |
| 💡 Ouvrir Google Lookout par la voix pour un mode précis | P1 | Lookout fait déjà très bien billets/étiquettes hors ligne | ✅ | 0 |

## 4. Orientation et déplacements

| Fonction | P | Pourquoi | Hors ligne | Coût |
|---|---|---|---|---|
| « Où suis-je ? » (adresse via IGN/BAN, repli Nominatim) | P0 | | ❌ (GPS seul ✅) | 0 |
| Lieux proches (pharmacie, boulangerie, arrêt de bus) avec distance et direction en heures d'horloge | P1 | Inspiration Soundscape | ❌ | 0 |
| Itinéraire piéton guidé (Google Maps en mode marche par intent) | P1 | Maps est fiable et parle | ❌ | 0 |
| Lieux favoris enregistrés à la voix (« enregistre ici comme maison ») + guidage | P1 | | ✅ | 0 |
| Partage de position avec un proche (SMS avec lien) | P0 | Sécurité | 🟡 | 0 |
| ~~Transports en commun~~ | — | Retiré (petit village, ne les prend pas) | — | — |
| 💡 Ouvrir Soundscape (Android, juin 2026) pour l'audio 3D | P2 | Inutile de réimplémenter | ✅ | 0 |

## 5. Infos et recherche

| Fonction | P | Hors ligne | Coût |
|---|---|---|---|
| Heure, date, jour, batterie, réseau | P0 | ✅ | 0 |
| Météo (Open-Meteo, modèles Météo-France) | P0 | ❌ | 0 |
| 💡 Vigilance météo (canicule, orages) annoncée le matin | P2 | ❌ | 0 |
| Actualités (RSS France Info, Le Monde…), titres puis « lis l'article » | P1 | ❌ | 0 (1 si résumé) |
| Questions générales à l'IA | P0 | ❌ | 1 |
| Recherche web résumée : Wikipédia (gratuit) ; Tavily (clé gratuite, ~1 000/mois) ; grounding Gemini si facturation activée | P1 | ❌ | 1-2 |
| Horloge parlante périodique (option) | P2 | ✅ | 0 |
| Calculatrice et conversions | P1 | ✅ | 0 |

## 6. Divertissement

| Fonction | P | Hors ligne | Coût |
|---|---|---|---|
| Contrôle de la musique en cours (pause, suivant, volume) via MediaSession | P0 | ✅ | 0 |
| Lancer YouTube Music / Spotify / Deezer par intent de recherche | P1 | ❌ | 0 |
| Musique locale | P1 | ✅ | 0 |
| Radios en direct (liste française préconfigurée + Radio Browser) | P0 | ❌ | 0 |
| Podcasts (RSS) avec reprise de position | P1 | 🟡 | 0 |
| Livres audio MP3/DAISY avec reprise, navigation par chapitre | P1 | ✅ | 0 |
| 💡 Aide à l'inscription à Éole (AVH) dans le README + lecture des livres DAISY téléchargés | P2 | ✅ | 0 |

## 7. Organisation et vie quotidienne

| Fonction | P | Hors ligne | Coût |
|---|---|---|---|
| Alarmes et minuteurs (app Horloge système), **minuteurs nommés multiples** 💡 (« minuteur pâtes 10 minutes ») | P0 | ✅ | 0 |
| Rappels ponctuels et récurrents | P0 | ✅ | 0 |
| Rappels de médicaments récurrents | P0 | ✅ | 0 |
| 💡 **Journal de prise** : « j'ai pris mon médicament » / « ai-je pris mon médicament ce matin ? » | P0 | ✅ | 0 |
| Agenda : lire / ajouter des rendez-vous (CalendarContract) | P1 | ✅ | 0 |
| Mémos vocaux, notes, liste de courses | P1 | ✅ | 0 |
| Lecture de PDF à voix haute | P2 | ✅ | 0 |
| 💡 **Briefing du matin** (heure, météo, rendez-vous, médicaments, messages non lus) | P1 | 🟡 | 0 |
| 💡 Annonce de branchement/débranchement du chargeur et du niveau | P1 | ✅ | 0 |

## 8. Sécurité

| Fonction | P | Hors ligne | Coût |
|---|---|---|---|
| SOS : « [nom], au secours » ou appui long volume → compte à rebours 10 s annulable → SMS avec position aux contacts d'urgence → appel du premier contact | P0 | ✅ (SMS/appel) | 0 |
| ~~Détection de chute~~ | — | Retirée à la demande | — |
| Retrouver le téléphone (sifflement, ou mot-clé) | P1 | ✅ | 0 |
| 💡 **Retrouver le téléphone par SMS** : un contact de confiance envoie un code → le téléphone sonne fort et répond avec sa position | P1 | ✅ | 0 |
| Alertes batterie faible, perte de réseau | P0 | ✅ | 0 |
| 💡 **« Je vais bien » quotidien** : si aucune interaction avant une heure choisie, l'app demande « tout va bien ? », puis prévient un proche sans réponse | P1 | ✅ | 0 |
| 💡 Avertissement numéro inconnu / démarchage à l'appel entrant | P2 | ✅ | 0 |

## 9. Bonus root

| Fonction | P | Hors ligne |
|---|---|---|
| Wi-Fi, Bluetooth, mode avion, redémarrage à la voix | P2 | ✅ |
| Service protégé (priv-app / whitelist), permissions accordées automatiquement | P2 | ✅ |

---

## Ce que je déconseille ou repousse (et pourquoi)

- **Achats à la voix** : risque d'erreur et d'arnaque trop élevé pour un gain faible. Non prévu.
- **Reconnaissance faciale des proches** : données biométriques (RGPD), consentement, fiabilité moyenne. P2, seulement si demandé après la v1.
- **Audio spatialisé type Soundscape** : l'app Soundscape existe maintenant sur Android ; on l'ouvre par la voix.
- **Gmail API OAuth** : jetons qui expirent toutes les semaines en usage perso → IMAP/SMTP à la place.
- **Mode description continue par défaut** : quotas inconnus, batterie ; seulement à la demande et limité dans le temps.

## Budget IA quotidien estimé (usage normal)

| Usage | Requêtes IA/jour |
|---|---|
| Questions libres | 10-20 |
| Lecture/explication de courrier, photos | 3-10 |
| Résumés (mails, articles) | 2-5 |
| **Total** | **~15-35**, bien sous les 500/jour de Flash-Lite gratuit |
