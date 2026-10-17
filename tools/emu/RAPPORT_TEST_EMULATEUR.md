# Rapport de test sur émulateur — 17 octobre 2026

Émulateur : Genymotion « Google Pixel 9 », **Android 11 (API 30)**, ABI x86_64, root oui, services Google **non**
(pas de Play Store, ni reconnaissance vocale Google, ni Google Maps, ni synthèse Google).
Tests automatiques au départ : 273 / 273 OK (2 tests d'intégration ignorés : Vosk réel et réseau réel, optionnels), ~6 min.

> Attention, différences avec le vrai Pixel :
> - l'émulateur est en Android 11, alors que le Pixel est en Android 15 ou plus. Les autorisations « notifications » et
>   « musique » (`POST_NOTIFICATIONS`, `READ_MEDIA_AUDIO`) n'existent pas en Android 11 : à vérifier sur le Pixel ;
> - **sans services Google, il n'y a pas de reconnaissance vocale** : toutes les phrases ont été injectées avec
>   `tools/emu/dire.sh` (intention de debug). La vraie écoute (micro → texte) n'a pas pu être testée ;
> - **synthèse vocale** : eSpeak (robotique) au début, puis Piper « fr_FR-tom-medium » (voix neuronale, moteur
>   sherpa-onnx) installé comme moteur Android. Sur le Pixel, ce sera la voix Google ;
> - **genyshell (version gratuite) ne simule ni appels ni SMS entrants** (« No valid license found »).

## Outils ajoutés

- `tools/emu/scenario.sh fichier` : enchaîne des phrases (`attente|phrase` par ligne) ; résultats dans `tools/emu/logs/resultats.log`.
- `tools/emu/ui.sh` : liste les éléments de l'écran avec leurs coordonnées (pour `adb shell input tap`).
- `tools/emu/touche.sh bas|haut durée` : appui physique simulé sur un bouton volume (root, `sendevent`).
- `tools/emu/scenarios/*.txt` : les scénarios joués.

## Résultats

| Phase | Fonction | Phrase / action | Réponse obtenue | Résultat | Remarque |
|---|---|---|---|---|---|
| 1 | Heure | quelle heure est-il ? | « Il est 21 heures 37. » | OK | Fuseau de l'émulateur réglé sur Europe/Paris (il était en UTC) |
| 1 | Date | quel jour sommes-nous ? | « Nous sommes le samedi 17 octobre 2026. » | OK | |
| 1 | Batterie | combien de batterie ? | « La batterie est à 100 pour cent, elle est pleine. » | OK | |
| 1 | Internet | est-ce que j'ai internet ? | « Oui, vous êtes connecté à internet. » / sans réseau : « Non, il n'y a pas de connexion internet pour l'instant. » | OK | wifi + données coupés en root |
| 1 | Répète | répète | Redit la dernière réponse | OK | |
| 1 | Stop | stop | (silence) | OK | |
| 1 | Vitesse | parle plus vite / moins vite | « D'accord, je parle plus vite. C'est mieux comme ça ? » | OK | |
| 1 | Brièveté | sois plus bref / donne plus de détails | « D'accord, je serai bref. » / « … réponses normales. » | OK | |
| 1 | Aide | qu'est-ce que tu sais faire ? | Avant : liste **inventée par l'IA** ; après correctif : liste locale « Je peux vous aider pour : … » | KO → corrigé | Bug 3 |
| 1 | Identité | qui es-tu ? | « Je suis Hector, votre assistant vocal. Que puis-je faire pour vous ? » | OK | La relance d'écoute disait à tort « pas l'autorisation du micro » : bug 1 |
| 1 | IA | comment faire une pâte à crêpes ? | Recette courte, en français, sans markdown | OK | Gemini `gemini-3.5-flash-lite` |
| 1 | IA hors ligne | (sans réseau) comment faire une pâte à crêpes ? | « Je n'ai pas internet pour l'instant… Je peux quand même vous donner l'heure… » | OK | |
| 1 | Calcul hors ligne | combien font 15 pour cent de 80 (sans réseau) | « Ça fait 12. » | OK | |
| 1 | Mode privé | active le mode privé → question libre → quitte le mode privé | Refus poli puis « Mode privé désactivé… » | OK | « combien font 12 fois 7 » marche en mode privé (« Ça fait 84. ») |
| 1 | Nom | change ton nom en Gédéon → oui | « C'est fait, je m'appelle maintenant Gédéon… dites-le après chacun des trois bips » ; « qui es-tu ? » → Gédéon | OK | Vosk recharge la grammaire avec le nouveau nom. Nom remis à Hector. L'apprentissage (3 bips) ne peut pas être fait sans vrai micro |
| 1 | Clé invalide | (clé Gemini invalide) qui a écrit les Misérables ? | « La clé de l'intelligence artificielle ne fonctionne pas. Demandez à un proche de vérifier les réglages. » | OK | Pas de clé Claude : bascule non testable. Bonne clé remise → « C'est Victor Hugo qui a écrit Les Misérables. » |
| 2 | Bouton Parler | tap sur le grand bouton | L'écoute démarre (bip), puis « La reconnaissance vocale n'est pas disponible… » | OK (partiel) | Pas de reconnaissance Google sur l'émulateur |
| 2 | Notification | bouton « PARLER » de la notification | Idem | OK (partiel) | Notification « Hector — Dites « Hector » ou touchez « Parler » » |
| 2 | Volume bas court | appui 0,1 s | Le volume baisse normalement | OK | |
| 2 | Volume bas long | appui 1,5 s (sendevent) | L'écoute démarre | OK | |
| 2 | Volume haut 3 s → SOS | appui 3,5 s puis « stop » | « Alerte de secours. Dans 10 secondes, je préviens Test emu. Dites stop pour annuler. » ; « stop » → rien n'est envoyé | OK | Le « stop » injecté par `dire.sh` ouvre une nouvelle session : pas de message « Alerte annulée » dans ce cas (artefact de l'outil ; le vrai « stop » passe par l'écoute du SOS, couvert par les tests automatiques) |
| 3 | SOS complet | volume haut 3,5 s, sans « stop » | « J'envoie l'alerte. » → « Message d'alerte envoyé à Test emu. Je n'ai pas pu obtenir votre position. J'appelle Test emu. » ; SMS dans les envoyés ; appel en cours (DIALING) | OK | Numéro du modem de l'émulateur uniquement |
| 3 | SOS + rappel | SOS, puis un rappel sonne pendant le compte à rebours | Avant : **SOS annulé en silence, rien envoyé** ; après correctif : SMS + appel partent | KO → corrigé | Bug 4 (sécurité, corrigé avec ton accord) |
| 2 | Redémarrage (sans code) | `adb reboot` | Service au premier plan relancé seul, « Il est 22 heures 35. » ; rappel de 8 h toujours programmé | OK | Rôle « assistant par défaut » **perdu** après ce redémarrage (remis à la main, gardé au suivant) |
| 2 | Redémarrage **avec code PIN, non déverrouillé** | PIN 1234, `adb reboot` | **Rien ne tourne** : « Not found; no service started » ; après saisie du code : 4 services, réponse OK | KO | Voir « non corrigés » n° 2 (Direct Boot) |
| 2 | Veille profonde | `deviceidle force-idle` | Refusée par l'émulateur (bloquée à INACTIVE) | Non testable | Écran éteint : le rappel « dans 2 minutes » a sonné à l'heure, répété une fois |
| 2 | App tuée | `am kill` / `kill -9` | `am kill` sans effet (service protégé) ; `kill -9` : relancée seule en < 15 s, répond | OK | |
| 2 | Forcer l'arrêt | `am force-stop` | Alarmes effacées par Android ; **pas reprogrammées à la réouverture** → corrigé | KO → corrigé | Bug 5. Les boutons volume restent coupés (service d'accessibilité « crashé ») jusqu'à réactivation |
| 3 | Surnom | Pierre Dupont, c'est mon fils | « C'est noté : « mon fils », c'est Pierre Dupont… » | OK | |
| 3 | Appel + refus | appelle mon fils → non | « Appeler Pierre Dupont sur son portable ? » → « D'accord, j'annule. » ; aucun appel | OK | |
| 3 | Homonymes | appelle Paul | « J'ai trouvé Paul Martin et Paul Durand. Lequel voulez-vous appeler ? » | OK | Ne choisit pas au hasard |
| 3 | Domicile | appelle Pierre à la maison → non | « Appeler Pierre Dupont sur son numéro domicile ? » → annulé | OK | |

## Bugs trouvés et corrigés (un commit chacun)

1. **Reconnaissance vocale absente : faux message « Je n'ai pas l'autorisation d'utiliser le micro »** (`fe13aad`).
   Sans application Google, l'app s'appelait elle-même (son relais `ProxyRecognitionService`) et recevait l'erreur 9.
   Elle dit maintenant « La reconnaissance vocale n'est pas disponible sur ce téléphone. Il faut installer l'application Google. »
   Test : `SpeechInputTest`.
2. **Voix robotique** (`49cee09`) : l'app prenait le moteur et la voix par défaut. Elle prend maintenant la synthèse Google si le
   moteur par défaut est un moteur robotique connu (eSpeak, Pico), et la meilleure voix française installée et sans internet.
   Un moteur choisi par un proche est respecté. Test : `VoiceChoiceTest`.
3. **« Qu'est-ce que tu sais faire ? » partait à l'IA** (`7fb778f`) : 7 mots, donc une confiance moyenne. L'IA inventait une liste incomplète.
   Ces phrases donnent maintenant la liste locale. Test dans `AssistantScenarioTest`.
4. **SOS annulé en silence** (`86356d0`) : un rappel, le « tout va bien ? », un appel entrant, « où es-tu », le bouton Parler
   ou un appui sur un bouton volume pendant les 10 s du compte à rebours coupaient l'alerte : ni SMS, ni appel, ni message.
   Désormais, ces demandes attendent la fin de l'alerte et seul « stop » annule. Tests dans `Phase3ServiceTest`.

5. **Rappels effacés par « Forcer l'arrêt »** (`voir git log`) : reprogrammés seulement au démarrage du téléphone.
   Ils sont maintenant reprogrammés à chaque démarrage du service. Test dans `Phase3ServiceTest`.

## Bugs trouvés non corrigés (avec proposition)

1. **Un rappel qui sonne pendant un appel téléphonique est perdu.** `onFire()` reprogramme ou supprime le rappel *avant* l'annonce.
   Puis le passage en ligne (`OFFHOOK`) appelle `interrupt()` et coupe l'annonce. Un rappel unique (« rappelle-moi dans 20 minutes… ») disparaît.
   Il sonne aussi par-dessus l'appel s'il tombe pendant. Vu sur l'émulateur : le rappel mis en attente pendant un SOS est perdu
   quand l'appel du SOS décroche. Proposition : si un appel est en cours, attendre la fin de l'appel avant d'annoncer, et ne
   consommer le rappel qu'une fois l'annonce faite (ou le reprogrammer si elle est interrompue).

2. **Après un redémarrage, rien ne marche avant le premier déverrouillage** (si le téléphone a un code). Aucun composant n'est
   `directBootAware` : pas d'écoute du nom, pas de bouton volume, **pas de SOS** tant que le code n'a pas été tapé (vérifié sur
   l'émulateur avec un PIN). Proposition : rendre le service, l'écoute du nom, l'heure et le SOS « Direct Boot » (réglages
   utiles et contacts d'urgence copiés dans le stockage protégé par l'appareil). En attendant : **pas de code de verrouillage**
   sur le Pixel, ou le savoir.

## Reste à faire (campagne en pause)

- Phase 3 : appel « oui » et raccrocher, appel entrant (`tools/emu/appel_entrant.sh`), SMS entrant normal et arnaque
  (`tools/emu/sms_entrant.sh`), « réponds », envoi de SMS, minuteur, médicament « c'est fait », batterie basse.
- Phases 4 à 8, étape 4 (robustesse, TalkBack). Fichiers de test prêts : `tools/emu/generer_fichiers.py`.
- Pas encore de verdict.

## Remarques sur l'installation (`tools/install.sh`)

- Le rôle « assistant par défaut » n'avait pas été pris au premier passage, alors que la commande ne signalait aucune erreur.
  `cmd role add-role-holder --user 0 …` a marché ensuite. À vérifier sur le Pixel : l'écran Réglages affiche ✔ / ✘, il fait foi.
- Après un `am force-stop` de l'app, Android 11 marque le service d'accessibilité (boutons volume) comme « crashé » et ne le relance plus
  tant qu'on ne le réactive pas. Si les boutons volume ne marchent plus, Réglages → « Activer les boutons volume ».
- En Android 12 et moins, il faut aussi `READ_EXTERNAL_STORAGE` (musique) : le script ne l'accorde pas. Sans effet sur le Pixel.
