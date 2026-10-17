#!/usr/bin/env bash
# Appui physique simulé (root) : tools/emu/touche.sh bas|haut durée_en_s
C=$([ "$1" = haut ] && echo 115 || echo 114); D=/dev/input/event2
adb shell "su -c 'sendevent $D 1 $C 1; sendevent $D 0 0 0; sleep $2; sendevent $D 1 $C 0; sendevent $D 0 0 0'"
