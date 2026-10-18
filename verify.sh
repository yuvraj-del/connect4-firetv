#!/usr/bin/env bash
# Compiles the engine + tests and runs the quality gates.
# Exit 0 = every test passed; otherwise it prints which tests failed and why.
# Usage: ./verify.sh [--fast]   (--fast skips the slow strength tests)
set -uo pipefail
cd "$(dirname "$0")"

rm -rf build && mkdir -p build
SRC=$(find engine/src/main/java tests/src -name '*.java')

if ! javac --release 17 -Xlint:none -d build $SRC > build/compile.log 2>&1; then
  echo "COMPILE FAILED"
  head -60 build/compile.log
  echo
  echo "SUMMARY: compile error"
  exit 2
fi

java -Xss8m -cp build com.yuvraj.connect4.tests.TestRunner "$@"
