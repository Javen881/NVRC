#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" ]]; then
    echo "Set JAVA_HOME to a JDK 21 installation before running protocol tests." >&2
    exit 1
fi
mkdir -p build/protocol-tests
"$JAVA_HOME/bin/javac" --add-modules jdk.httpserver -d build/protocol-tests app/src/main/java/com/example/nvrclient/protocol/*.java tests/ProtocolTest.java
"$JAVA_HOME/bin/java" --add-modules jdk.httpserver -cp build/protocol-tests com.example.nvrclient.protocol.ProtocolTest
