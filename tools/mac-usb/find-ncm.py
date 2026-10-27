#!/usr/bin/env python3
"""Print the first CDC NCM BSD interface under exactly one USB iPhone."""
import re
import subprocess

tree = subprocess.check_output(["ioreg", "-r", "-n", "iPhone", "-l", "-w", "0"], text=True)
if len(re.findall(r"\+-o iPhone@", tree)) != 1:
    raise SystemExit("Connect exactly one iPhone by USB")
inside = False
level = 0
for line in tree.splitlines():
    if "+-o " not in line:
        continue
    depth = line.index("+-o ")
    if inside and depth <= level:
        inside = False
    if re.search(r"\+-o NCM Control@\d+ ", line):
        inside = True
        level = depth
    elif inside:
        match = re.search(r"\+-o (en\d+)\s+<class IOEthernetInterface", line)
        if match:
            print(match.group(1))
            break
else:
    raise SystemExit("The iPhone has no active ordinary NCM network interface")
