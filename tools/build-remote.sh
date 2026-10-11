#!/usr/bin/env bash
# Construit l'APK de Cosmo Robot sur une machine distante, puis l'aligne et le
# signe en local.
#
# Pourquoi distant : Gradle + Chaquopy (pip, compilation des .pyc, empaquetage
# de Python et numpy) demande plusieurs Go de mémoire ; sur une petite machine
# partagée, l'OOM killer finit par tuer d'autres services. La machine de build
# est un serveur de production : tout y tourne en nice 19 / ionice idle, en
# espace utilisateur, sans démon Gradle et avec peu de workers.
#
# Pourquoi signer en local : la clé reste sur la machine du développeur, et la
# même clé debug signe toutes les versions (une mise à jour par-dessus une
# version précédente l'exige).
#
# Usage : tools/build-remote.sh [--clean]
# Variables surchargeables :
#   COSMO_REMOTE  hôte ssh de build          (debian@lrd-heberg.fr)
#   COSMO_RDIR    dossier de build distant   (cosmo-build, relatif au $HOME distant)
#   COSMO_RSDK    SDK Android distant        ($HOME/sb-android/Android)
#   COSMO_RJDK    JDK 17 distant             ($HOME/sb-android/jdk17)
#   COSMO_RPY     Python 3.12 distant        (/usr/bin/python3.12)
#   BUILD_TOOLS   build-tools locaux         (le plus récent de ~/Android/build-tools)
#   KEYSTORE      clé de signature           (~/.android/debug.keystore)
#   OUT_DIR       dépôt de l'APK final       (~/partage/CosmoRobot)
set -euo pipefail

REMOTE=${COSMO_REMOTE:-debian@lrd-heberg.fr}
RDIR=${COSMO_RDIR:-cosmo-build}
RSDK=${COSMO_RSDK:-'$HOME/sb-android/Android'}
RJDK=${COSMO_RJDK:-'$HOME/sb-android/jdk17'}
RPY=${COSMO_RPY:-/usr/bin/python3.12}
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
OUT_DIR=${OUT_DIR:-$HOME/partage/CosmoRobot}

CLEAN=""
[ "${1:-}" = "--clean" ] && CLEAN="clean"

ROOT=$(cd "$(dirname "$0")/.." && pwd)

if [ -z "${BUILD_TOOLS:-}" ]; then
    for d in "$HOME/Android/build-tools" "$HOME/Android/Sdk/build-tools"; do
        [ -d "$d" ] && BUILD_TOOLS=$(ls -d "$d"/*/ | sort -V | tail -1) && break
    done
fi
BUILD_TOOLS=${BUILD_TOOLS%/}
[ -x "$BUILD_TOOLS/zipalign" ] && [ -x "$BUILD_TOOLS/apksigner" ] \
    || { echo "zipalign/apksigner introuvables (BUILD_TOOLS=$BUILD_TOOLS)" >&2; exit 1; }
[ -f "$KEYSTORE" ] || { echo "Clé absente : $KEYSTORE" >&2; exit 1; }

VERSION=$(sed -n "s/^[[:space:]]*versionName[[:space:]]*['\"]\([^'\"]*\)['\"].*/\1/p" \
    "$ROOT/app/build.gradle" | head -1)
[ -n "$VERSION" ] || { echo "versionName introuvable dans app/build.gradle" >&2; exit 1; }

echo "== Envoi des sources vers $REMOTE:$RDIR"
# Les dossiers exclus ne sont pas effacés par --delete : le cache de build
# distant est conservé d'une fois sur l'autre.
rsync -az --delete \
    --exclude .git/ --exclude build/ --exclude .gradle/ --exclude .idea/ \
    --exclude local.properties --exclude '*.keystore' --exclude '*.jks' \
    --exclude '*.apk' --exclude __pycache__/ \
    "$ROOT/" "$REMOTE:$RDIR/"

echo "== Build assembleRelease ($VERSION)"
# ssh recolle les arguments en une seule ligne de commande, que le shell
# distant redécoupe : un argument vide disparaîtrait, d'où le « - » pour
# « pas de clean ». Les $HOME des chemins sont développés côté distant.
ssh "$REMOTE" bash -s -- "$RDIR" "$RSDK" "$RJDK" "$RPY" "${CLEAN:--}" <<'EOF'
set -euo pipefail
cd "$HOME/$1"
export ANDROID_HOME="$2" JAVA_HOME="$3"
TASKS="assembleRelease"
[ "$5" = clean ] && TASKS="clean assembleRelease"
export PATH="$JAVA_HOME/bin:$PATH"
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew
nice -n 19 ionice -c3 ./gradlew --no-daemon --console=plain --max-workers=4 \
    -PbuildPython="$4" $TASKS
EOF

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== Rapatriement"
rsync -az "$REMOTE:$RDIR/app/build/outputs/apk/release/app-release-unsigned.apk" "$WORK/unsigned.apk"

echo "== Alignement et signature ($(basename "$BUILD_TOOLS"))"
# -P 16 : les .so non compressés sont alignés sur 16 Ko, exigé par les
# téléphones à pages de 16 Ko (Android 15+) et par le Play Store.
"$BUILD_TOOLS/zipalign" -f -P 16 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$WORK/signed.apk" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$WORK/signed.apk"

mkdir -p "$OUT_DIR"
DEST="$OUT_DIR/CosmoRobot-$VERSION.apk"
cp "$WORK/signed.apk" "$DEST"
echo "== $DEST ($(du -h "$DEST" | cut -f1), sha256 $(sha256sum "$DEST" | cut -c1-16)…)"
