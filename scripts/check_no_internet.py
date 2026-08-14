#!/usr/bin/env python3
"""Fail the build if a Kosha APK declares network permissions.

Spec B1 makes "no INTERNET permission" a structural guarantee, not a policy:
the app must be incapable of phoning home. That guarantee is fragile in a way
code review does not catch — manifest merger silently adds INTERNET from any
transitive dependency that declares it (ML Kit and Play Services both do), so
a routine dependency bump can quietly break it.

Reads the APK's binary AndroidManifest directly, which avoids depending on a
particular aapt2 version or AGP intermediate path.

Usage: check_no_internet.py <apk> [<apk> ...]
"""
import re
import sys
import zipfile

# INTERNET is the hard failure: it is the capability to open a socket.
FORBIDDEN = ("android.permission.INTERNET",)

PERMISSION_RE = re.compile(r"android\.permission\.[A-Z_]+")


def permissions(apk_path: str) -> set[str]:
    with zipfile.ZipFile(apk_path) as apk:
        manifest = apk.read("AndroidManifest.xml")
    # Strings in the binary manifest are UTF-16LE; decoding the whole blob and
    # pattern-matching is crude but stable across build-tools versions.
    text = manifest.decode("utf-16-le", errors="ignore")
    return set(PERMISSION_RE.findall(text))


def main(paths: list[str]) -> int:
    if not paths:
        print("usage: check_no_internet.py <apk> [<apk> ...]", file=sys.stderr)
        return 2

    failed = False
    for path in paths:
        found = permissions(path)
        violations = sorted(p for p in found if p in FORBIDDEN)
        name = path.rsplit("/", 1)[-1]
        if violations:
            failed = True
            print(f"FAIL {name}: declares {', '.join(violations)}")
            print("     Kosha must not be able to reach the network (spec B1).")
            print("     Add tools:node=\"remove\" for it in app/src/main/AndroidManifest.xml")
        else:
            print(f"ok   {name}: no network permission")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
