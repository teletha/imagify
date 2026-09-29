#!/usr/bin/env python3
"""Fails when the version a workflow expects is not the one the build will report.

Each bundled codec reports the upstream version it was built from: WebpCodec.getVersion() for
libwebp, JpegliCodec.getVersion() for jpegli. Both strings used to be handed to cmake as
-DNAME=1.6.0 on a workflow command line, and a workflow step with no shell: key runs under pwsh on
a Windows runner. PowerShell passes a native command -DNAME=1.6.0 as two arguments, -DNAME=1 and
.6.0, so cmake cached the 1, printed "Ignoring extra path from command line: .6.0" and carried on.
The libwebp Windows libraries have reported their version as "1" ever since, and the jpegli ones
have reported a commit hash, because 0 is falsy and the fallback that answers a commit when no
version is given took over. Both versions are written in their CMakeLists now, where no shell can
reach them, and this is what stops the copy in the workflow and the copy the build reads from
drifting apart when the pin is bumped.

Usage: check-codec-version.py <webp|jpegli> <expected version>
Exit code 0 when all three agree, 1 when they do not, 2 on a usage or read error.
"""

import os
import re
import sys

# Where each codec's build reads its version from, and the workflow that expects it. The pattern
# matches the set() that names the version, so that reformatting a comment above it cannot change
# what this script sees.
CODECS = {
    "webp": {
        "cmake": os.path.join("src", "main", "native", "webp", "CMakeLists.txt"),
        "workflow": os.path.join(".github", "workflows", "webp-natives.yml"),
        "variable": "IMAGIFY_WEBP_VERSION",
        "expected_in_workflow": "WEBP_VERSION",
        "reporter": "WebpCodec.getVersion()",
    },
    "jpegli": {
        "cmake": os.path.join("src", "main", "native", "jpegli", "CMakeLists.txt"),
        "workflow": os.path.join(".github", "workflows", "jpegli-natives.yml"),
        "variable": "IMAGIFY_JPEGLI_VERSION",
        "expected_in_workflow": "JPEGLI_VERSION",
        "reporter": "JpegliCodec.getVersion()",
    },
}


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
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    codec = CODECS.get(argv[1])
    if codec is None:
        return fail("unknown codec " + argv[1] + ", expected one of " + ", ".join(sorted(CODECS)))
    expected = argv[2]

    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    cmake = read(os.path.join(root, codec["cmake"]))
    if cmake is None:
        return 2
    workflow = read(os.path.join(root, codec["workflow"]))
    if workflow is None:
        return 2

    # set(NAME "value" CACHE ...) for a default written in the file, or set(NAME "value" CACHE
    # STRING ...) which is the same line with a docstring after the keyword. jpegli also has a
    # set(NAME "unknown") and a set(NAME ${...}) further down for the fallbacks, and neither
    # carries CACHE, so requiring the keyword is what tells the default apart from them.
    in_build = re.search(
        r'set\s*\(\s*' + re.escape(codec["variable"]) + r'\s+"([^"]*)"\s+CACHE\b',
        cmake,
    )
    if in_build is None:
        return fail("no set(" + codec["variable"] + ' "..." CACHE ...) in ' + codec["cmake"]
                    + ", so the build cannot report which upstream version it came from and this "
                      "check cannot tell whether that is meant")

    in_workflow = re.search(
        r'^\s*' + re.escape(codec["expected_in_workflow"]) + r':\s*(\S+)\s*$',
        workflow,
        re.MULTILINE,
    )
    if in_workflow is None:
        return fail("no " + codec["expected_in_workflow"] + ": in " + codec["workflow"])

    version = in_build.group(1)
    print(f"the build will report        {version}")
    print(f"the workflow says it should  {in_workflow.group(1)}")
    print(f"the check was given          {expected}")

    for name, value in ((codec["expected_in_workflow"] + " in the workflow", in_workflow.group(1)),
                        ("the version given on the command line", expected)):
        if value != version:
            print(f"::error::the version written in {codec['cmake']} is {version}, but {name} is "
                  f"{value}. A library built now would report itself as an upstream it was not "
                  f"built from, and {codec['reporter']} would say so in a bug report.", file=sys.stderr)
            return 1

    print(f"the version the build will report is the version the workflow expects")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
