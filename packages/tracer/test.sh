#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JUNIT=lib/junit-platform-console-standalone-1.11.3.jar
if [ ! -f "$JUNIT" ]; then
  mkdir -p lib
  curl -fsSL -o "$JUNIT" \
    https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.11.3/junit-platform-console-standalone-1.11.3.jar
fi
./build.sh
rm -rf build/test-classes
mkdir -p build/test-classes
javac --release 17 -d build/test-classes -cp "$JUNIT" \
  $(find src/main/java src/test/java -name '*.java')
TRACER_JAR=build/tracer.jar java -jar "$JUNIT" execute \
  -cp build/test-classes:src/test/resources --scan-class-path "$@"
