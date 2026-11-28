#!/usr/bin/env python3
"""Check the independent API addon's runtime markers, on any CI host."""
from pathlib import Path
import sys


REGISTERED = b'BCCE_API_TEST REGISTERED v1'
PASSED = b'BCCE_API_TEST PASS v1'
FAILED = b'BCCE_API_TEST FAIL v1'


def api_test_addon_passed(log_files):
    """Require both exact v1 markers and no FAIL across this invocation's logs."""
    registered = passed = False
    try:
        for path in log_files:
            with Path(path).open('rb') as log:
                for line in log:
                    line = line.rstrip(b' \t\r\n\v\f')
                    if line.endswith(FAILED):
                        return False
                    registered |= line.endswith(REGISTERED)
                    passed |= line.endswith(PASSED)
    except OSError:
        # An unreadable log cannot establish that no failure marker was emitted.
        return False
    return registered and passed


if __name__ == '__main__':
    sys.exit(0 if api_test_addon_passed(sys.argv[1:]) else 1)
