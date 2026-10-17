# Mot d'activation : mesures et reproduction

Le mot d'activation (le nom de l'assistant) est détecté entièrement sur le téléphone avec Vosk
(`vosk-model-small-fr-0.22`, 41 Mo, Apache 2.0), sans entraînement, pour n'importe quel nom présent
dans le vocabulaire du modèle (presque tous les prénoms testés : Orphée, Balthazar, Hector, Jarvis,
Gédéon, Ulysse, Gaston, Merlin, Nestor, Casimir…).

## Fonctionnement (code : `core/src/main/kotlin/.../wake/WakeWord.kt`)

1. Un détecteur de niveau sonore ne réveille Vosk que lorsque quelqu'un parle (le silence ne coûte rien).
2. **Étape 1** : décodeur Vosk limité au nom (`["orphée", "[unk]"]`), en continu pendant la parole.
3. **Étape 2** : quand le nom sort avec une confiance suffisante, le passage exact (horodatage Vosk) est
   redécodé avec le nom **en concurrence avec 1000 mots français fréquents**. Le nom doit gagner.
4. **Calibration** (« apprends mon nom ») : 3 enregistrements, réglage du seuil, puis 15 s d'écoute
   pour compter les faux déclenchements (mode strict si besoin). Si le nom n'est pas reconnu tel quel,
   l'app apprend comment Vosk l'entend (variantes), en écartant les variantes faites de mots courants.

## Mesures (17 octobre 2026, voix de synthèse Piper siwis / tom / upmc)

| Nom | Phrases isolées : détection | Faux sur 108 phrases | Conversation de 2 min (nom dit 6 fois) | Faux en 2 min | Calcul |
|---|---|---|---|---|---|
| Orphée | 94 % | 0 | 5 / 6 | 1 | 3 % |
| Balthazar | 100 % | 1 | 6 / 6 | 2 | 4 % |
| Hector | 100 % | 1 | 5 / 6 | 0 | 3 % |
| Jarvis | 83 % | 1 | 6 / 6 | 0 | 3 % |
| Gédéon | 94 % | 0 | 6 / 6 | 0 | 3 % |

Les phrases « pièges » contenaient des mots proches (« Morphée », « orfèvre », « le hasard », « le bal »).
Un faux déclenchement de Balthazar vient de « **le hasard** fait bien les choses » : d'où l'intérêt du test
de 15 secondes à la calibration avec le vrai nom choisi. Un faux déclenchement n'a pas de conséquence grave :
l'assistant émet un bip et écoute ; aucune action sensible n'a lieu sans confirmation.

Étapes écartées après mesure :
- grammaire « nom seul » sans vérification : 96–100 % de détection mais 5 à 26 faux sur 108 ;
- vérification par transcription libre : 0 faux mais seulement 30–89 % de détection (le modèle préfère
  les mots courants : « Orphée » devient « en fait ») ;
- découpage maison des phrases (VAD) : faisait chuter la détection en conversation continue à 1–2 / 6.

**Limites** : voix de synthèse, pas de vraie pièce (écho, télévision, voix âgée). Le test sur le vrai
téléphone (voir la checklist du README) reste indispensable.

## Reproduire

```bash
python3 -m venv venv && ./venv/bin/pip install vosk==0.3.45 piper-tts
# modèle Vosk + voix Piper fr_FR (siwis, tom, upmc, qualité medium) dans models/
./venv/bin/python tools/wake/generate_fixtures.py models fixtures orphée balthazar hector jarvis gédéon
VOSK_MODEL=models/vosk-model-small-fr-0.22 WAKE_FIXTURES=fixtures ./gradlew :core:test --tests '*WakeIntegrationTest*'
# WAKE_DEBUG=1 affiche le détail des décisions ; WAKE_NAMES=hector limite aux noms donnés.
```

La liste `core/src/main/resources/.../mots_frequents.txt` vient de `wordfreq` (CC BY-SA 4.0),
filtrée sur le vocabulaire du modèle Vosk.
