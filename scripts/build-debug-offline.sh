#!/bin/sh
set -eu
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ]; then echo 'Set ANDROID_HOME or ANDROID_SDK_ROOT' >&2; exit 1; fi
ndk=${ANDROID_NDK_HOME:-"$sdk/ndk/30.0.16248370"}
tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-36/android.jar"
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}
workspace=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/classes" "$scratch/dex" "$scratch/package/lib/arm64-v8a" "$workspace/build/manual"
"${java_bin}javac" -source 17 -target 17 -cp "$platform" -d "$scratch/classes" "$workspace"/app/src/main/java/org/emugo/frontend/*.java
"${java_bin}jar" cf "$scratch/classes.jar" -C "$scratch/classes" .
if [ -f "$workspace/.local-sdk/discord_partner_sdk.aar" ]; then
    unzip -p "$workspace/.local-sdk/discord_partner_sdk.aar" libs/discord_partner_sdk.jar > "$scratch/discord.jar"
    unzip -p "$workspace/.local-sdk/discord_partner_sdk.aar" libs/libwebrtc.jar > "$scratch/webrtc.jar"
    unzip -p "$workspace/.local-sdk/discord_partner_sdk.aar" jni/arm64-v8a/libdiscord_partner_sdk.so > "$scratch/package/lib/arm64-v8a/libdiscord_partner_sdk.so"
    "$tools/d8" --min-api 29 --lib "$platform" --output "$scratch/dex" "$scratch/classes.jar" "$scratch/discord.jar" "$scratch/webrtc.jar"
else
    "$tools/d8" --min-api 29 --lib "$platform" --output "$scratch/dex" "$scratch/classes.jar"
fi
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
cpp="$workspace/app/src/main/cpp"
rc="$cpp/rcheevos"
mkdir -p "$scratch/obj"
for source in "$rc"/src/*.c "$rc"/src/rapi/*.c "$rc"/src/rcheevos/*.c "$rc"/src/rhash/*.c; do
    "$bin/clang" --target=aarch64-linux-android29 -std=c99 -fPIC -O2 -DRC_DISABLE_LUA -DRC_CLIENT_SUPPORTS_HASH -I"$rc/include" -I"$rc/src" -I"$cpp" -c "$source" -o "$scratch/obj/$(basename "$(dirname "$source")")_$(basename "$source" .c).o"
done
"$bin/clang++" --target=aarch64-linux-android29 -std=c++17 -DRC_CLIENT_SUPPORTS_HASH -shared -fPIC -O2 -I"${JAVA_HOME:?Set JAVA_HOME to a Java 17 JDK}/include" -I"$JAVA_HOME/include/linux" -I"$rc/include" -I"$rc/src" -I"$cpp" "$cpp/core.cpp" "$cpp/ra.cpp" "$cpp/vfs.cpp" "$cpp/discord.cpp" "$scratch"/obj/*.o -o "$scratch/package/lib/arm64-v8a/libemugo.so" -ldl -llog -landroid
stl="$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
test -f "$stl" || { echo "Missing Android C++ runtime: $stl" >&2; exit 1; }
cp "$stl" "$scratch/package/lib/arm64-v8a/libc++_shared.so"
"$tools/aapt2" compile --dir "$workspace/app/src/main/res" -o "$scratch/resources.zip"
"$tools/aapt2" link -o "$scratch/base.apk" --manifest "$workspace/app/src/main/AndroidManifest.xml" -I "$platform" --min-sdk-version 29 --target-sdk-version 35 --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-0.1.0-debug}" -A "$workspace/app/src/main/assets" "$scratch/resources.zip"
cp "$scratch/dex/classes.dex" "$scratch/package/classes.dex"
cp "$scratch/base.apk" "$scratch/unsigned.apk"
(cd "$scratch/package" && zip -q -r "$scratch/unsigned.apk" classes.dex lib)
"$tools/zipalign" -f 4 "$scratch/unsigned.apk" "$scratch/aligned.apk"
output=${OUTPUT_APK:-"$workspace/EmuGo-debug.apk"}
if [ -n "${SIGNING_KEYSTORE:-}" ]; then
    # Release signing: credentials come from the environment, never from files in the repo.
    "$tools/apksigner" sign --ks "$SIGNING_KEYSTORE" --ks-key-alias "${SIGNING_KEY_ALIAS:?Set SIGNING_KEY_ALIAS}" --ks-pass env:SIGNING_STORE_PASSWORD --key-pass env:SIGNING_KEY_PASSWORD --out "$output" "$scratch/aligned.apk"
else
    key="$workspace/build/manual/debug.jks"
    if [ ! -f "$key" ]; then "${java_bin}keytool" -genkeypair -keystore "$key" -storepass android -keypass android -alias androiddebugkey -dname 'CN=EmuGo Debug,O=EmuGo,C=DE' -keyalg RSA -keysize 2048 -validity 3650 -noprompt >/dev/null 2>&1; fi
    "$tools/apksigner" sign --ks "$key" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$output" "$scratch/aligned.apk"
fi
"$tools/apksigner" verify "$output"
echo "$output"
