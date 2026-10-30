#!/usr/bin/env bash
# Compila e testa o módulo :core SEM Gradle/rede, usando o compilador Kotlin e o JUnit
# que acompanham a distribuição do Gradle. Uso: tools/core-test.sh [GRADLE_HOME]
set -euo pipefail
GH="${1:-${GRADLE_HOME:-/opt/gradle-8.14.3}}"
L="$GH/lib"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/hqverse-core-build"
rm -rf "$OUT" && mkdir -p "$OUT/main" "$OUT/test"
KCP="$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
STD="$L/kotlin-stdlib-2.0.21.jar"
JU="$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar"
kc() { java -Xmx1g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -nowarn "$@"; }
echo ">> compilando main"
kc -cp "$STD" -d "$OUT/main" "$ROOT/core/src/main/kotlin"
echo ">> compilando testes"
kc -cp "$STD:$JU:$OUT/main" -d "$OUT/test" "$ROOT/core/src/test/kotlin"
echo ">> executando testes"
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort | tr '\n' ' ')
java -cp "$STD:$JU:$OUT/main:$OUT/test" org.junit.runner.JUnitCore $CLASSES
