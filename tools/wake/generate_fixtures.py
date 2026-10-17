"""Génère des enregistrements de test (WAV 16 kHz mono) avec des voix françaises Piper.

Usage :
  python generate_fixtures.py <dossier_voix_piper> <dossier_sortie> <nom> [<nom> ...]

Produit pour chaque nom (sans accents dans les noms de fichiers) : name_<nom>_<voix>_<vitesse>_<i>.wav (le nom seul ou en début de phrase),
et une fois : neg_<voix>_<i>.wav (phrases du quotidien sans le nom), plus stream_<nom>.wav : 2 minutes
de conversation dans lesquelles le nom est dit 6 fois (fichier stream_<nom>.txt = positions).
"""
import io, os, sys, unicodedata, wave
import numpy as np
from piper import PiperVoice, SynthesisConfig

NEG = ["Bonjour, comment ça va aujourd'hui ?", "Il fait beau ce matin, on va se promener.", "Tu as vu le match hier soir ?",
 "J'ai besoin d'acheter du pain et du lait.", "Le facteur est passé ce matin.", "Il est quelle heure maintenant ?",
 "Ma fille m'appelle tous les dimanches.", "On mange à midi et demi.", "Le chat dort sur le canapé.",
 "Il faut que je prenne mes médicaments.", "La radio parle de la politique.", "Tu veux un café ou un thé ?",
 "Les voisins font du bruit.", "Je vais chez le médecin mardi.", "Le jardin a besoin d'eau.",
 "C'est l'or qui fait la fortune.", "Morphée m'a pris dans ses bras.", "L'orfèvre a fait une bague.",
 "Il a dit oui sans réfléchir.", "Mon frère habite à Lyon.", "La télé est trop forte.", "On fera ça demain.",
 "Je ne trouve plus mes lunettes.", "Le boulanger ferme à dix-neuf heures.", "Il pleut depuis trois jours.",
 "Attends, je cherche mes clés.", "Passe-moi le sel s'il te plaît.", "Les enfants rentrent de l'école.",
 "Victor Hugo a écrit les Misérables.", "Le bal du village est samedi.", "Elle a balancé la balle très loin.",
 "Le hasard fait bien les choses.", "J'ai mal au dos ce matin.", "On regarde le journal de vingt heures.",
 "Mon fils arrive avec sa femme.", "La pharmacie est fermée le dimanche."]

def synth(voice, text, ls=1.0):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        voice.synthesize_wav(text, w, syn_config=SynthesisConfig(length_scale=ls))
    buf.seek(0)
    with wave.open(buf) as w:
        sr = w.getframerate(); a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
    n = int(len(a) * 16000 / sr)
    return np.interp(np.linspace(0, len(a) - 1, n), np.arange(len(a)), a)

def save(path, a):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes(np.clip(a, -32768, 32767).astype(np.int16).tobytes())

def main():
    vdir, out, names = sys.argv[1], sys.argv[2], sys.argv[3:]
    os.makedirs(out, exist_ok=True)
    rng = np.random.default_rng(1)
    voices = {v: PiperVoice.load(os.path.join(vdir, f"fr_FR-{v}-medium.onnx")) for v in ["siwis", "tom", "upmc"]}
    for v, voice in voices.items():
        for i, t in enumerate(NEG):
            save(f"{out}/neg_{v}_{i:02d}.wav", synth(voice, t))
    for name in names:
        disp = name.capitalize()
        name = "".join(c for c in unicodedata.normalize("NFD", name) if unicodedata.category(c) != "Mn")  # fichiers en ASCII
        for v, voice in voices.items():
            for ls in (0.85, 1.0, 1.25):
                for i, t in enumerate([f"{disp}.", f"{disp}, quelle heure est-il ?"]):
                    save(f"{out}/name_{name}_{v}_{ls}_{i}.wav", synth(voice, t, ls))
        # flux continu : phrases enchaînées, le nom 6 fois, bruit de fond léger
        parts, marks, pos = [], [], 0
        sentences = NEG * 2
        rng.shuffle(sentences)
        for k, t in enumerate(sentences[:48]):
            voice = list(voices.values())[k % 3]
            if k % 8 == 4:
                a = synth(voice, f"{disp}, quelle heure est-il ?")
                marks.append(pos)
            else:
                a = synth(voice, t)
            gap = np.zeros(int(16000 * rng.uniform(0.4, 1.2)))
            parts += [a, gap]; pos += len(a) + len(gap)
        s = np.concatenate(parts)
        s = s + rng.normal(0, np.sqrt(np.mean(s ** 2) / 10 ** 1.5), len(s))  # SNR 15 dB
        save(f"{out}/stream_{name}.wav", s)
        with open(f"{out}/stream_{name}.txt", "w") as f:
            f.write("\n".join(str(m) for m in marks))

if __name__ == "__main__":
    main()
