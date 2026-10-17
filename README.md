# Assistant vocal pour personne aveugle (Android)

Assistant vocal en français, utilisable entièrement à la voix, pensé pour une personne aveugle.
Le nom de l'assistant est personnalisable. Il répond aux commandes simples sans internet et utilise
une intelligence artificielle (Gemini, Claude en secours) pour les questions libres.

> État : **phases 1 et 2 terminées** (socle, mot d'activation et déclencheurs). Les appels, SMS, la vision, etc.
> arrivent dans les phases suivantes. Ce README sera complété à la fin (guide à lire à voix haute, liste complète des commandes).

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
   boutons volume, nom appris.

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

## Développement

```bash
./gradlew :core:test :llm:test :app:testDebugUnitTest   # 80 tests (+ test d'intégration Vosk si le modèle est fourni)
./gradlew :app:assembleDebug
```

- `core/` : logique pure (routeur, confirmations, nom, IA abstraite, scénarios) — testée sans Android.
- `llm/` : fournisseurs Gemini (REST) et Claude (SDK officiel).
- `app/` : application Android (service, voix, sons, écrans).

Pas d'émulateur dans l'environnement de développement (pas de KVM) : les tests Android tournent avec Robolectric.

Documents : [RESEARCH.md](RESEARCH.md), [FEATURES.md](FEATURES.md), [OPEN_SOURCE.md](OPEN_SOURCE.md), [ARCHITECTURE.md](ARCHITECTURE.md).
