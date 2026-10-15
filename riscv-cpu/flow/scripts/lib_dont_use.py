#!/usr/bin/env python3
"""Copy a Liberty file without the cells ORFS marks dont-use for sky130hd
(*lpflow* power-domain cells and *probe* cells), so Yosys/ABC cannot map to
them. Usage: lib_dont_use.py in.lib out.lib"""
import re, sys
src = open(sys.argv[1]).read()
out, i, dropped = [], 0, 0
for m in re.finditer(r'\n\s*cell\s*\(\s*"?(\w+)"?\s*\)\s*\{', src):
    if m.start() < i:
        continue
    name = m.group(1)
    if "lpflow" in name or "probe" in name:
        depth, j = 0, m.end() - 1
        while True:
            c = src[j]
            depth += c == "{"
            depth -= c == "}"
            j += 1
            if depth == 0:
                break
        out.append(src[i:m.start()])
        i = j
        dropped += 1
out.append(src[i:])
open(sys.argv[2], "w").write("".join(out))
print(f"dropped {dropped} dont-use cells")
