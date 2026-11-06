#!/usr/bin/env bash
# فایل‌های API رسمی OpenVPN for Android (AIDL) را از مخزن اصلی می‌گیرد و در پروژه می‌گذارد.
# این فایل‌ها داخل پروژه ذخیره نمی‌شوند تا همیشه با نسخه‌ی واقعی برنامه یکی باشند.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

git clone --depth 1 --no-recurse-submodules https://github.com/schwabe/ics-openvpn.git "$TMP/ics"

AIDL_DIR="$TMP/ics/main/src/main/aidl/de/blinkt/openvpn/api"
if [ ! -d "$AIDL_DIR" ]; then
  AIDL_DIR="$(find "$TMP/ics" -type d -path '*/src/main/aidl/de/blinkt/openvpn/api' -print -quit)"
fi

JAVA_FILE="$TMP/ics/main/src/main/java/de/blinkt/openvpn/api/APIVpnProfile.java"
if [ ! -f "$JAVA_FILE" ]; then
  JAVA_FILE="$(find "$TMP/ics" -type f -name APIVpnProfile.java -print -quit)"
fi

if [ -z "${AIDL_DIR:-}" ] || [ ! -d "$AIDL_DIR" ] || [ -z "${JAVA_FILE:-}" ] || [ ! -f "$JAVA_FILE" ]; then
  echo "فایل‌های API در مخزن ics-openvpn پیدا نشد؛ ساختار مخزن عوض شده است." >&2
  exit 1
fi

mkdir -p "$ROOT/app/src/main/aidl/de/blinkt/openvpn/api" \
         "$ROOT/app/src/main/java/de/blinkt/openvpn/api"
cp "$AIDL_DIR"/*.aidl "$ROOT/app/src/main/aidl/de/blinkt/openvpn/api/"
cp "$JAVA_FILE" "$ROOT/app/src/main/java/de/blinkt/openvpn/api/"

echo "فایل‌های API کپی شدند:"
ls "$ROOT/app/src/main/aidl/de/blinkt/openvpn/api/" "$ROOT/app/src/main/java/de/blinkt/openvpn/api/"
