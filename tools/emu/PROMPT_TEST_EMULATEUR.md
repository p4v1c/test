# Prompt : tester toute l'application sur l'émulateur Genymotion (Pixel 9, rooté)

> À copier tel quel dans Claude Code, lancé sur le PC où tourne Genymotion, à la racine du dépôt cloné.

---

Tu es chargé de **valider de bout en bout** l'application Android « Assistant vocal pour personne aveugle »
(dépôt courant, branche `ccr-69b99168-6tdr45`) sur un **émulateur Genymotion « Google Pixel 9 » rooté**, lancé sur ce PC
(Windows : utilise Git Bash pour les scripts `.sh`). L'application est destinée à mon père, aveugle : la **fiabilité**
compte plus que tout. Ton but : prouver, fonction par fonction, que ça marchera sur son vrai Pixel, et trouver les bugs
avant lui.

## Règles

1. **Lis d'abord** `README.md` (commandes, checklists « Tests sur le vrai téléphone » phases 2 à 8), `ARCHITECTURE.md`
   et `FEATURES.md`. Les checklists du README sont ta liste de contrôle principale.
2. **Ne casse rien** : travaille sur une branche `test-emulateur` créée depuis `ccr-69b99168-6tdr45`. Ne pousse jamais
   sur une autre branche, ne crée pas de pull request sans me demander.
3. Si tu trouves un bug : reproduis-le, écris d'abord un **test automatique qui échoue** (dans `core/src/test`, `llm/src/test`
   ou `app/src/test`), corrige, relance **tous** les tests, puis commit avec un message clair. Un commit par bug.
   Si la correction est risquée ou touche la sécurité (appels, SMS, SOS, mails), **décris-la et demande-moi avant**.
4. Ne contourne jamais une confirmation de sécurité (relecture + « oui ») pour qu'un test passe.
5. Aucune donnée réelle : contacts, numéros et adresses de test uniquement. **N'appelle et n'envoie de SMS qu'à des
   numéros de l'émulateur** (le modem simulé de Genymotion), jamais au 112 ni à un vrai numéro.
6. Note tout dans `tools/emu/RAPPORT_TEST_EMULATEUR.md` au fur et à mesure (modèle en fin de prompt).
7. Si une fonction ne peut pas être testée sur émulateur, dis-le **précisément** (pourquoi, et ce qu'il faudra faire
   sur le vrai téléphone), ne la marque pas « OK ».

## Étape 0 : environnement

- JDK 21, Android SDK (platform 35, build-tools 35), `adb` dans le PATH (`adb version`). Si Genymotion utilise son propre
  adb, aligne-le sur celui du SDK (Genymotion → Paramètres → ADB → SDK Android personnalisé).
- `adb devices` doit montrer l'émulateur (souvent `192.168.56.x:5555` ou `127.0.0.1:6555`) ; sinon `adb connect <ip>`.
- Vérifie et note : `adb shell getprop ro.product.model`, `ro.build.version.release`, `ro.build.version.sdk`, l'ABI
  (`ro.product.cpu.abi` : doit être `x86_64` ou `arm64-v8a`, les seules gérées par l'app) et le root
  (`adb shell su -c id` → `uid=0`).
- Services Google : `adb shell pm list packages | grep -E "gms|vending|googlequicksearchbox|tts|maps|youtube.music"`.
  Sans Play Store, installe les « Open GApps » depuis la barre d'outils Genymotion, puis depuis le Play Store
  **Speech Services by Google** (synthèse vocale française) et **Google Maps** ; vérifie qu'une voix française est
  installée : `adb shell settings get secure tts_default_synth`, puis un essai avec l'app.
- `genyshell` (dans le dossier d'installation de Genymotion) sert à simuler GPS, batterie, réseau, appels et SMS.
  Lance `genyshell -h` et adapte la syntaxe à ta version. Exemples : `gps setstatus enabled`,
  `gps setlatitude 44.9698`, `battery setlevel 15`, `phone call 0612345678`, `phone sms 0612345678 "texte"`
  (selon la version : `phone baseband gsm call …` et `phone baseband sms send …`).

## Étape 1 : construire et tests automatiques

```bash
git checkout -b test-emulateur ccr-69b99168-6tdr45
./gradlew test                 # Windows : gradlew.bat test — attendu : ~272 tests, 0 échec
./gradlew :app:assembleDebug   # télécharge le modèle Vosk (41 Mo) et la base des médicaments à la 1re construction
```

Note le nombre de tests et le temps. Un échec ici est déjà un bug à traiter.

## Étape 2 : installation et préparation

1. `bash tools/install.sh` (installe, accorde les autorisations, assistant par défaut, accessibilité, batterie).
   Note chaque ligne « à accorder à la main » et fais-le (UI : `adb shell uiautomator dump /sdcard/ui.xml`, `adb pull`,
   puis `adb shell input tap X Y` ; ou `adb shell su -c ...` quand c'est possible).
2. Ouvre l'app une fois (`adb shell am start -n fr.assistantvocal.app/.MainActivity`) : accepte toutes les autorisations.
3. **Contacts de test** : crée « Pierre Dupont » (06 11 22 33 44 et domicile 04 75 00 00 00, mail pierre@example.com),
   « Paul Martin » (06 12 34 56 78), « Paul Durand » (06 98 76 54 32). Méthode au choix : fichier `.vcf` poussé dans
   `/sdcard/Download` puis importé via l'app Contacts (automatisation UI), ou `content insert` sur
   `content://com.android.contacts/raw_contacts` puis `.../data` (en root).
4. **Réglages** (écran « Réglages, pour un proche ») :
   - nom de l'assistant « Hector » ;
   - prénom « Jean » ;
   - contact d'urgence = **un numéro de l'émulateur**, pas un vrai ;
   - surnom « mon fils: Pierre Dupont » ;
   - **clé Gemini** : demande-la-moi. Je peux en créer une gratuite sur https://aistudio.google.com/apikey. Ne l'écris jamais dans un fichier du dépôt.
5. **Pour faire « entendre » une phrase** à l'assistant, utilise `tools/emu/dire.sh "phrase" [attente_en_s]`.
   - Il envoie l'intention de debug `fr.assistantvocal.DEBUG_HEAR` (version debug seulement, via root).
   - Puis il affiche le journal : `Entendu : …`, `Dit : …` (tout ce que l'assistant prononce), `Lecture : …` / `Lecteur prêt`
     (son en cours), `Lecture du segment n` (documents lus), exceptions.
   - Pour une confirmation, envoie ensuite `dire.sh "oui"` ou `dire.sh "non"`.
   - Garde aussi un `adb logcat` complet en arrière-plan dans un fichier, et cherche `FATAL EXCEPTION`, `ANR` après chaque série.

## Étape 3 : scénarios « il dit X, il entend Y »

Pour chaque ligne : la phrase, la réponse attendue (approximative si l'IA répond), le résultat **réel** copié du
journal, et OK / KO / non testable. Les réponses exactes attendues sont aussi dans les tests de `core/src/test`
(fichiers `*ScenarioTest.kt`) : appuie-toi dessus.

**Phase 1 : socle**
- `quelle heure est-il ?`, `quel jour sommes-nous ?`, `combien de batterie ?`, `est-ce que j'ai internet ?` (avec et sans réseau :
  `adb shell su -c "svc wifi disable; svc data disable"` puis `enable`).
- `répète`, `stop`, `parle plus vite` (la vitesse de la voix change), `sois plus bref`, `qu'est-ce que tu sais faire ?`.
- Question libre (IA) : `comment faire une pâte à crêpes ?` → réponse courte en français, sans markdown.
- `active le mode privé` → une question libre est refusée poliment ; `quitte le mode privé`.
- `change ton nom en Gédéon` → confirmation → `oui` → l'app annonce l'apprentissage du nom.
  Remets ensuite « Hector ».
- Quota et bascule : mets une clé Gemini invalide → message clair et bascule vers Claude si une clé Claude existe,
  sinon explication. Remets la bonne clé.

**Phase 2 : déclencheurs et arrière-plan**
- Notification « Parler » et grand bouton : `adb shell input tap` sur le bouton → l'écoute démarre (journal).
- Bouton volume bas maintenu ; volume haut maintenu 3 s : si `input keyevent --longpress` ne suffit pas, utilise
  `sendevent` en root. Résultat : SOS, à **annuler par « stop »**.
- **Redémarrage sans déverrouiller** (`adb reboot`, ne déverrouille pas) : le service doit tourner
  (`adb shell dumpsys activity services fr.assistantvocal.app`) et `dire.sh "quelle heure est-il ?"` doit répondre.
- **Veille profonde** : `adb shell dumpsys deviceidle force-idle`, puis un rappel dans 2 minutes (phase 3) doit sonner.
  Sors ensuite de la veille : `dumpsys deviceidle unforce`.
- App tuée : `adb shell am kill fr.assistantvocal.app` et `am force-stop`. Note ce qui redémarre seul.
- **Mot d'activation** (si Genymotion peut utiliser le micro du PC) : active l'entrée audio de l'émulateur. Fais dire « Hector »
  par la synthèse vocale du PC (PowerShell : `Add-Type -AssemblyName System.Speech`, voix française si disponible),
  et vérifie le bip ou le journal. Sinon : « non testable », à faire sur le vrai téléphone.

**Phase 3 : appels, SMS, rappels, secours**
- `Pierre Dupont, c'est mon fils`, puis `appelle mon fils` → « Appeler Pierre Dupont sur son portable ? » → `non`
  (rien ne part), puis `appelle mon fils` → `oui`. Vérifie l'appel (`adb shell dumpsys telecom`, journal d'appels).
  Raccroche (`raccroche`).
- `appelle Paul` → il demande lequel (deux Paul), il ne choisit jamais au hasard.
- Appel entrant via genyshell : l'annonce « Appel de Paul Martin » ; `décroche` ; numéro inconnu → mise en garde.
- SMS entrant via genyshell (normal, puis arnaque : `Votre colis est bloqué, payez 1,99 € sur http://x.top`) →
  annonce, `lis mes messages`, avertissement arnaque, `réponds` → texte → relecture → `oui` → SMS envoyé
  (`content query --uri content://sms/sent`).
- `envoie un message à Paul Martin pour lui dire que j'arrive` → relecture → `oui`.
- `réveille-moi dans 2 minutes` (phrase équivalente acceptée), `minuteur de 1 minute pour les pâtes` → ça sonne (journal
  `Dit :`), `stop`. `rappelle-moi de prendre mon Doliprane tous les jours à <heure+2 min>` → le rappel sonne → `c'est fait`
  → `ai-je pris mon Doliprane ?`. `quels sont mes rappels ?`. Redémarre : les rappels sont toujours programmés
  (`adb shell dumpsys alarm | grep fr.assistantvocal`).
- **SOS** : `au secours` → compte à rebours → `stop` → rien n'est envoyé. Recommence sans « stop » → SMS avec lien de
  position au contact d'urgence **de test** + appel. Batterie basse via genyshell → annonce.

**Phase 4 : caméra** (caméra de l'émulateur : webcam du PC ou image ; sinon le dire)
- `lis ce courrier` devant une lettre imprimée (webcam), `quel billet ?`, `quelle couleur ?`, `détecteur de lumière`,
  `c'est quoi ce produit ?` (code-barres), `c'est quoi ce médicament ?` (DataMatrix d'une boîte), `relis le montant`.
- Vérifie : pas de plantage, la lampe s'éteint en fin de séance, refus propre sans autorisation caméra
  (`pm revoke fr.assistantvocal.app android.permission.CAMERA`, puis réaccorde).

**Phase 5 : orientation, météo, infos**
- GPS genyshell `44.9698, 4.9500` → `où suis-je ?` → « … rue de la Liberté, à Saint-Marcel-lès-Valence ».
- `enregistre ici comme maison` → `oui` ; déplace le GPS de ~500 m au nord → `à quelle distance est la maison ?`.
- `où est la pharmacie la plus proche ?` → nom, distance, direction → `oui` → Google Maps s'ouvre en mode piéton
  (`dumpsys activity activities | grep -i maps`).
- `envoie ma position à Paul Martin` → `oui` → SMS avec lien.
- `quel temps fait-il ?`, `va-t-il pleuvoir demain ?`, `météo à Lyon`, `les infos`, `la suite`, `lis le deuxième`,
  `lis l'article`, `cherche sur internet les horaires de la mairie de Valence`, `combien font 12 fois 7` (réseau coupé : doit marcher).

**Phase 6 : lecteur façon Victor, mémos, agenda, mails**
- Crée sur `/sdcard/Victor/` (en `adb push`) :
  - `$VRDTB/<livre>/` : un **petit livre DAISY 2.02** (ncc.html + 2 smil + 2 mp3 générés, par exemple avec `ffmpeg` et une
    voix de synthèse du PC). Modèle de structure : `DaisyFixtures` dans `core/src/test/.../Phase6ScenarioTest.kt`.
  - `$VROtherBooks/Contes/` : 3 mp3 nommés `1 …`, `2 …`, `10 …`.
  - Un **ncc.html en windows-1252** avec accents.
- Réglages → « Choisir le dossier des livres » → `Victor` (automatisation UI du sélecteur Android).
- Scénarios :
  - `quels livres ai-je ?`, `lis le livre …` (journal `Lecture :` puis `Lecteur prêt`).
  - `chapitre suivant`, `va à la page 1`, `avance de 1 minute`, `où j'en suis ?`, `pose un signet`, `va au signet 1`.
  - `lis plus vite` (la vitesse du livre change, pas celle de la voix), `pause`, `reprends`.
  - Pendant la lecture, `dire.sh "quelle heure est-il ?"` : le livre se met en pause puis reprend.
  - `arrête la lecture dans 1 minute`.
  - `adb reboot`, puis `reprends mon livre` : reprend au bon endroit.
- Radio et podcasts :
  - `mets France Inter`, `mets RTL` (flux HLS), `radio suivante`, `pause`.
  - `abonne-moi au podcast Affaires sensibles` → `oui` → `écoute le podcast Affaires sensibles`.
- Musique : mp3 dans `/sdcard/Music` (relance l'indexation : redémarrage) → `mets de la musique`.
- Mémos : `enregistre un mémo` (avec le micro du PC si possible ; sinon vérifier « je n'ai rien entendu » après 6 s),
  `écoute mes mémos`, `supprime ce mémo` → `oui`.
- Notes et liste de courses : `note que …`, `lis mes notes`, `ajoute du pain et du lait à la liste`, `lis ma liste`.
- Agenda : s'il n'y a pas de compte Google, crée un **agenda local** en root, en sync adapter :
  `content insert --uri "content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=test&account_type=LOCAL"`
  avec `name`, `calendar_displayName`, `account_name`, `account_type`, `owner_account`, `calendar_access_level:i:700`, `visible:i:1`.
  Puis `ajoute un rendez-vous chez le dentiste demain à 10 heures` → `oui` → `qu'est-ce que j'ai demain ?`. Enfin `fais-moi le point`.
- Mails : seulement si je te donne un compte Gmail de test avec mot de passe d'application ; sinon « non testable ».

**Phase 7 : prise en main**
- Bouton Tutoriel (ou `dire.sh "tutoriel"`) : enchaîne les 8 leçons avec les bonnes réponses, et une mauvaise réponse
  pour vérifier l'indice. **Aucun appel, aucun SMS, aucun rappel ne doit être créé** (vérifie).
- `arrête le tutoriel` puis `reprends le tutoriel`.
- `est-ce que tout marche ?` : compare chaque point annoncé avec l'état réel.
- `sauvegarde mes réglages` → fichier dans `/sdcard/Android/data/fr.assistantvocal.app/files/Sauvegardes/`,
  **sans aucune clé** (`grep -i "key\|AIza" …` ne doit rien trouver).
  Modifie un surnom, restaure depuis les réglages, vérifie, et vérifie que les rappels restent programmés.
- `signale un problème` → Réglages → Partager le journal.

**Phase 8 : documents et podcasts hors ligne**
- Pousse dans `/sdcard/Victor/$VRText/` :
  - un PDF texte (une facture générée avec Python/reportlab, ou n'importe quel PDF libre) ;
  - un **PDF scanné** (image d'une lettre convertie en PDF) ;
  - un `.docx`, un `.txt` avec « Chapitre 1 / Chapitre 2 », un EPUB libre (par exemple Project Gutenberg en français).
- Scénarios :
  - `lis le PDF facture` : journal `Lecture du segment …` à la suite, sans trou.
  - `va à la page 2`, `chapitre suivant`, `où j'en suis ?` (pourcentage), `pose un signet`.
  - Une question pendant la lecture : la lecture s'arrête puis reprend.
  - Le PDF scanné est lu (OCR) ; un EPUB protégé est refusé proprement.
- Podcasts hors ligne :
  - `télécharge les podcasts` en wifi : le journal annonce la fin ; vérifie les fichiers avec
    `adb shell su -c ls -l /data/data/fr.assistantvocal.app/files/podcasts`.
  - Mode avion (`adb shell cmd connectivity airplane-mode enable`), puis `écoute le podcast Affaires sensibles` : épisode téléchargé.
  - Données mobiles sans wifi : le téléchargement est refusé (« pas en wifi »).

## Étape 4 : robustesse et ressources

- 30 minutes de lecture continue d'un livre, puis vérifie la mémoire (`dumpsys meminfo fr.assistantvocal.app`).
  Vérifie l'absence de plantage ou d'ANR (`adb logcat -b crash -d`, `dumpsys activity anr`).
- CPU au repos avec l'écoute du nom (`top -n 3 | grep assistantvocal`) : cible moins de 5 %.
- Batterie : `dumpsys batterystats --reset`, puis 1 h au repos, puis `dumpsys batterystats fr.assistantvocal.app`.
- Rafale de 50 phrases variées enchaînées (script) : aucune réponse vide, aucun plantage.
- TalkBack : active-le (`settings put secure enabled_accessibility_services …`).
  - Les boutons sont annoncés : `uiautomator dump`, vérifier `content-desc` / `text`.
  - L'appui long sur volume bas marche toujours avec TalkBack.

## Étape 5 : rapport final

Remplis `tools/emu/RAPPORT_TEST_EMULATEUR.md` :

```markdown
# Rapport de test sur émulateur — <date>
Émulateur : Genymotion Pixel 9, Android <version>, ABI <abi>, root <oui/non>, services Google <oui/non>
Tests automatiques : <n> / <n> OK

| Phase | Fonction | Phrase | Réponse obtenue | Résultat | Remarque |
|---|---|---|---|---|---|
| 1 | Heure | quelle heure est-il ? | « Il est 14 heures 30. » | OK | |

## Bugs trouvés et corrigés (un commit chacun)
## Bugs trouvés non corrigés (avec proposition)
## Non testable sur émulateur → à vérifier sur le vrai Pixel
## Verdict : prêt pour l'essai sur le Pixel de mon père ? (oui / non / oui sauf …)
```

Commit le rapport et les correctifs sur `test-emulateur`, pousse cette branche, puis donne-moi en français un résumé
court : ce qui marche, ce qui a été corrigé, ce qui reste à vérifier sur le vrai téléphone.
