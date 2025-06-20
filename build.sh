#!/bin/bash

source ../../env.sh

APK_PATH=app/build/outputs/apk/debug/app-debug.apk
ZIPALIGNED_APK=app/build/outputs/apk/debug/app-debug-aligned.apk
APK=apk

set -e

gradle clean

gradle assembleDebug

zipalign -v -p 4 $APK_PATH $ZIPALIGNED_APK

mkdir -p $APK

apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --out $APK/app-debug-signed.apk $ZIPALIGNED_APK 

