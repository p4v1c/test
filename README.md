# Assistant vocal pour personne aveugle (Android)

Assistant vocal en français, utilisable entièrement à la voix, pensé pour une personne aveugle.
Le nom de l'assistant est personnalisable. Il répond aux commandes simples sans internet et utilise
une intelligence artificielle (Gemini, Claude en secours) pour les questions libres.

> État : **phases 1 à 3 terminées** (socle ; mot d'activation et déclencheurs ; appels, SMS, rappels, SOS).
> La vision, l'orientation, la météo, la musique, etc. arrivent dans les phases suivantes. Ce README sera complété à la fin (guide à lire à voix haute, liste complète des commandes).

## Comment appeler l'assistant (phase 2)

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

## Commandes de la vie quotidienne (phase 3)

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

## Caméra : lire, reconnaître, décrire (phase 4)

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



## Orientation, météo, infos, recherche (phase 5)

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

## Ce qui marche aujourd'hui (phase 1)

- Un seul gros bouton « Parler » (compatible TalkBack), et un bouton « Parler » dans la notification.
- Commandes sans IA ni internet : heure, date, batterie, internet, « répète », « stop », « aide »,
  « parle plus vite / moins vite », « parle plus fort / moins fort », « sois plus bref / donne plus de détails »,
  « change ton nom en … » (avec confirmation), « active / quitte le mode privé », « qui es-tu ? ».
- Questions libres envoyées à Gemini (`gemini-3.5-flash-lite` par défaut), avec bascule automatique
  et annoncée vers Claude (`claude-haiku-5-5`) en cas de quota épuisé ou de panne.
- Toute action sensible demandée par l'IA est relue et doit être confirmée par « oui ».
- Sons distincts (écoute, réflexion, succès, erreur, question, annulé) et vibrations.
- Compteur de requêtes par jour, avec annonce à 80 % de la limite.

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

## Vie privée

- L'écoute du nom se fait entièrement sur le téléphone (Vosk) : aucun son n'est envoyé sur internet.
  Rien ne part tant que le nom n'a pas été dit (ou qu'un bouton n'a pas été appuyé).
- Seule la phrase reconnue (et plus tard la photo, pour les fonctions de vision) part vers Google ou Anthropic.
- **Le palier gratuit de Gemini autorise Google à utiliser les données pour améliorer ses produits.**
- « Active le mode privé » : plus rien n'est envoyé à l'IA, seules les commandes simples fonctionnent.
- Les clés API sont chiffrées sur le téléphone (Android Keystore).

## Checklist de tests sur le vrai téléphone (phase 2)

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

## Checklist de tests sur le vrai téléphone (phase 3)

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

## Checklist de tests sur le vrai téléphone (phase 4)

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

## Checklist de tests sur le vrai téléphone (phase 5)

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

## Développement

```bash
./gradlew :core:test :llm:test :app:testDebugUnitTest   # 121 tests (+ test d'intégration Vosk si le modèle est fourni)
./gradlew :app:assembleDebug
```

- `core/` : logique pure (routeur, confirmations, nom, IA abstraite, scénarios) — testée sans Android.
- `llm/` : fournisseurs Gemini (REST) et Claude (SDK officiel).
- `app/` : application Android (service, voix, sons, écrans).

Pas d'émulateur dans l'environnement de développement (pas de KVM) : les tests Android tournent avec Robolectric.

Documents : [RESEARCH.md](RESEARCH.md), [FEATURES.md](FEATURES.md), [OPEN_SOURCE.md](OPEN_SOURCE.md), [ARCHITECTURE.md](ARCHITECTURE.md).
