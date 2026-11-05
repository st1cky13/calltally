#!/bin/sh
# Builds the app with the Android tools in ~/local/calltally-build. The signing key stays in that folder, out of git.
#   CALLTALLY_SERVER=https://your-server sh android/app/build.sh
# CALLTALLY_CLEARTEXT=true allows plain http, only for a test build on an emulator.
set -e
: "${CALLTALLY_SERVER:?Set CALLTALLY_SERVER to the server address}"
HERE=$(cd "$(dirname "$0")" && pwd)
TOOLS="$HOME/local/calltally-build"
export JAVA_HOME="$TOOLS/jdk" PATH="$TOOLS/jdk/bin:$PATH"
SDK="$TOOLS/android-sdk"; BT="$SDK/build-tools/35.0.0"; JAR="$SDK/platforms/android-35/android.jar"
WORK="$TOOLS/out/${CALLTALLY_OUT:-app}"; rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/gen/com/calltally/app"
KEY="$TOOLS/calltally.keystore"; PASS="$TOOLS/keystore.pass"
sed "s/__CLEARTEXT__/${CALLTALLY_CLEARTEXT:-false}/" "$HERE/AndroidManifest.template.xml" > "$WORK/AndroidManifest.xml"
printf 'package com.calltally.app;\nfinal class Config {\n    static final String SERVER = "%s";\n}\n' "$CALLTALLY_SERVER" > "$WORK/gen/com/calltally/app/Config.java"
"$BT/aapt2" compile --dir "$HERE/res" -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/base.apk" -I "$JAR" --manifest "$WORK/AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 35 "$WORK/res.zip"
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -d "$WORK/classes" $(find "$HERE/src" "$WORK/gen" -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 26 --output "$WORK" $(find "$WORK/classes" -name '*.class')
python3 -I -c "import sys,zipfile;z=zipfile.ZipFile(sys.argv[1],'a');z.write(sys.argv[2],'classes.dex');z.close()" "$WORK/base.apk" "$WORK/classes.dex"
"$BT/zipalign" -f -p 4 "$WORK/base.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEY" --ks-pass "file:$PASS" --ks-key-alias calltally --out "$WORK/calltally.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify "$WORK/calltally.apk"
echo "$WORK/calltally.apk"
