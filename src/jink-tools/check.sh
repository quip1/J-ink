#!/usr/bin/env bash
# Compile-checks every module against the real Android 14 framework and runs the JVM unit tests,
# without needing the Android SDK. Useful where Google's download servers are unreachable.
# It does not build APKs or compile resources; use Gradle / Android Studio for that.
#
#   ./check.sh            check everything
#   ./check.sh dice chess check only these modules (plus common)
set -euo pipefail
cd "$(dirname "$0")"

CACHE="${JINK_CACHE:-$HOME/.cache/jink}"
MAVEN=https://repo.maven.apache.org/maven2
mkdir -p "$CACHE"

fetch() { # path-in-maven, local-name
  [ -s "$CACHE/$2" ] && return
  echo "Downloading $2 ..."
  for wait in 2 4 8 16 32; do
    curl -sSf -o "$CACHE/$2.part" "$MAVEN/$1" && mv "$CACHE/$2.part" "$CACHE/$2" && return
    sleep "$wait"
  done
  echo "Could not download $1" >&2; exit 1
}
fetch org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar android-all-14.jar
fetch junit/junit/4.13.2/junit-4.13.2.jar junit-4.13.2.jar
fetch org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar hamcrest-core-1.3.jar
# Real org.json for tests of code that parses JSON (Android has it built in; plain JVMs don't).
fetch org/json/json/20240303/json-20240303.jar json-20240303.jar

ANDROID="$CACHE/android-all-14.jar"
JUNIT="$CACHE/junit-4.13.2.jar:$CACHE/hamcrest-core-1.3.jar:$CACHE/json-20240303.jar"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

compile() { # out-dir classpath src-dir
  local files
  files=$(find "$3" -name '*.java')
  [ -z "$files" ] && return
  mkdir -p "$1"
  # shellcheck disable=SC2086
  javac -nowarn -Xlint:none --release 17 -encoding UTF-8 -cp "$2" -d "$1" $files
}

echo "== common"
compile "$OUT/common" "$ANDROID" common/src/main/java

if [ $# -gt 0 ]; then MODULES=$(printf "%s\n" "$@" | grep -vx common || true); else
  MODULES=$(sed -n "s/.*include \(.*\)/\1/p" settings.gradle | tr -d "':," | tr ' ' '\n' | grep -v '^common$' | grep .)
fi

failed=0
# Modules see every module compiled before them (settings.gradle order), e.g. scribe uses aiwhisper.
DEPS="$OUT/common"
for m in $MODULES; do
  echo "== $m"
  main="$OUT/$m/main"
  if ! compile "$main" "$ANDROID:$DEPS" "$m/src/main/java"; then failed=1; continue; fi
  DEPS="$DEPS:$main"
  if [ -d "$m/src/test/java" ]; then
    tests="$OUT/$m/test"
    if ! compile "$tests" "$main:$JUNIT" "$m/src/test/java"; then failed=1; continue; fi
    classes=$(cd "$tests" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g')
    # Tests only touch plain-Java logic classes, so they run on the JVM without Android.
    # shellcheck disable=SC2086
    java -cp "$tests:$main:$JUNIT" org.junit.runner.JUnitCore $classes | grep -E '^(OK|Tests run|FAIL|[0-9]+\))' || failed=1
  fi
done

[ $failed -eq 0 ] && echo "All modules OK" || { echo "Some modules FAILED" >&2; exit 1; }
