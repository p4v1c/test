# OPEN_SOURCE.md — Projets et données réutilisés

Usage visé : personnel et non commercial. Toutes les licences ci-dessous le permettent. Les poids indiqués sont ceux téléchargés sur le téléphone.

## Retenus

| Projet | Licence | Maintenance | Poids | Hors ligne | Français | Ce qu'on réutilise |
|---|---|---|---|---|---|---|
| **[Vosk](https://alphacephei.com/vosk/) + `vosk-model-small-fr-0.22`** | Apache 2.0 (code et modèle) | Active | 41 Mo | ✅ | Bon (WER ~20-24 %) | **Mot d'activation** (grammaire restreinte `[nom, "[unk]"]`), reconnaissance hors ligne des commandes et du « oui/non » de confirmation |
| **vosk-android 0.3.47 + JNA** | Apache 2.0 / Apache 2.0-LGPL | Active | ~10 Mo/ABI | ✅ | — | Moteur du mot d'activation dans l'app (arm64 + x86_64 seulement) |
| **Liste de mots fréquents [wordfreq](https://github.com/rspeer/wordfreq)** | Données CC BY-SA 4.0 | Figée | 8 Ko | ✅ | Oui | 1000 mots « concurrents » pour vérifier le nom |
| **[Piper](https://github.com/rhasspy/piper) + voix siwis/tom/upmc** | MIT / voix variables | Active | — | ✅ | Oui | **Tests uniquement** : génération d'enregistrements pour mesurer le mot d'activation |
| **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** | Apache 2.0 | Très active (k2-fsa) | lib ~10 Mo + modèles | ✅ | ASR streaming fr (121 Mo int8) ; KWS : pas de modèle fr | VAD Silero (filtre avant le mot d'activation), **voix Piper françaises** (TTS optionnel), ASR fr streaming en option, KWS expérimental |
| **[Silero VAD](https://github.com/snakers4/silero-vad)** (via sherpa-onnx) | MIT | Active | ~2 Mo | ✅ | Indépendant de la langue | **Pas utilisé finalement** : un détecteur de niveau simple suffit (mesuré) |
| **Voix Piper fr_FR** (siwis, tom, upmc…) ([Hugging Face](https://huggingface.co/rhasspy/piper-voices/tree/main/fr/fr_FR)) | Variable selon la voix (à vérifier voix par voix, certaines CC BY-NC) | Stable | 20-60 Mo/voix | ✅ | Oui | Voix hors ligne optionnelle |
| **[Google ML Kit](https://developers.google.com/ml-kit)** (Text Recognition v2 latin, Barcode, Image Labeling, Object Detection) | Gratuit, conditions Google (pas open source) | Active | 5-20 Mo selon module | ✅ | OCR latin OK | **Utilisé (phase 4)** : OCR latin 16.0.1 et codes-barres/DataMatrix 17.3.0, modèles inclus dans l'app |
| **CameraX 1.4** (AndroidX) | Apache 2.0 | Active | — | ✅ | — | **Utilisé (phase 4)** : caméra sans aperçu depuis le service, lampe, analyse d'image |
| **[Dicio](https://github.com/Stypox/dicio-android)** | GPL-3.0 | Active | — | ✅ | Oui | **Idées seulement** (structure des « skills », phrases françaises). Pas de copie de code pour garder une licence libre de choix |
| **[TalkBack](https://github.com/google/talkback)** | Apache 2.0 | Active | — | — | — | Bonnes pratiques : focus audio, gestion des touches dans un AccessibilityService, earcons |
| **[Soundscape (Microsoft)](https://github.com/microsoft/soundscape)** | MIT | Archivé (repris par Scottish Tech Army) | — | — | — | Idées : description « autour de moi » avec repères horaires, filtrage des lieux intéressants |
| **[openWakeWord](https://github.com/dscripka/openWakeWord)** | Code Apache 2.0, modèles pré-entraînés CC BY-NC-SA 4.0 | Active | ~1 Mo/modèle | ✅ | Entraînement requis par nom | **Option avancée** : un proche peut entraîner un modèle `.tflite`/`.onnx` du nom pour plus de précision |
| **Porcupine (Picovoice)** | Propriétaire, **palier gratuit fermé le 30/06/2026** | — | — | ✅ | Oui | Option uniquement si l'utilisateur fournit sa clé et son `.ppn` |
| **Anthropic Java SDK** (`com.anthropic:anthropic-java`) | MIT | Officiel | — | ❌ | — | `ClaudeProvider` |
| **OkHttp + kotlinx.serialization** | Apache 2.0 | Active | — | — | — | `GeminiProvider` (REST `generateContent`), APIs de données |
| **JavaMail pour Android** (`com.sun.mail:android-mail` 1.6.7) | EPL-2.0 / GPL+CE | Stable | ~0,7 Mo | ❌ | — | **Utilisé (phase 6)** : Gmail en IMAP/SMTP (Angus Mail écarté : dépendances Java absentes d'Android) |
| **Media3 ExoPlayer 1.4.1** (+ HLS) | Apache 2.0 | Active (Google) | ~2 Mo | ✅ | — | **Utilisé (phase 6)** : livres, radios, podcasts, musique, mémos |
| **AndroidX DocumentFile** | Apache 2.0 | Stable | petit | ✅ | — | **Utilisé (phase 6)** : lecture du dossier des livres choisi par un proche |
| **Format DAISY 2.02 / 3** (spécifications du DAISY Consortium) | Spécifications ouvertes | — | — | ✅ | — | **Implémenté (phase 6)** : analyse ncc.html, .ncx et SMIL, sans bibliothèque externe |
| **Tests** : JUnit, Robolectric (MIT), MockWebServer (Apache 2.0), Turbine, Espresso | — | Active | — | — | — | Tests unitaires et instrumentés |

## Données et APIs gratuites

| Service | Conditions | Clé | Usage |
|---|---|---|---|
| [Open-Meteo](https://open-meteo.com/en/terms) | Non commercial, < 10 000 appels/jour, CC BY 4.0 (citer la source) | Non | **Utilisé (phase 5)** : météo actuelle et 7 jours (modèles Météo-France inclus) |
| [Géoplateforme IGN — géocodage BAN](https://www.data.gouv.fr/fr/posts/lapi-adresse-de-la-base-adresse-nationale-est-transferee-a-lign-10/) (`data.geopf.fr/geocodage`) | Service public ouvert | Non (vérifié) | **Utilisé (phase 5)** : « Où suis-je ? », recherche d'adresse et de commune |
| [Nominatim (OSM)](https://operations.osmfoundation.org/policies/nominatim/) | 1 requête/s max, User-Agent obligatoire, ODbL | Non | Repli hors de France |
| [Overpass API (OSM)](https://wiki.openstreetmap.org/wiki/Overpass_API) | Usage raisonnable, ODbL | Non | **Utilisé (phase 5)** : lieux proches (pharmacies, boulangeries, médecins, distributeurs…) |
| [Open Food Facts](https://world.openfoodfacts.org/data) | ODbL, User-Agent obligatoire, limites de débit | Non | **Utilisé (phase 4)** : produits par code-barres, allergènes, Nutri-Score |
| [Base de données publique des médicaments](https://base-donnees-publique.medicaments.gouv.fr/telechargement) | Licence Ouverte (Etalab) | Non | **Utilisé (phase 4)** : table CIP13 → nom et présentation, téléchargée à la compilation |
| [Radio Browser](https://www.radio-browser.info/) | Gratuit, communautaire | Non | **Utilisé (phase 6)** : recherche de radios (vérifié en réel) |
| Flux Radio France (`icecast.radiofrance.fr`) | Flux publics officiels | Non | **Utilisé (phase 6)** : France Inter, France Info, France Culture, France Musique, FIP, Mouv' |
| [Annuaire iTunes Search](https://performance-partners.apple.com/search-api) | Gratuit, ~20 requêtes/min | Non | **Utilisé (phase 6)** : trouver le flux RSS d'un podcast |
| Flux RSS (France Info, Le Monde, Radio France podcasts…) | Usage personnel | Non | **Utilisé (phase 5)** : titres France Info par thème, journal régional au choix |
| [PRIM Île-de-France Mobilités](https://prim.iledefrance-mobilites.fr/) | Gratuit avec compte | Oui (gratuite) | Prochains passages en Île-de-France |
| [transport.data.gouv.fr](https://transport.data.gouv.fr/) | Licences ouvertes selon le réseau | Selon réseau | GTFS-RT / SIRI hors Île-de-France |
| [Wikipédia REST API](https://fr.wikipedia.org/api/rest_v1/) | CC BY-SA, User-Agent identifiant obligatoire | Non | **Utilisé (phase 5)** : recherche et résumé par défaut |
| [Tavily](https://tavily.com/) | ~1 000 recherches/mois gratuites | Oui | **Utilisé si clé (phase 5)** : recherche web d'actualité |

## Écartés

| Projet | Raison |
|---|---|
| whisper.cpp | Trop lent sur téléphone pour une réponse en moins de 2 s ; utile seulement pour transcrire un message long a posteriori (option future) |
| Parakeet TDT 0.6B v3 (sherpa-onnx) | Excellent multilingue, mais ~640 Mo en int8 : trop lourd pour l'usage |
| Vosk grand modèle fr (1,4 Go) | Trop lourd, pas de grammaire dynamique |
| Rhasspy / OpenVoiceOS / Leon / Mycroft | Conçus pour un serveur ou un Raspberry Pi, pas pour Android |
| Home Assistant Assist | Nécessite un serveur domotique (pourra être ajouté si la maison en a un) |
| Modèles TFLite de billets | Aucun modèle euro open source fiable trouvé |
| EncryptedSharedPreferences (androidx.security-crypto) | **Déprécié par Google en 2025.** Remplacé par un petit chiffrement AES-GCM avec une clé dans l'Android Keystore (même résultat : les clés API ne sont jamais en clair sur le disque) |
