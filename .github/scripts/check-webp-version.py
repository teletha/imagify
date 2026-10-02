#!/usr/bin/env python3
"""Fails when the version in the workflow is not the one the build will report.

WebpCodec.getVersion() answers with the libwebp release version that the bundled library was built
from, and that string is written into native/webp/CMakeLists.txt rather than passed to
cmake on a command line. It used to be passed on a command line, and every Windows library this
project built reported its version as "1": a workflow step with no shell: key runs under pwsh on a
Windows runner, PowerShell hands a native command -DIMAGIFY_WEBP_VERSION=1.6.0 as two arguments,
and cmake kept the 1. Keeping the value in the CMakeLists is what removes the shell from the
question, and this script is what stops it going stale: WEBP_TAG and WEBP_VERSION in the workflow are
the two places a bump has to be written down, and they are checked against each other here so that
bumping one and forgetting the other is a failed build rather than a library that says it is a
libwebp it was not built from.

Usage: check-webp-version.py <expected version>
Exit code 0 when the two agree, 1 when they do not, 2 when the file cannot be read or parsed.
"""

import os
import re
import sys

# The set() that names the version, matched rather than read line by line so that a reformatted
# comment above it cannot change what this script sees. The string it must find is the first
# argument of the set() whose name is IMAGIFY_WEBP_VERSION and whose value is a quoted string.
VERSION_PATTERN = re.compile(
    r'set\s*\(\s*IMAGIFY_WEBP_VERSION\s+"([^"]*)"\s+CACHE\b',
    re.MULTILINE,
)

# The workflow's own copy, read the same way: the line "  WEBP_VERSION: 1.6.0" under env:.
WORKFLOW_PATTERN = re.compile(r'^\s*WEBP_VERSION:\s*(\S+)\s*$', re.MULTILINE)


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    return 2


def read(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError as e:
        return fail(f"cannot read {path}: {e}")


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2

    expected = argv[1]
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    cmake = read(os.path.join(root, "native", "webp", "CMakeLists.txt"))
    if cmake is None:
        return 2
    workflow = read(os.path.join(root, ".github", "workflows", "webp-natives.yml"))
    if workflow is None:
        return 2

    found = VERSION_PATTERN.search(cmake)
    if found is None:
        return fail("no set(IMAGIFY_WEBP_VERSION \"...\" CACHE ...) in "
                    "native/webp/CMakeLists.txt, so the build will report \"unknown\" "
                    "and this check cannot tell whether that is meant")

    in_build = found.group(1)
    in_workflow = WORKFLOW_PATTERN.search(workflow)
    if in_workflow is None:
        return fail("no WEBP_VERSION: in .github/workflows/webp-natives.yml")

    print(f"the build will report        {in_build}")
    print(f"the workflow says it should  {in_workflow.group(1)}")
    print(f"the check was given          {expected}")

    for name, value in (("WEBP_VERSION in the workflow", in_workflow.group(1)),
                        ("the version given on the command line", expected)):
        if value != in_build:
            print(f"::error::the version written in native/webp/CMakeLists.txt is "
                  f"{in_build}, but {name} is {value}. A library built now would report itself as a "
                  f"libwebp it was not built from.", file=sys.stderr)
            return 1

    print("the version the build will report is the version the workflow expects")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
