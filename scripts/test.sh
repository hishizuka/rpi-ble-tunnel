#!/bin/sh
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
python3 -m unittest discover -s "$task_root/tests" -p 'test_*.py'
cmake -S "$task_root" -B "$task_root/build/local" -DCMAKE_BUILD_TYPE=Debug
cmake --build "$task_root/build/local"
ctest --test-dir "$task_root/build/local" --output-on-failure
if [ "$(uname -s)" = Darwin ]; then
    swift run --package-path "$task_root/macos" pilink-core-tests
    swift build --package-path "$task_root/macos" --product pilink-mux-test
    python3 "$task_root/tests/integration/verify-multiplex.py"
    python3 "$task_root/tests/integration/verify-internet.py"
fi
