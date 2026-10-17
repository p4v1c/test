#!/usr/bin/env bash
# Affiche les éléments de l'écran : « x,y|texte|description|classe|id » (pour adb shell input tap x y).
T=$(mktemp); adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$T" >/dev/null
python3 -I - "$T" <<'P'
import sys,re,xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    t,d=n.get('text'),n.get('content-desc')
    if t or d or n.get('clickable')=='true':
        a,b,c,e=map(int,re.findall(r'\d+',n.get('bounds')))
        print(f"{(a+c)//2},{(b+e)//2}|{t}|{d}|{n.get('class').split('.')[-1]}|{n.get('resource-id')}")
P
rm -f "$T"
