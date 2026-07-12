#!/bin/bash
set -e
cd /data/local/projects/termux-app-ui-improve
OUT=/storage/emulated/0/Download
ABIS="arm64-v8a armeabi-v7a x86 x86_64"
for abi in $ABIS; do
  echo "============================================================"
  echo ">>> BUILD $abi"
  echo "============================================================"
  TERMUX_ARCH="$abi" ./gradlew assembleRelease -Dorg.gradle.daemon=false --no-daemon
  apk=$(ls -1 app/build/outputs/apk/release/termux-app_*_universal.apk 2>/dev/null | head -1)
  if [ -z "$apk" ]; then
    echo "!!! APK not found for $abi"
    exit 1
  fi
  dst="$OUT/termux-app_$(date +%Y%m%d)_$abi.apk"
  cp "$apk" "$dst"
  echo ">>> $abi -> $dst ($(du -h "$dst" | cut -f1))"
done
echo "ALL DONE"
