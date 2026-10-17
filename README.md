# Assistant vocal pour personne aveugle (Android)

Assistant vocal en français, utilisable entièrement à la voix, pensé pour une personne aveugle.
Le nom de l'assistant est personnalisable. Il répond aux commandes simples sans internet et utilise
une intelligence artificielle (Gemini, Claude en secours) pour les questions libres.

> État : **phase 1 terminée** (socle). Le mot d'activation, les appels, SMS, la vision, etc. arrivent
> dans les phases suivantes. Ce README sera complété à la fin (guide à lire à voix haute, liste complète des commandes).

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
3. Construire et installer :

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n fr.assistantvocal.app/.MainActivity
```

4. Accepter l'autorisation du micro et des notifications.
5. Ouvrir « Réglages, pour un proche » : choisir le nom, coller la clé Gemini, appuyer sur
   « Enregistrer », puis « Tester l'intelligence artificielle ».
6. Appuyer sur « Autoriser à fonctionner en arrière-plan ».

### Obtenir une clé Gemini (gratuite)

1. Aller sur <https://aistudio.google.com/apikey> avec un compte Google.
2. « Create API key », copier la clé, la coller dans les réglages de l'app.
3. Les limites gratuites réelles du projet sont visibles sur <https://aistudio.google.com/rate-limit>.

### Clé Claude (optionnelle, payante mais très peu chère)

<https://console.anthropic.com> → API Keys. Environ 0,0003 $ par question avec Claude Haiku.

## Vie privée

- Rien n'est envoyé avant que la personne appuie sur « Parler » (puis, en phase 2, dise le nom).
- Seule la phrase reconnue (et plus tard la photo, pour les fonctions de vision) part vers Google ou Anthropic.
- **Le palier gratuit de Gemini autorise Google à utiliser les données pour améliorer ses produits.**
- « Active le mode privé » : plus rien n'est envoyé à l'IA, seules les commandes simples fonctionnent.
- Les clés API sont chiffrées sur le téléphone (Android Keystore).

## Développement

```bash
./gradlew :core:test :llm:test :app:testDebugUnitTest   # 60 tests
./gradlew :app:assembleDebug
```

- `core/` : logique pure (routeur, confirmations, nom, IA abstraite, scénarios) — testée sans Android.
- `llm/` : fournisseurs Gemini (REST) et Claude (SDK officiel).
- `app/` : application Android (service, voix, sons, écrans).

Pas d'émulateur dans l'environnement de développement (pas de KVM) : les tests Android tournent avec Robolectric.

Documents : [RESEARCH.md](RESEARCH.md), [FEATURES.md](FEATURES.md), [OPEN_SOURCE.md](OPEN_SOURCE.md), [ARCHITECTURE.md](ARCHITECTURE.md).
