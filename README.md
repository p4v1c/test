# Assistant vocal pour personne aveugle (Android)

Assistant vocal en français, utilisable entièrement à la voix, pensé pour une personne aveugle.
Le nom de l'assistant est personnalisable. Il répond aux commandes simples sans internet et utilise
une intelligence artificielle (Gemini, Claude en secours) pour les questions libres. Toute action qui
sort du téléphone (appel, SMS, mail, rendez-vous, alerte) est **relue puis confirmée par « oui »**.

> État : **phases 1 à 8 terminées** — socle ; mot d'activation ; appels, SMS, rappels, SOS ; caméra ;
> orientation, météo, infos ; lecteur façon Victor, mémos, agenda, mails ; tutoriel, sauvegarde, diagnostic ; livres texte et PDF lus à voix haute, podcasts sans internet.
> Tout est testé automatiquement (simulations) ; les essais sur le vrai Pixel restent à faire (checklists en fin de page).

Sommaire : [Installation](#installation-sur-le-téléphone-google-pixel) ·
[Premiers pas](#premiers-pas) · [Appeler l'assistant](#comment-appeler-lassistant) ·
[Commandes de base](#commandes-de-base-sans-internet) · [Appels, SMS, rappels](#appels-sms-rappels-médicaments-secours) ·
[Caméra](#caméra--lire-reconnaître-décrire) · [Orientation, météo](#orientation-météo-infos-recherche) ·
[Livres, radio, agenda, mails](#livres-comme-sur-le-victor-radio-musique-mémos-agenda-mails) ·
[Sauvegarde](#sauvegarde-et-changement-de-téléphone) · [Dépannage](#dépannage) · [Vie privée](#vie-privée) ·
[Tests sur le téléphone](#tests-sur-le-vrai-téléphone) · [Développement](#développement)

## Installation sur le téléphone (Google Pixel)

1. Sur le Pixel : Paramètres → À propos du téléphone → appuyer 7 fois sur « Numéro de build » ;
   puis Paramètres → Système → Options pour les développeurs → activer « Débogage USB ».
2. Brancher le téléphone à un ordinateur avec les outils `adb` (Android platform-tools) installés.
3. Construire (le modèle de reconnaissance française hors ligne, 41 Mo, est téléchargé automatiquement)
   puis installer et tout régler d'un coup :

```bash
./gradlew :app:assembleDebug
./tools/install.sh            # installe, autorise le micro, assistant par défaut, boutons volume, batterie
```

   Sans ordinateur Linux/Mac : `adb install -r app-debug.apk`, puis faire les réglages à la main
   (le bouton « Réglages, pour un proche » liste ce qui reste à activer, avec ✔ / ✘).
4. Ouvrir « Réglages, pour un proche » : choisir le nom, coller la clé Gemini, « Enregistrer »,
   puis « Tester l'intelligence artificielle ».
5. Toucher « Apprendre la prononciation du nom » et laisser **votre père** dire le nom 3 fois après les bips
   (c'est sa voix qui compte), puis parler normalement 15 secondes.
6. Vérifier les ✔ : assistant par défaut (nécessaire pour l'écoute au démarrage et écran éteint),
   boutons volume, nom appris, contacts d'urgence, lecture des notifications.
7. Remplir la partie « proches » des réglages : prénom de votre père, **contacts d'urgence** (indispensable
   pour le SOS), favoris, surnoms (« mon fils: Pierre Dupont »), heure du « tout va bien ? » (option),
   mot secret pour retrouver le téléphone (option), façon d'annoncer les SMS.

### Choisir le nom

2 ou 3 syllabes, peu courant dans la maison, sans ressemblance avec un mot fréquent ni le prénom d'un proche.
Bons exemples mesurés : **Hector, Jarvis, Gédéon, Orphée**. À éviter : un prénom de la famille, « Paul »
(trop court), et les noms qui ressemblent à une expression courante (« Balthazar » ~ « le hasard »).
Mesures détaillées : [tools/wake/README.md](tools/wake/README.md).

### Obtenir une clé Gemini (gratuite)

1. Aller sur <https://aistudio.google.com/apikey> avec un compte Google.
2. « Create API key », copier la clé, la coller dans les réglages de l'app.
3. Les limites gratuites réelles du projet sont visibles sur <https://aistudio.google.com/rate-limit>.

### Clé Claude (optionnelle, payante mais très peu chère)

<https://console.anthropic.com> → API Keys. Environ 0,0003 $ par question avec Claude Haiku.

## Premiers pas

1. Au premier lancement, l'assistant se présente et propose le **tutoriel** : huit petites leçons à la voix
   (poser une question, « stop » et « répète », appeler, messages, livres, rappels, secours, aide).
   Pendant le tutoriel, on essaie les vraies phrases mais **rien n'est déclenché** (aucun appel, aucun SMS).
2. Pour le lancer ou le refaire : dire « tutoriel », ou appuyer sur le bouton **Tutoriel** de l'écran.
   « Arrête le tutoriel » l'interrompt, « reprends le tutoriel » repart à la même leçon.
3. Ensuite : « qu'est-ce que tu sais faire ? » donne la liste des domaines, « aide pour les livres » (ou les appels,
   la caméra, l'orientation…) donne des exemples.
4. « Est-ce que tout marche ? » : l'assistant vérifie lui-même le micro, l'écoute du nom, l'IA, internet,
   la position, les contacts d'urgence, la veille, le rôle d'assistant, puis les options (livres, mails, agenda…),
   et dit ce qu'il faut régler.

## Comment appeler l'assistant

| Déclencheur | Comment | Remarque |
|---|---|---|
| **Son nom** | Dire « Orphée » (le nom choisi), attendre le bip, puis parler | Hors ligne, rien n'est envoyé sur internet. À apprendre une fois : « apprends mon nom » |
| Bouton du casque ou de l'enceinte | Un appui sur lecture/pause | Pendant qu'il parle : l'interrompt |
| Bouton « assistant » d'un casque Bluetooth | Appui long (selon le casque) | Passe par Android |
| Bouton volume bas | Garder appuyé 1 seconde | Un appui court baisse le volume comme d'habitude ; pendant qu'il parle, n'importe quel bouton volume l'interrompt |
| Geste assistant / appui long sur le bouton d'accueil | Selon le téléphone | Une fois l'app « assistant par défaut » |
| Écran | Grand bouton « Parler », ou « Parler » dans la notification | Compatible TalkBack |

Commandes liées : « apprends mon nom », « change ton nom en … » (suivi automatiquement de l'apprentissage),
« n'écoute plus ton nom » / « réactive ton nom », « aide pour les façons de m'appeler ».

## Commandes de base (sans internet)

- Un seul gros bouton « Parler » (compatible TalkBack), et un bouton « Parler » dans la notification.
- Commandes sans IA ni internet : heure, date, batterie, internet, « répète », « stop », « aide »,
  « parle plus vite / moins vite », « parle plus fort / moins fort », « sois plus bref / donne plus de détails »,
  « change ton nom en … » (avec confirmation), « active / quitte le mode privé », « qui es-tu ? ».
- Questions libres envoyées à Gemini (`gemini-3.5-flash-lite` par défaut), avec bascule automatique
  et annoncée vers Claude (`claude-haiku-5-5`) en cas de quota épuisé ou de panne.
- Toute action sensible demandée par l'IA est relue et doit être confirmée par « oui ».
- Sons distincts (écoute, réflexion, succès, erreur, question, annulé) et vibrations.
- Compteur de requêtes par jour, avec annonce à 80 % de la limite.

## Appels, SMS, rappels, médicaments, secours

Toutes les actions qui sortent du téléphone (appel, SMS, réponse) sont **relues puis confirmées par « oui »**.
En cas de doute (deux « Paul »), l'assistant demande lequel ; il ne choisit jamais au hasard.

| Domaine | Exemples |
|---|---|
| Appeler | « appelle mon fils », « appelle Paul Martin », « appelle Pierre à la maison », « appelle le numéro 1 », « appelle le 06 12 34 56 78 » |
| Appels reçus | Il annonce « Appel de Paul Martin » (ou « numéro inconnu… méfiez-vous ») ; pour décrocher : dire le nom ou appuyer sur le bouton du casque ; « décroche », « raccroche », « mets le haut-parleur » ; pendant l'appel : volume bas gardé 1 s = raccrocher |
| Journal | « qui m'a appelé ? », « rappelle-le » |
| SMS | « lis mes messages », « lis mon dernier message », « envoie un message à Marie pour lui dire que j'arrive », « écris à Paul » (il demande quoi), « réponds » |
| Notifications | « lis mes notifications » (Gmail, Meet…), « réponds » quand l'application le permet ; annonce automatique des nouveaux messages (réglable) |
| Surnoms | « Pierre Dupont, c'est mon fils », puis « appelle mon fils » |
| Réveil | « réveille-moi demain à 7 heures », « réveille-moi en semaine à 7 h 30 » |
| Minuteurs | « minuteur de 10 minutes pour les pâtes », « combien de temps reste-t-il ? », « annule le minuteur » |
| Rappels | « rappelle-moi d'appeler le médecin demain à 10 heures », « rappelle-moi dans 20 minutes de sortir le gâteau », « quels sont mes rappels ? », « supprime le rappel du médecin » |
| Médicaments | « rappelle-moi de prendre mon Doliprane tous les jours à 8 heures et à 20 heures » ; au rappel : « c'est fait » ; « ai-je pris mon Doliprane ? » ; sans réponse, il redemande 20 minutes plus tard (2 fois) |
| SOS | « au secours », « à l'aide », « je suis tombé », ou **volume haut gardé 3 s** : 10 secondes pour dire « stop », puis SMS avec la position à tous les contacts d'urgence et appel du premier (haut-parleur) |

Automatique : alerte arnaque à la lecture d'un SMS ou d'une notification suspects (lien, faux colis, CPF, Ameli,
« j'ai changé de numéro », demande d'argent ou de code) ; batterie faible (20, 10, 5 %), chargeur branché/débranché ;
perte et retour d'internet ; « Tout va bien ? » chaque matin (option) avec SMS à un proche s'il ne répond pas ;
un proche peut retrouver le téléphone en envoyant « où es-tu » + le mot secret (le téléphone sonne fort et répond avec sa position).

## Caméra : lire, reconnaître, décrire

Tenir le téléphone **à plat ou droit, caméra arrière vers l'objet, à 20-30 cm**. L'assistant guide à la voix
(« le texte est coupé à droite… », « rapprochez… », « c'est bon, ne bougez plus ») avec un petit bip ;
il allume la lampe tout seul s'il fait sombre et l'éteint toujours à la fin. Dire « stop » pour arrêter.

| Domaine | Exemples | Internet ? |
|---|---|---|
| Lire un texte | « lis ce courrier », « qu'est-ce qui est écrit ? », « lis cette étiquette » | Non |
| Document de plusieurs pages | « page suivante », puis « relis », « relis le montant », « quelle est la date limite ? » | Non |
| Comprendre un courrier | « c'est quoi cette lettre ? », « que dois-je faire ? » (seul le texte lu est envoyé à l'IA, pas la photo) | Oui |
| Décrire | « qu'est-ce qu'il y a devant moi ? », « qu'est-ce que je tiens ? » | Oui |
| Billets | « quel billet ? » (chiffre + couleur, sur le téléphone) | Non |
| Couleurs | « quelle couleur ? », « est-ce que ça va ensemble ? » (2 photos) | Non / Oui |
| Lumière | « détecteur de lumière » : son aigu = lumière, grave = noir ; « la lampe est allumée ? » | Non |
| Produits | « c'est quoi ce produit ? » (code-barres → nom, allergènes, Nutri-Score), « c'est périmé ? » | Oui (Open Food Facts) / Non |
| Médicaments | « c'est quoi ce médicament ? » : code DataMatrix → nom, dosage, boîte, date de péremption, lot (base publique incluse dans l'app) ; sinon lecture de la boîte. Toujours : « en cas de doute, demandez au pharmacien » | Non |
| Objets | « où sont mes clés ? » (indique gauche/droite/devant) | Oui |
| Lookout | « ouvre Lookout » (application Google gratuite, très bonne pour les billets et le mode texte continu) | — |



## Orientation, météo, infos, recherche

| Domaine | Exemples | Internet ? |
|---|---|---|
| Où suis-je | « où suis-je ? », « quelle est mon adresse ? » → « Vous êtes près du 12 rue des Lilas, à Saint-Marcel » | Oui (sans internet : seulement « vous êtes à la maison ») |
| Lieux proches | « où est la pharmacie la plus proche ? », « y a-t-il une boulangerie près d'ici ? », « un distributeur ? » : nom, distance, direction, téléphone ; puis « oui » pour être guidé | Oui |
| Lieux à soi | « enregistre ici comme maison » (confirmé), « à quelle distance est la maison ? », « où est la maison ? », « quels sont mes lieux ? », « oublie le lieu … » | Non |
| Guidage à pied | « emmène-moi à la maison », « guide-moi jusqu'à la pharmacie », « emmène-moi au 8 rue de la Paix à Valence » → Google Maps en mode piéton | Oui |
| Partager sa position | « envoie ma position à mon fils », « dis à Paul où je suis » → SMS avec l'adresse et un lien de carte, après « oui » | Non (adresse si internet) |
| Météo | « quel temps fait-il ? », « va-t-il pleuvoir demain ? », « la météo du week-end », « la météo de la semaine », « météo à Lyon », « il fait froid dehors ? » (conseils : parapluie, verglas, chaleur) | Oui |
| Actualités | « les infos », « les infos sport / santé / monde / économie / régionales », « la suite », « lis le deuxième », « lis l'article » (résumé) | Oui |
| Recherche | « cherche sur internet les horaires de la déchetterie », « que dit Wikipédia sur … » | Oui |
| Calculs | « combien font 12 fois 7 ? », « 15 pour cent de 80 », « racine carrée de 81 », « 100 euros en francs » | Non |

La direction est donnée **en heures d'horloge** (« à 3 heures, sur votre droite ») si la boussole du téléphone répond,
téléphone tenu devant soi ; sinon en points cardinaux (« au nord-est »). Les distances sont à vol d'oiseau.

## Livres (comme sur le Victor), radio, musique, mémos, agenda, mails

Le lecteur reprend les habitudes du **Victor Reader Stream** : mêmes étagères, livres DAISY et MP3, reprise
automatique de chaque livre, chapitres, pages, sauts dans le temps, signets numérotés, « où j'en suis »,
vitesse, minuterie de sommeil, notes vocales. Quand on appelle l'assistant, la lecture se met en pause, puis reprend
après la réponse (sauf « pause » ou « stop »). Débrancher le casque met aussi en pause.

| Domaine | Exemples |
|---|---|
| Bibliothèque | « quels livres ai-je ? », « lis le livre Le Petit Prince », « reprends mon livre », « continue » |
| Livres texte et PDF | « lis le document lettre de la banque », « lis le PDF facture EDF », « lis le livre texte Les Misérables » : lus à voix haute (TXT, Word, HTML, EPUB sans DRM, PDF ; PDF scannés lus par reconnaissance de texte), avec les mêmes commandes : chapitres, pages, signets, « où j'en suis », vitesse |
| Navigation | « chapitre suivant / précédent », « section suivante », « va au chapitre 5 », « va à la page 42 », « avance de 5 minutes », « recule de 30 secondes », « recule un peu », « au début du chapitre », « au début du livre » |
| Où j'en suis | « où j'en suis ? » → titre, chapitre, page, pourcentage écouté, temps restant |
| Signets | « pose un signet » (numérotés 1, 2, 3…), « va au signet 2 », « mes signets », « supprime le signet 2 », « supprime tous les signets » (confirmé) |
| Vitesse et sommeil | « lis plus vite / moins vite », « vitesse normale », « arrête la lecture dans 30 minutes », « annule la minuterie de sommeil » |
| Radio | « mets France Inter », « mets la radio » (dernière écoutée), « mets RTL », « mets France Bleu Drôme Ardèche », « radio suivante », « ajoute cette radio à mes favoris », « mes radios » |
| Podcasts | « abonne-moi au podcast Affaires sensibles » (confirmé), « mes podcasts », « écoute le podcast Affaires sensibles » (reprend l'épisode commencé), « écoute le dernier épisode de … », « épisode suivant » |
| Podcasts sans internet | Les 3 derniers épisodes de chaque abonnement se téléchargent **tout seuls en wifi** (comme sur le Victor) ; « télécharge les podcasts » (ou « … du podcast X ») pour le faire tout de suite ; « mes podcasts téléchargés » ; « supprime les épisodes téléchargés » (confirmé). Sans internet, « écoute le podcast X » joue l'épisode téléchargé |
| Musique | « mets de la musique » (au hasard), « mets de la musique de Brassens » (fichiers du téléphone, sinon YouTube Music), « suivant », « pause » ; commande aussi la musique d'une autre application |
| Mémos vocaux | « enregistre un mémo » → bip, parler, il s'arrête au silence ; « écoute mes mémos » (le plus récent d'abord), « mémo suivant », « supprime ce mémo » (confirmé) |
| Notes écrites | « note que le code du portail est 1 2 3 4 », « lis mes notes », « supprime la note 2 » |
| Liste de courses | « ajoute du pain et du lait à la liste », « il faut acheter des œufs », « lis ma liste », « enlève le pain de la liste », « vide la liste » (confirmé) |
| Agenda | « qu'est-ce que j'ai demain ? », « mes rendez-vous de la semaine », « ajoute un rendez-vous chez le dentiste mardi à 14 heures » (confirmé) — les rappels du jour sont dits aussi |
| Point du jour | « fais-moi le point » : date, heure, météo, rendez-vous et rappels du jour, SMS et mails non lus, liste de courses |
| Mails (Gmail) | « lis mes mails », « lis le premier », « résume le mail », « réponds » (relu et confirmé), « écris un mail à Paul pour lui dire que … » ; alerte si le mail ressemble à une arnaque |

### Mettre les livres du Victor sur le téléphone

1. Retirer la carte SD du Victor et la brancher sur un ordinateur (ou un lecteur de carte USB-C branché au Pixel).
2. Brancher le Pixel à l'ordinateur en USB, choisir « Transfert de fichiers » sur le téléphone.
3. Créer un dossier `Victor` sur le téléphone et y copier les dossiers de la carte : `$VRDTB` (livres DAISY),
   `$VROtherBooks` (livres audio), `$VRText` (livres texte), `$VRMusic`, `$VRPodcasts`, `$VRNotes`. Un simple dossier de livres
   (un sous-dossier par livre audio, ou des fichiers PDF, Word, TXT, EPUB) marche aussi.
4. Dans l'app : **Réglages → Choisir le dossier des livres** → ouvrir `Victor` → « Utiliser ce dossier » → « Autoriser ».
5. Dire « quels livres ai-je ? ».

Les livres d'Éole (AVH) téléchargés au format DAISY se copient de la même façon. Pour faire lire un courrier reçu en PDF :
le copier dans ce dossier (par exemple depuis Gmail, « Enregistrer sur l'appareil », puis le déplacer), puis « lis le PDF … ».
Pas pris en charge : livres DAISY en texte seul, EPUB protégés (DRM), Audible, et les signets déjà posés sur le Victor
(ils restent sur le Victor).

### Brancher Gmail (lecture et envoi de mails)

1. Sur le compte Google de votre père (sur un ordinateur) : **Gérer le compte → Sécurité → Validation en deux étapes** (à activer si besoin).
2. Toujours dans Sécurité : **Mots de passe des applications** → nom « Assistant » → Créer → noter les 16 lettres.
3. Dans l'app : **Réglages** → adresse Gmail + ce mot de passe → Enregistrer. Il est stocké chiffré sur le téléphone.

## Sauvegarde et changement de téléphone

- **Automatique** : une fois par semaine, l'app enregistre une sauvegarde dans
  `Android/data/fr.assistantvocal.app/files/Sauvegardes/` (les 8 dernières sont gardées), visible en branchant le téléphone à un ordinateur.
- **À la voix** : « sauvegarde mes réglages ».
- **Réglages → Enregistrer une sauvegarde…** : choisir où mettre le fichier (Drive, Téléchargements…).
- **Réglages → Restaurer une sauvegarde…** : l'app résume ce que contient le fichier (rappels, lieux, surnoms,
  signets, podcasts, notes…) et demande confirmation avant de remplacer quoi que ce soit.
- Contenu : tous les réglages et données (nom, voix, proches, rappels, médicaments, lieux, positions et signets des livres,
  radios, podcasts, notes, liste de courses). **Jamais** : clés IA, clé Tavily, mot de passe Gmail (à ressaisir), ni le
  dossier des livres (à rechoisir), ni les mémos vocaux (fichiers audio à copier à part si besoin).
- Le fichier contient des informations personnelles (surnoms, notes, lieux) : le garder en lieu sûr.

## Dépannage

| Problème | À essayer |
|---|---|
| Il ne réagit plus à son nom | « est-ce que tout marche ? » ; « réactive ton nom » ; « apprends mon nom » ; vérifier que l'app est retirée de l'optimisation de batterie |
| Il se déclenche tout seul | « apprends mon nom » (l'écoute devient plus stricte si besoin), ou changer pour un nom plus rare |
| « Je n'ai pas compris » trop souvent | Parler après le bip, plus près du téléphone ; « sois plus bref » pour des réponses plus courtes |
| Plus de réponses aux questions | Quota gratuit du jour atteint (il le dit) : les commandes simples marchent toujours ; ajouter une clé Claude en secours |
| Un livre ne s'ouvre pas | « quels livres ai-je ? » ; rechoisir le dossier des livres dans les réglages ; un EPUB protégé (DRM) ne peut pas être lu |
| Pas de podcast sans internet | « mes podcasts téléchargés » ; vérifier « Télécharger les podcasts en wifi » dans les réglages ; passer une nuit en wifi |
| Les mails ne marchent pas | Recréer le mot de passe d'application Google et le ressaisir |
| Quelque chose s'est mal passé | Dire « **signale un problème** » juste après : l'échange est noté ; un proche ouvre Réglages → **Partager le journal** et l'envoie par mail |

## Vie privée

- L'écoute du nom se fait entièrement sur le téléphone (Vosk) : aucun son n'est envoyé sur internet.
  Rien ne part tant que le nom n'a pas été dit (ou qu'un bouton n'a pas été appuyé).
- Seuls partent vers Google ou Anthropic : la phrase reconnue quand l'IA est nécessaire, le texte lu d'un courrier à expliquer,
  ou une photo pour décrire une scène. Adresse, météo, actualités et radios passent par des services publics, sans IA.
- **Le palier gratuit de Gemini autorise Google à utiliser les données pour améliorer ses produits.**
- « Active le mode privé » : plus rien n'est envoyé à l'IA, seules les commandes simples fonctionnent.
- Les clés API sont chiffrées sur le téléphone (Android Keystore).
- Le journal des derniers échanges (pour « signale un problème ») reste sur le téléphone ; il ne part que si un proche le partage.
- Les sauvegardes ne contiennent ni clés ni mots de passe.

## Tests sur le vrai téléphone

### Phase 2

À faire avec votre père, téléphone posé à sa place habituelle :

- [ ] Après installation, redémarrer le téléphone **sans le déverrouiller** : dire le nom → bip ? (assistant par défaut)
- [ ] Écran éteint, téléphone sur la table à 1 m : nom → bip → « quelle heure est-il » → réponse correcte
- [ ] Même chose à 3 m, puis depuis la pièce voisine (noter la distance limite)
- [ ] Télévision ou radio allumée **pendant 1 heure** : compter les bips non voulus (objectif : 0 ou 1)
- [ ] Conversation à table pendant 30 min : compter les bips non voulus
- [ ] Dire le nom pendant que l'assistant parle → il s'interrompt et écoute
- [ ] « Qui es-tu ? » : il dit son propre nom sans se redéclencher
- [ ] Casque Bluetooth / enceinte : un appui sur lecture/pause → bip ; pendant qu'il parle → il s'arrête
- [ ] Casque filaire (si utilisé) : idem
- [ ] Bouton volume bas gardé 1 s, écran allumé puis **écran éteint** (noter si ça marche écran éteint)
- [ ] Appui court volume bas : le volume baisse normalement ; appui pendant qu'il parle : il s'arrête
- [ ] Avec TalkBack activé : les gestes TalkBack et le raccourci TalkBack (les deux boutons volume) fonctionnent toujours
- [ ] Dictée vocale du clavier Google toujours fonctionnelle (l'app ne doit pas la casser)
- [ ] Batterie : noter le pourcentage le matin et le soir sur une journée normale (comparer avec écoute désactivée)
- [ ] « Change ton nom en … » → confirmation → apprentissage → le nouveau nom fonctionne, l'ancien non

### Phase 3

- [ ] « appelle mon fils » → relecture → « oui » → l'appel part ; « non » → rien ne se passe
- [ ] Appel entrant d'un proche : nom annoncé ; dire le nom → décroché (haut-parleur si réglé) ; volume bas 1 s → raccroché
- [ ] Appel d'un numéro inconnu : « numéro inconnu… méfiez-vous »
- [ ] « qui m'a appelé ? » puis « rappelle-le »
- [ ] SMS reçu : annonce selon le réglage ; « lis mes messages » ; « réponds » → dictée → relecture → envoi réel (vérifier côté destinataire)
- [ ] SMS d'arnaque (se l'envoyer soi-même avec un lien et « colis ») → avertissement lu
- [ ] Notification Gmail / Meet annoncée ; « lis mes notifications »
- [ ] Réveil demain à une heure proche : sonne écran éteint, téléphone verrouillé ; « stop » l'arrête
- [ ] Minuteur 1 minute « pour les pâtes » : annonce du nom du minuteur
- [ ] Rappel de médicament : annonce, « c'est fait », puis « ai-je pris mon médicament ? » ; sans réponse : relance 20 min plus tard
- [ ] Redémarrer le téléphone : les rappels sont toujours là (« quels sont mes rappels ? »)
- [ ] SOS (prévenir les proches avant !) : « au secours » puis « stop » → rien n'est envoyé ; sans « stop » → SMS avec lien de carte reçu par tous, appel du premier
- [ ] Volume haut gardé 3 s → SOS ; appui court → le volume monte normalement
- [ ] « Tout va bien ? » à l'heure réglée ; sans réponse 3 fois → SMS au proche
- [ ] Un proche envoie « où es-tu <mot secret> » → le téléphone sonne fort et répond avec la position
- [ ] Batterie à 20 % : annonce ; brancher le chargeur : annonce

### Phase 4

- [ ] À la première utilisation : autorisation caméra accordée (sinon l'assistant le dit)
- [ ] « lis ce courrier » sur une vraie facture : guidage, puis lecture complète ; « relis le montant » ; « quelle est la date limite ? »
- [ ] Facture de 2 pages : « page suivante », puis « relis » ; « que dois-je faire ? » → explication simple
- [ ] Pièce sombre : la lampe s'allume pendant la photo et **s'éteint après** (vérifier en touchant/regardant)
- [ ] Écran verrouillé, téléphone en poche puis sorti : la caméra fonctionne quand même (service en avant-plan)
- [ ] Billets de 5, 10, 20, 50 € (recto et verso) : bonne valeur ; si doute, il le dit
- [ ] « quelle couleur ? » sur 5 vêtements de couleurs connues ; « est-ce que ça va ensemble ? »
- [ ] Détecteur de lumière : lampe allumée/éteinte, le son change
- [ ] Code-barres d'un produit alimentaire courant : nom et allergènes ; d'un produit inconnu : il le dit
- [ ] Boîte de Doliprane (code carré DataMatrix sur le côté) : nom, dosage, péremption, lot
- [ ] « qu'est-ce qu'il y a devant moi ? » avec internet ; sans internet : message clair
- [ ] « où sont mes clés ? » clés sur une table
- [ ] « ouvre Lookout » (si installé ; sinon il propose de l'installer)
- [ ] « stop » pendant le guidage arrête tout et éteint la lampe

### Phase 5

- [ ] Dehors, localisation activée : « où suis-je ? » → bonne rue et bonne commune
- [ ] À la maison : « enregistre ici comme maison » → « oui » ; couper internet → « où suis-je ? » → « Vous êtes à la maison »
- [ ] À 300 m de la maison : « à quelle distance est la maison ? » ; tourner sur soi avec le téléphone devant : les heures changent dans le bon sens
- [ ] « où est la pharmacie la plus proche ? » (au village, il peut chercher jusqu'à 15 km) → « oui » → Google Maps démarre le guidage à pied, écran verrouillé compris
- [ ] « envoie ma position à … » → « oui » → le proche reçoit l'adresse et un lien qui ouvre la bonne carte
- [ ] « quel temps fait-il ? », « va-t-il pleuvoir demain ? » : comparer avec la météo de Météo-France
- [ ] « les infos » → « la suite » → « lis le deuxième » → « lis l'article » (résumé, IA requise)
- [ ] Réglages : adresse du flux RSS du journal régional → « les infos régionales »
- [ ] « cherche sur internet … » avec et sans clé Tavily
- [ ] « combien font 12 fois 7 ? » en mode privé (aucune IA) → « Ça fait 84 »

### Phase 6

- [ ] Copier 2 livres DAISY du Victor + 1 dossier MP3, choisir le dossier dans les réglages → « quels livres ai-je ? » donne les bons titres
- [ ] « lis le livre … » : la lecture démarre après l'annonce ; écran verrouillé, elle continue ; casque débranché → pause
- [ ] Pendant la lecture : dire le nom → la lecture s'arrête pendant l'écoute ; « où j'en suis ? » → réponse, puis la lecture reprend
- [ ] « chapitre suivant », « va au chapitre 3 », « va à la page 10 » (livre DAISY avec pages), « avance de 5 minutes », « recule un peu »
- [ ] « pose un signet », avancer, « va au signet 1 » ; éteindre et rallumer le téléphone → « reprends mon livre » repart au bon endroit
- [ ] « lis plus vite » (le livre accélère, la voix de l'assistant ne change pas) ; « parle plus vite » (l'inverse)
- [ ] « arrête la lecture dans 1 minute » → la lecture s'arrête seule
- [ ] Livre DAISY ancien avec accents (encodage windows-1252) : titres bien prononcés
- [ ] « mets France Inter », « mets RTL », « radio suivante », « pause », « reprends »
- [ ] « abonne-moi au podcast … » → « oui » → « écoute le podcast … » ; le lendemain, l'épisode reprend où il en était
- [ ] « mets de la musique », « mets de la musique de … » (avec et sans fichiers sur le téléphone)
- [ ] « enregistre un mémo » : parler 10 s, se taire → « Mémo enregistré » ; « écoute mes mémos » ; « supprime ce mémo » → « oui »
- [ ] Liste de courses et notes ; « fais-moi le point » le matin
- [ ] Agenda : « qu'est-ce que j'ai demain ? » ; ajouter un rendez-vous → il apparaît dans Google Agenda sur un autre appareil
- [ ] Gmail : « lis mes mails », « lis le premier », « réponds » → « oui » → le correspondant reçoit la réponse ; « écris un mail à … »

### Phase 7

- [ ] Premier lancement : le message d'accueil propose le tutoriel ; bouton « Tutoriel » → les 8 leçons se suivent, rien n'est appelé ni envoyé
- [ ] « arrête le tutoriel » au milieu, puis « reprends le tutoriel » → même leçon
- [ ] « est-ce que tout marche ? » avant et après avoir coupé le wifi : internet est signalé
- [ ] « sauvegarde mes réglages » → fichier visible dans `Android/data/fr.assistantvocal.app/files/Sauvegardes/` depuis un ordinateur
- [ ] Réglages → Enregistrer une sauvegarde sur Drive ; modifier un surnom ; Restaurer → le surnom revient, les rappels sonnent toujours
- [ ] « signale un problème » → Réglages → Partager le journal → mail reçu avec l'échange marqué

### Phase 8

- [ ] Copier dans le dossier des livres : un PDF texte (facture), un PDF scanné (photo d'un courrier), un .docx, un .txt, un EPUB → « quels livres ai-je ? »
- [ ] « lis le PDF … » : la voix lit sans coupure entre les morceaux ; « va à la page 2 », « chapitre suivant », « où j'en suis ? », « pose un signet »
- [ ] PDF scanné : le texte est lu (reconnaissance sur le téléphone, peut prendre quelques secondes à la première ouverture)
- [ ] Pendant la lecture d'un document, appeler l'assistant → la lecture s'arrête, puis reprend après la réponse ; « lis plus vite »
- [ ] Abonné à un podcast, laisser le téléphone une nuit en wifi → « mes podcasts téléchargés » ; mode avion → « écoute le podcast … » fonctionne
- [ ] En 4G : « télécharge les podcasts » → il refuse poliment et attend le wifi

## Développement

```bash
./gradlew :core:test :llm:test :app:testDebugUnitTest   # 273 tests (+ tests d'intégration Vosk et réseau réels, optionnels)
./gradlew :app:assembleDebug
```

- `core/` : logique pure (routeur, confirmations, nom, IA abstraite, scénarios) — testée sans Android.
- `llm/` : fournisseurs Gemini (REST) et Claude (SDK officiel), services web gratuits (IGN, Open-Meteo, OpenStreetMap, RSS, Wikipédia, radios, podcasts).
- `app/` : application Android (service, voix, sons, écrans, caméra, lecteur, agenda, mails, sauvegardes).

Pas d'émulateur dans l'environnement de développement (pas de KVM) : les tests Android tournent avec Robolectric.

**Test complet sur émulateur** (Genymotion, Pixel rooté) : donner à Claude Code le prompt [tools/emu/PROMPT_TEST_EMULATEUR.md](tools/emu/PROMPT_TEST_EMULATEUR.md). En version de debug, `tools/emu/dire.sh "phrase"` fait « entendre » une phrase à l'assistant (intention `fr.assistantvocal.DEBUG_HEAR`, root) et affiche ce qu'il répond (journal `Dit : …`).

Documents : [RESEARCH.md](RESEARCH.md), [FEATURES.md](FEATURES.md), [OPEN_SOURCE.md](OPEN_SOURCE.md), [ARCHITECTURE.md](ARCHITECTURE.md).
