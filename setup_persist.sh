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

# Export paths
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$GRADLE_INSTALL_DIR/bin:$PATH"

echo "📦 Starting Android SDK + Gradle setup..."

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
sdkmanager --install \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "ndk;26.1.10909125" > /dev/null

ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/26.1.10909125"
export ANDROID_NDK_ROOT ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"

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
# CONDITIONAL GRADLE BUILD
# ----------------------------
cd "$PROJECT_DIR"

if [ -f "./gradlew" ]; then
  echo "🛠️  Found ./gradlew, ensuring it's executable..."
  chmod +x ./gradlew

  if [ -d "$PROJECT_DIR/app/build/outputs/apk/debug" ]; then
    echo "✅ Build output already exists. Skipping Gradle build."
  else
    echo "🚀 Running ./gradlew build..."
    ./gradlew build --no-daemon
  fi
else
  echo "⚠️  No ./gradlew found in project directory. Skipping build."
fi

# ----------------------------
# DONE
# ----------------------------
echo ""
echo "🎉 Setup and build complete!"
echo "📍 Android SDK: $ANDROID_SDK_ROOT"
echo "📍 Gradle: $(gradle --version | grep Gradle)"
