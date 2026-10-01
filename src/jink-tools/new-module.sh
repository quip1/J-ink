#!/usr/bin/env bash
# Scaffolds the Gradle file for a new app module: ./new-module.sh <module> <versionName>
set -euo pipefail
cd "$(dirname "$0")"
m=$1; v=${2:-1.0}
mkdir -p "$m/src/main/java/dev/jacob/$m" "$m/src/main/res/drawable"
cat > "$m/build.gradle" <<GRADLE
plugins { id 'com.android.application' }
apply from: rootProject.file('app.gradle')
android {
  namespace 'dev.jacob.$m'
  defaultConfig {
    applicationId 'dev.jacob.$m'
    versionCode 1
    versionName '$v'
  }
}
GRADLE
