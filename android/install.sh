#!/bin/bash
# Builds the debug APK and installs it on a connected device over USB.
# Sets ANDROID_HOME/JAVA_HOME for this invocation only (nothing global).
#
# Requires: Android Studio installed (for the SDK at ~/Library/Android/sdk)
# and `brew install openjdk@21` (Gradle's Kotlin script compiler can't parse
# newer JDKs' version strings, e.g. 25.x, so a plain JDK 25 default breaks it).
set -e
cd "$(dirname "$0")"

export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"

adb devices -l
./gradlew installDebug
