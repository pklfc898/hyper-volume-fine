#!/usr/bin/env bash
# 澎湃音阶微调 — 不依赖 Gradle 的最小构建脚本
#
# 依赖：
#   - JDK 17+（JAVA_HOME）
#   - Android SDK：platform android-37.0、build-tools 37.0.0
#   - libxposed API 102 的 classes.jar（LIBXPOSED_JAR）
#
# 用法：
#   export ANDROID_SDK_ROOT=/path/to/android-sdk
#   export JAVA_HOME=/path/to/jdk
#   export LIBXPOSED_JAR=/path/to/libxposed-api-102.jar
#   export KS_PASS=你的签名口令
#   bash build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$ROOT/.android-sdk}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-37.0.0}"
PLATFORM="${ANDROID_PLATFORM:-android-37.0}"
BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
LIBXPOSED="${LIBXPOSED_JAR:-$ROOT/libs/libxposed-api-102.jar}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVAC="${JAVA_BIN}javac"
KEYTOOL="${JAVA_BIN}keytool"
KS="${KS_FILE:-$ROOT/keys/release.jks}"
KS_ALIAS="${KS_ALIAS:-hypervolumenice}"
OUT_DIR="${OUT_DIR:-$ROOT/dist}"
: "${KS_PASS:?请先设置 KS_PASS（签名密钥口令）}"

for f in "$ANDROID_JAR" "$LIBXPOSED"; do
  if [ ! -f "$f" ]; then
    echo "缺少依赖：$f" >&2
    exit 1
  fi
done

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; D8="$BT/d8.bat"; APKSIGNER="$BT/apksigner.bat" ;;
  *)                    EXE="";     D8="$BT/d8";     APKSIGNER="$BT/apksigner" ;;
esac

VERSION="$(grep -o 'android:versionName="[^"]*"' "$ROOT/AndroidManifest.xml" | head -1 | sed 's/.*="//; s/"$//')"
OUT_APK="$OUT_DIR/hyper-volume-fine-${VERSION}.apk"
BUILD="$ROOT/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT_DIR"

echo "[1/7] aapt2 compile"
"$BT/aapt2$EXE" compile --dir "$ROOT/res" -o "$BUILD/res.zip"

echo "[2/7] aapt2 link"
"$BT/aapt2$EXE" link \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  -o "$BUILD/base.apk"

echo "[3/7] javac"
find "$ROOT/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" -source 17 -target 17 -encoding UTF-8 -nowarn \
  -cp "$ANDROID_JAR;$LIBXPOSED" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "[4/7] d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$D8" --release --min-api 29 --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "[5/7] package"
PYTHON_BIN="${PYTHON:-python3}"
command -v "$PYTHON_BIN" >/dev/null 2>&1 || PYTHON_BIN="python"
"$PYTHON_BIN" - "$BUILD" "$ROOT" <<'PY'
import sys, zipfile, shutil, os
build, root = sys.argv[1], sys.argv[2]
src = os.path.join(build, 'base.apk')
dst = os.path.join(build, 'unsigned.apk')
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a') as z:
    z.writestr('classes.dex', open(os.path.join(build, 'dex', 'classes.dex'), 'rb').read(),
               compress_type=zipfile.ZIP_STORED)
    for name in ('module.prop', 'java_init.list', 'scope.list'):
        path = os.path.join(root, 'META-INF', 'xposed', name)
        z.writestr('META-INF/xposed/' + name, open(path, 'rb').read(),
                   compress_type=zipfile.ZIP_DEFLATED)
print('packaged:', dst)
PY

echo "[6/7] zipalign"
"$BT/zipalign$EXE" -p -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "[7/7] sign"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  "$KEYTOOL" -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=HyperVolumeFine, O=HyperVolumeFine, C=CN" \
    -storepass "$KS_PASS" -keypass "$KS_PASS"
fi
"$APKSIGNER" sign \
  --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 29 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUT_APK" "$BUILD/aligned.apk"

echo "done: $OUT_APK"
ls -l "$OUT_APK"
