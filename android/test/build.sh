#!/bin/sh
# Builds the test app with the Android tools in ~/local/calltally-build. The signing key stays in that folder, out of git.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
TOOLS="$HOME/local/calltally-build"
export JAVA_HOME="$TOOLS/jdk" PATH="$TOOLS/jdk/bin:$PATH"
SDK="$TOOLS/android-sdk"; BT="$SDK/build-tools/35.0.0"; JAR="$SDK/platforms/android-35/android.jar"
WORK="$TOOLS/out/test"; rm -rf "$WORK"; mkdir -p "$WORK/classes"
KEY="$TOOLS/calltally.keystore"; PASS="$TOOLS/keystore.pass"
if [ ! -f "$KEY" ]; then
  (umask 077; head -c 24 /dev/urandom | base64 > "$PASS")
  keytool -genkeypair -keystore "$KEY" -storepass:file "$PASS" -alias calltally -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Call Tally" 2>/dev/null
  chmod 600 "$KEY"
fi
"$BT/aapt2" link -o "$WORK/base.apk" -I "$JAR" --manifest "$HERE/AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 35
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -d "$WORK/classes" $(find "$HERE/src" -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 26 --output "$WORK" $(find "$WORK/classes" -name '*.class')
python3 -I -c "import sys,zipfile;z=zipfile.ZipFile(sys.argv[1],'a');z.write(sys.argv[2],'classes.dex');z.close()" "$WORK/base.apk" "$WORK/classes.dex"
"$BT/zipalign" -f -p 4 "$WORK/base.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEY" --ks-pass "file:$PASS" --ks-key-alias calltally --out "$WORK/calltally-test.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify "$WORK/calltally-test.apk"
echo "$WORK/calltally-test.apk"
