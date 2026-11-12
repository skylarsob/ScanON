#!/bin/bash
# Build helper for an aarch64 Linux host. Google's aapt2 is x86-only, so a native arm64 aapt2 is passed
# through -Pandroid.aapt2FromMavenOverride. Usage: ./build.sh <gradle tasks...>
export JAVA_HOME=${JAVA_HOME:-/workspace/skyng/tools/jdk-17.0.21+9}
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=${ANDROID_HOME:-/workspace/skyng/tools/sdk}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-/workspace/skyng/tools/gradle-home}
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy
cd "$(dirname "$0")"
AAPT2=${AAPT2:-/workspace/skyng/tools/aapt2wrap/aapt2}
EXTRA=()
[ -x "$AAPT2" ] && EXTRA+=("-Pandroid.aapt2FromMavenOverride=$AAPT2")
./gradlew --no-daemon "${EXTRA[@]}" "$@"
