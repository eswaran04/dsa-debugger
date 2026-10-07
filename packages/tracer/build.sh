#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
javac --release 17 -d build/classes $(find src/main/java -name '*.java')
jar --create --file build/tracer.jar --main-class dev.dsadebug.tracer.Main -C build/classes .
