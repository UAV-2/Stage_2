#!/usr/bin/env bash
# Microbenchmarks JMH (módulo benchmarks, código en src/test/java).
#
#   scripts/run_jmh.sh              todos los benchmarks: 5 warmup + 10 medidas, 2 forks
#   scripts/run_jmh.sh threads      experimento de hilos (-t 1,2,4,8) para tokenizar y buscar
#   scripts/run_jmh.sh quick        pasada corta para comprobar que todo funciona
#   scripts/run_jmh.sh "<args JMH>" argumentos propios, p. ej. "SearchBenchmark -p books=500"
#
# Resultados CSV en benchmarks/results/. Ejecutar con la máquina enchufada y sin otros
# programas pesados abiertos.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p benchmarks/results
echo "== Compilando e instalando módulos"
mvn -q -DskipTests install

run() { # fichero args...
  local out=$1; shift
  echo "== JMH $* -> benchmarks/results/$out"
  mvn -q -pl benchmarks test-compile exec:exec -Djmh.args="$* -rf csv -rff results/$out"
}

case "${1:-all}" in
  all)
    run jmh_all.csv "-wi 5 -i 10 -f 2 -jvmArgsAppend -Xmx2g" ;;
  threads)
    for t in 1 2 4 8; do
      run "jmh_threads_t$t.csv" "TextProcessingBenchmark.tokenizeAndCount|SearchBenchmark -t $t -wi 5 -i 10 -f 2 -p words=50000 -p books=500 -p segments=1"
    done ;;
  quick)
    run jmh_quick.csv "-wi 1 -i 2 -f 1 -w 1s -r 1s" ;;
  *)
    run jmh_custom.csv "$1" ;;
esac
