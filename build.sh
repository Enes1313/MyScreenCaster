#!/bin/bash

export GRADLE_USER_HOME=~/android/gradle-8.14.2
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/ndk/21.4.7075529

export PATH=$GRADLE_USER_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_NDK_ROOT:$PATH

APK_PATH=app/build/outputs/apk/debug/app-debug.apk
ZIPALIGNED_APK=app/build/outputs/apk/debug/app-debug-aligned.apk
APK=apk

set -e

gradle clean

gradle assembleDebug

$ANDROID_SDK_ROOT/build-tools/30.0.3/zipalign -v -p 4 $APK_PATH $ZIPALIGNED_APK

mkdir -p $APK

$ANDROID_SDK_ROOT/build-tools/30.0.3/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --out $APK/app-debug-signed.apk $ZIPALIGNED_APK 

