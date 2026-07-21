#!/usr/bin/env sh
# Intentionally tracked with the POSIX executable bit (git mode 100755).
#
# Its sole purpose is to give the worker's clone -> workspace -> publish round-trip a file that
# carries the executable bit, so the empty-diff sad-path test (HermeticSadPaths, "empty-diff")
# fails loudly if that round-trip ever strips modes again. That was the bit-flip regression: the
# mode-agnostic workspace abstraction re-materialized every file 0644, so a genuine no-op run
# produced a spurious 100755->100644 diff and opened a junk PR instead of reporting
# "Engine produced no changes". Nothing runs this script; it only needs to exist and be executable.
exit 0
