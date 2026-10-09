#!/usr/bin/env python3
"""Verify that every configured sweep target is a real static field in the pack.

Reads the target list straight out of MemFixConfig.DEFAULT_TARGETS, then asks
`javap` to list the fields of each owning class. A target passes when the field
exists and its declaration includes `static`.

javap is used rather than Class.forName deliberately:

  * Loading a target class runs its static initializer, which registers blocks
    and items and needs the full Minecraft bootstrap. Outside a server that
    throws, and would be misreported as a missing field.
  * javap reads the field table out of the class file without linking the class
    at all, so a class whose supertypes are absent still verifies.
  * Several targets (notably moreartifacts' item classes) fail to link outside a
    server for exactly that reason; javap is immune.

Run after a modpack or mod update to see which targets drifted:

    ./tools/verify-targets.py [/path/to/serverpack]
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, 'src/main/java/com/linggango/memfix/MemFixConfig.java')
JAVA_HOME = os.environ.get('JAVA_HOME', '/workspace/tools/jdk-17.0.20.1+1')
JAVAP = os.path.join(JAVA_HOME, 'bin', 'javap')

ENTRY = re.compile(r'"([A-Za-z0-9_$.]+#[A-Za-z0-9_$]+)"')
# `  private static final java.util.Map<...> name;`
FIELD = re.compile(r'^\s*(?:[\w$.<>\[\],\s]+?)\s+(\w+)\s*;')


def read_targets(path):
    with open(path, encoding='utf-8') as fh:
        body = fh.read()
    start = body.index('DEFAULT_TARGETS')
    end = body.index(');', start)
    return ENTRY.findall(body[start:end])


def main():
    pack = sys.argv[1] if len(sys.argv) > 1 else '/workspace/pack'
    classpath = os.pathsep.join(
        os.path.join(pack, sub) + '/*' for sub in ('mods', 'libraries')
    )

    targets = read_targets(CONFIG)
    if not targets:
        print('no targets parsed from', CONFIG)
        return 1

    cache = {}
    ok = bad = 0
    for target in targets:
        class_name, field_name = target.split('#')

        if class_name not in cache:
            result = subprocess.run(
                [JAVAP, '-p', '-cp', classpath, class_name],
                capture_output=True, text=True,
            )
            cache[class_name] = result.stdout

        output = cache[class_name]
        if not output.strip():
            print('BAD       %s  (class not found on the pack classpath)' % target)
            bad += 1
            continue

        match = None
        for line in output.splitlines():
            m = FIELD.match(line)
            if m and m.group(1) == field_name:
                match = (line.strip(), 'static' in line)
                break

        if match is None:
            print('BAD       %s  (class found, field absent)' % target)
            bad += 1
        elif not match[1]:
            print('NOT-STATIC %s' % target)
            bad += 1
        else:
            declaration = match[0].rstrip(';')
            print('OK        %s  (%s)' % (target, declaration))
            ok += 1

    print()
    print('static fields verified=%d bad=%d (of %d)' % (ok, bad, len(targets)))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
