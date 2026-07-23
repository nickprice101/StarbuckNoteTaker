#!/bin/bash

set -e

# ----------------------------
# CONFIGURATION
# ----------------------------
ANDROID_SDK_ROOT="$HOME/.android-sdk"
CMDLINE_TOOLS_VERSION="11076708"
GRADLE_VERSION="8.5"
GRADLE_INSTALL_DIR="$HOME/.gradle/gradle-$GRADLE_VERSION"
GRADLE_DOWNLOAD_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
PROJECT_DIR="$(pwd)"  # assumes you're in the project root

# Detect build configuration so we install matching SDK components
APP_BUILD_GRADLE="$PROJECT_DIR/app/build.gradle"

if [ ! -f "$APP_BUILD_GRADLE" ]; then
  echo "❌ Unable to locate app/build.gradle at $APP_BUILD_GRADLE"
  exit 1
fi

COMPILE_SDK=$(grep -E "compileSdk[[:space:]]+[0-9]+" "$APP_BUILD_GRADLE" | head -n1 | sed -E 's/.*compileSdk[[:space:]]+([0-9]+).*/\1/')
if [ -z "$COMPILE_SDK" ]; then
  echo "❌ Unable to detect compileSdk value from $APP_BUILD_GRADLE"
  exit 1
fi

# Try to read the declared NDK version (if any)
NDK_VERSION=$(awk -F"['\"]" '/ndkVersion/ {for (i=2; i<=NF; ++i) {if ($i ~ /^[0-9.]+$/) {print $i; exit}}}' "$APP_BUILD_GRADLE")

# Match the build-tools version with the compile SDK when not explicitly configured
BUILD_TOOLS_VERSION="${COMPILE_SDK}.0.0"

# Export paths
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$GRADLE_INSTALL_DIR/bin:$PATH"

echo "📦 Starting Android SDK + Gradle setup..."

echo "ℹ️  Detected compileSdk: ${COMPILE_SDK}"
if [ -n "$NDK_VERSION" ]; then
  echo "ℹ️  Detected ndkVersion: ${NDK_VERSION}"
else
  echo "ℹ️  No explicit ndkVersion found in build.gradle"
fi

# ----------------------------
# ANDROID SDK INSTALLATION
# ----------------------------
if [ ! -d "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
  echo "🔧 Installing Android SDK..."

  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  cd "$ANDROID_SDK_ROOT/cmdline-tools"

  curl -sSLo tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  unzip -q tools.zip
  rm tools.zip
  mv cmdline-tools latest
else
  echo "✅ Android SDK already installed at $ANDROID_SDK_ROOT"
fi

echo "🔐 Accepting Android SDK licenses silently..."
yes | sdkmanager --licenses > /dev/null

echo "📦 Installing required SDK packages..."
REQUIRED_PACKAGES=(
  "platform-tools"
  "platforms;android-${COMPILE_SDK}"
  "build-tools;${BUILD_TOOLS_VERSION}"
)

if [ -n "$NDK_VERSION" ]; then
  REQUIRED_PACKAGES+=("ndk;${NDK_VERSION}")
fi

printf '   - %s\n' "${REQUIRED_PACKAGES[@]}"

sdkmanager --install "${REQUIRED_PACKAGES[@]}" > /dev/null

echo "📄 Writing local.properties with SDK path..."
cat <<EOF > "$PROJECT_DIR/local.properties"
sdk.dir=$ANDROID_SDK_ROOT
EOF

# ----------------------------
# GRADLE INSTALLATION
# ----------------------------
if [ ! -d "$GRADLE_INSTALL_DIR" ]; then
  echo "🔧 Installing Gradle $GRADLE_VERSION..."

  mkdir -p "$HOME/.gradle"
  cd "$HOME/.gradle"

  curl -sSLo gradle.zip "$GRADLE_DOWNLOAD_URL"
  unzip -q gradle.zip
  rm gradle.zip
else
  echo "✅ Gradle $GRADLE_VERSION already installed at $GRADLE_INSTALL_DIR"
fi

# ----------------------------
# NDK: COPY libc++_shared.so FROM EXISTING INSTALL
# ----------------------------
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs"
SUPPORTED_ABIS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")

echo "🔎 Searching for existing NDK installation with libc++_shared.so..."

NDK_LOCATIONS=(
  "$ANDROID_SDK_ROOT/ndk"
  "$ANDROID_NDK_ROOT"
  "/usr/local/android-ndk"
  "/opt/android-ndk"
  "$HOME/Library/Android/sdk/ndk"
)

LIBCXX_FOUND=""

for ndk_base in "${NDK_LOCATIONS[@]}"; do
  if [ -d "$ndk_base" ]; then
    # Find latest-version folder
    latest_ndk=$(ls -1 "$ndk_base" | sort -V | tail -n 1)
    ndk_path="$ndk_base/$latest_ndk"
    if [ -d "$ndk_path" ]; then
      LIBCXX_FOUND="$ndk_path"
      break
    fi
  fi
done

if [ -n "$LIBCXX_FOUND" ]; then
  echo "✅ Found NDK at $LIBCXX_FOUND. Copying libc++_shared.so for each ABI..."
  for abi in "${SUPPORTED_ABIS[@]}"; do
    mkdir -p "$JNILIBS_DIR/$abi"
    LIBCXX_SRC="$LIBCXX_FOUND/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$abi/libc++_shared.so"
    if [ ! -f "$LIBCXX_SRC" ]; then
      # Fallback: Try another common location
      LIBCXX_SRC="$LIBCXX_FOUND/sources/cxx-stl/llvm-libc++/libs/$abi/libc++_shared.so"
    fi
    if [ -f "$LIBCXX_SRC" ]; then
      cp "$LIBCXX_SRC" "$JNILIBS_DIR/$abi/"
      echo "  - Copied for ABI: $abi"
    else
      echo "  ⚠️  WARNING: libc++_shared.so not found for ABI $abi in $LIBCXX_FOUND"
    fi
  done
else
  echo "⚠️  No existing NDK installation found. Please ensure libc++_shared.so is present for all ABIs in $JNILIBS_DIR."
fi

# ----------------------------
# CONDITIONAL GRADLE BUILD
# ----------------------------
cd "$PROJECT_DIR"

if [ -f "./gradlew" ]; then
  echo "🛠️  Found ./gradlew, ensuring it's executable..."
  chmod +x ./gradlew

  echo ""
  echo "ℹ️  Gradle build will NOT run automatically as part of setup."
  echo "    To run the build manually, use:"
  echo "      ./gradlew assembleDebug"
  echo "    Or to opt-in during setup, run:"
  echo "      RUN_GRADLE_BUILD=1 ./setup_persist.sh"
  echo ""

  if [ "${RUN_GRADLE_BUILD:-0}" = "1" ]; then
    if [ -d "$PROJECT_DIR/app/build/outputs/apk/debug" ]; then
      echo "✅ Build output already exists. Skipping Gradle build."
    else
      echo "🚀 RUN_GRADLE_BUILD=1 detected: Running ./gradlew assembleDebug..."
      ./gradlew assembleDebug --no-daemon
    fi
  fi
else
  echo "⚠️  No ./gradlew found in project directory. Skipping build."
fi

# ----------------------------
# DONE
# ----------------------------
echo ""
echo "🎉 Setup complete!"
echo "📍 Android SDK: $ANDROID_SDK_ROOT"
echo "📍 Gradle: $(gradle --version | grep Gradle)"
