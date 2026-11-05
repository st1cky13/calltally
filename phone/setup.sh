#!/data/data/com.termux/files/usr/bin/sh
# Sets up calltally inside Termux. Safe to run again.
set -e
BASE="__BASE_URL__"
DIR="$HOME/.calltally"

# Upgrade first: on a fresh Termux, installing alone can update curl without the OpenSSL it needs, which breaks curl.
pkg upgrade -y </dev/null
pkg install -y python termux-api curl </dev/null
mkdir -p "$DIR"
curl -fsSL "$BASE/phone/calltally-phone.py" -o "$DIR/calltally-phone.py"
printf '%s\n' "$BASE" > "$DIR/server"
cat > "$DIR/run.sh" <<RUN
#!$PREFIX/bin/sh
export HOME="$HOME"
export PATH="$PREFIX/bin:\$PATH"
exec "$PREFIX/bin/python" "$DIR/calltally-phone.py" >> "$DIR/last.log" 2>&1
RUN
chmod 700 "$DIR/run.sh"

echo "First run. Allow each permission Android asks for."
tries=0
until "$PREFIX/bin/python" "$DIR/calltally-phone.py"; do
  tries=$((tries + 1))
  if [ "$tries" -ge 6 ]; then
    echo "It still can't read your calls, texts or contacts. Check the permissions for Termux:API, then run this again."
    exit 1
  fi
  echo "Trying again in 20 seconds."
  sleep "${CALLTALLY_RETRY_WAIT:-20}"
done

termux-job-scheduler --job-id 8093 --script "$DIR/run.sh" --period-ms 900000 --network any --persisted true
echo "Done. It will upload about every 15 minutes."
