#!/bin/sh
# run-tests.sh [--expect-fail] SIM ELF... : run each program under lockstep.
# With --expect-fail, each program must end with a test failure (exit 1),
# never with a lockstep mismatch, timeout or harness error.
xfail=0
if [ "$1" = "--expect-fail" ]; then xfail=1; shift; fi
SIM=$1; shift
pass=0; fail=0; failed=""
log=$(mktemp)
for elf in "$@"; do
  "$SIM" --quiet $SIM_ARGS "$elf" 2>"$log"; rc=$?
  if { [ $xfail -eq 0 ] && [ $rc -eq 0 ]; } || { [ $xfail -eq 1 ] && [ $rc -eq 1 ]; }; then
    pass=$((pass+1))
  else
    fail=$((fail+1)); failed="$failed $(basename "$elf")"
    tail -4 "$log"
  fi
done
rm -f "$log"
if [ $xfail -eq 1 ]; then echo "expected failures: $pass as expected, $fail unexpected${failed:+:$failed}"
else echo "passed $pass, failed $fail${failed:+:$failed}"; fi
[ "$fail" -eq 0 ]
