#!/usr/bin/env python3
"""Fabrique les fichiers de test du lecteur (phases 6 et 8) dans le dossier donné, rangés comme une carte du Victor.

Usage : python generer_fichiers.py SORTIE   (dépendances : lameenc, pillow ; LibreOffice et ImageMagick pour docx/pdf)
Puis : adb push SORTIE/Victor /sdcard/  et  adb push SORTIE/Music /sdcard/
"""
import math, os, struct, subprocess, sys, zipfile
import lameenc
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
V = os.path.join(OUT, "Victor")


def mp3(path, seconds, freq):
    """Son bip-bip à la fréquence donnée (un bip par seconde) : reconnaissable, durée exacte."""
    rate = 22050
    pcm = bytearray()
    for i in range(int(seconds * rate)):
        on = (i % rate) < rate * 0.3
        pcm += struct.pack("<h", int(8000 * math.sin(2 * math.pi * freq * i / rate)) if on else 0)
    enc = lameenc.Encoder(); enc.set_bit_rate(32); enc.set_in_sample_rate(rate); enc.set_channels(1); enc.set_quality(7)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(enc.encode(bytes(pcm)) + enc.flush())


def smil(clips):
    pars = "".join(
        f'<par endsync="last" id="p{i * 2 + 1}"><text src="ncc.html#x" id="t{i * 2 + 1}"/>'
        f'<audio src="{src}" clip-begin="npt={b}s" clip-end="npt={e}s" id="a{i}"/></par>' for i, (src, b, e) in enumerate(clips))
    return f"<smil><body><seq>{pars}</seq></body></smil>"


def daisy(folder, title, ncc_encoding="utf-8"):
    d = os.path.join(V, "$VRDTB", folder)
    os.makedirs(d, exist_ok=True)
    ncc = (f'<?xml version="1.0" encoding="{ncc_encoding}"?><html><head><title>{title}</title>'
           f'<meta name="dc:title" content="{title}"/><meta name="ncc:totalTime" content="0:04:00"/></head><body>'
           f'<h1 class="title" id="t1"><a href="s01.smil#p1">{title}</a></h1>'
           '<h1 id="c1"><a href="s02.smil#p1">Chapitre 1 : le départ</a></h1>'
           '<span class="page-normal" id="pg1"><a href="s02.smil#p1">1</a></span>'
           '<h1 id="c2"><a href="s03.smil#p1">Chapitre 2 : l\'été</a></h1>'
           '<span class="page-normal" id="pg2"><a href="s03.smil#p1">2</a></span>'
           '</body></html>')
    with open(os.path.join(d, "ncc.html"), "wb") as f:
        f.write(ncc.encode(ncc_encoding))
    files = {"s01.smil": [("a01.mp3", "0.000", "5.000")],
             "s02.smil": [("a02.mp3", "0.000", "60.000"), ("a02.mp3", "60.000", "120.000")],
             "s03.smil": [("a03.mp3", "0.000", "120.000")]}
    for n, clips in files.items():
        with open(os.path.join(d, n), "w") as f:
            f.write(smil(clips))
    mp3(os.path.join(d, "a01.mp3"), 5, 440); mp3(os.path.join(d, "a02.mp3"), 120, 550); mp3(os.path.join(d, "a03.mp3"), 120, 660)


def page_image(lines, path):
    img = Image.new("L", (1240, 1754), 255)
    draw = ImageDraw.Draw(img)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf", 40)
    except OSError:
        font = ImageFont.load_default()
    y = 150
    for line in lines:
        draw.text((120, y), line, fill=0, font=font); y += 70
    img.save(path)


def office(src, fmt, outdir):
    subprocess.run(["libreoffice", "--headless", "--convert-to", fmt, "--outdir", outdir, src], check=True, capture_output=True)


def epub(path, title, chapters, protected=False):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip")
        z.writestr("META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
                   '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        if protected:
            z.writestr("META-INF/encryption.xml", '<?xml version="1.0"?><encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" '
                       'xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData><enc:EncryptionMethod Algorithm="http://ns.adobe.com/adept"/>'
                       '<enc:CipherData><enc:CipherReference URI="OEBPS/c1.xhtml"/></enc:CipherData></enc:EncryptedData></encryption>')
        items = "".join(f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(1, len(chapters) + 1))
        spine = "".join(f'<itemref idref="c{i}"/>' for i in range(1, len(chapters) + 1))
        z.writestr("OEBPS/content.opf", f'<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">'
                   f'<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>{title}</dc:title><dc:language>fr</dc:language>'
                   f'<dc:identifier id="id">test-{title}</dc:identifier></metadata><manifest>{items}</manifest><spine>{spine}</spine></package>')
        for i, (h, body) in enumerate(chapters, 1):
            z.writestr(f"OEBPS/c{i}.xhtml", f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>{h}</title></head>'
                       f'<body><h1>{h}</h1><p>{body}</p></body></html>')


# Livres DAISY : un normal, un ancien en windows-1252 avec accents.
daisy("Le_Grand_Meaulnes", "Le Grand Meaulnes")
daisy("Les_Memoires_d_Ete", "Les Mémoires d'été à Noël", ncc_encoding="windows-1252")

# Livre audio en MP3 : tri naturel attendu 1, 2, 10.
for n, freq in [("1 Cendrillon", 500), ("2 Le Chat botté", 600), ("10 Barbe bleue", 700)]:
    mp3(os.path.join(V, "$VROtherBooks", "Contes", n + ".mp3"), 20, freq)

# Musique du téléphone.
for n, freq in [("La mauvaise reputation", 520), ("Les copains d abord", 620)]:
    mp3(os.path.join(OUT, "Music", n + ".mp3"), 15, freq)

# Documents texte.
T = os.path.join(V, "$VRText")
os.makedirs(T, exist_ok=True)
tmp = os.path.join(OUT, "tmp"); os.makedirs(tmp, exist_ok=True)
with open(os.path.join(tmp, "facture.html"), "w") as f:
    f.write("<html><body><h1>Facture EDF</h1><p>Client : Jean Testeur, 12 rue de la Liberté, Saint-Marcel-lès-Valence.</p>"
            "<p>Montant à payer : 84,20 euros. Date limite de paiement : 15 novembre 2026.</p>"
            + "".join(f"<p>Ligne de consommation numéro {i} : 3,10 euros.</p>" for i in range(1, 40))
            + '<p style="page-break-before: always">Page deux : conditions générales de vente.</p>'
            + "".join(f"<p>Article {i} : le client règle sa facture dans les délais.</p>" for i in range(1, 30)) + "</body></html>")
office(os.path.join(tmp, "facture.html"), "pdf:writer_pdf_Export", tmp)
os.replace(os.path.join(tmp, "facture.pdf"), os.path.join(T, "facture EDF.pdf"))

page_image(["Mairie de Saint-Marcel-lès-Valence", "", "Monsieur,", "Le repas des aînés aura lieu", "le 15 novembre à midi,",
            "à la salle des fêtes.", "Merci de répondre avant le 5 novembre.", "", "Le maire"], os.path.join(tmp, "scan.png"))
Image.open(os.path.join(tmp, "scan.png")).convert("RGB").save(os.path.join(T, "courrier mairie.pdf"), "PDF", resolution=150)

with open(os.path.join(tmp, "lettre banque.html"), "w") as f:
    f.write("<html><body><h1>Lettre de la banque</h1><p>Monsieur, votre carte bancaire arrive à expiration en décembre. "
            "Une nouvelle carte vous sera envoyée par courrier.</p></body></html>")
office(os.path.join(tmp, "lettre banque.html"), "docx:MS Word 2007 XML", tmp)
os.replace(os.path.join(tmp, "lettre banque.docx"), os.path.join(T, "lettre banque.docx"))

with open(os.path.join(T, "souvenirs.txt"), "w") as f:
    f.write("Chapitre 1\n\n" + "Nous partions chaque été à la mer. " * 40 + "\n\nChapitre 2\n\n" + "L'hiver, nous restions au village. " * 40 + "\n")

epub(os.path.join(T, "Contes du lundi.epub"), "Contes du lundi",
     [("La dernière classe", "Ce matin-là, j'étais très en retard pour aller à l'école. " * 20),
      ("Le siège de Berlin", "Nous remontions l'avenue des Champs-Élysées avec le docteur. " * 20)])
epub(os.path.join(T, "Livre protégé.epub"), "Livre protégé", [("Chapitre 1", "Texte chiffré.")], protected=True)
print("Fichiers prêts dans", OUT)
