"""静态检查：所有 com.zhiwei.ffmpegx.* 的 import 是否真的指向存在的符号。

Kotlin 编译器一次只报若干条错误，来回试错太慢。这个脚本先扫一遍，
把「符号在 A 包、却从 B 包导入」这类系统性错误一次性揪出来。
"""
import os
import re
import sys
import collections

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."

# 声明：class/interface/object/val/var/typealias 与 fun（含泛型与扩展函数）
PATTERNS = [
    re.compile(r'^\s*(?:@\w+(?:\([^)]*\))?\s+)*'
               r'(?:public |internal |private |abstract |open |sealed |data |enum |value |annotation |inline |suspend )*'
               r'(?:class|interface|object|val|var|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)', re.M),
    re.compile(r'^\s*(?:@\w+(?:\([^)]*\))?\s+)*'
               r'(?:public |internal |private |inline |suspend |operator |infix )*'
               r'fun\s+(?:<[^>\n]*>\s*)?(?:[A-Za-z_][\w.<>?,\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)', re.M),
]

# AGP 生成的类，不在源码里，属于正常导入
GENERATED = {"com.zhiwei.ffmpegx.R", "com.zhiwei.ffmpegx.BuildConfig"}

symbols = collections.defaultdict(dict)   # package -> {symbol: file}
files = []
for dirpath, _, names in os.walk(ROOT):
    for n in names:
        if n.endswith(".kt"):
            files.append(os.path.join(dirpath, n))

for f in files:
    txt = open(f, encoding="utf-8").read()
    m = re.search(r'^\s*package\s+([\w.]+)', txt, re.M)
    if not m:
        continue
    pkg = m.group(1)
    for pat in PATTERNS:
        for name in pat.findall(txt):
            symbols[pkg].setdefault(name, f)

imp_re = re.compile(r'^\s*import\s+(com\.zhiwei\.ffmpegx\.[\w.]+)', re.M)

# 内联的全限定引用，例如 foo(com.zhiwei.ffmpegx.core.model.Bar)
fq_re = re.compile(r'(?<![\w.])com\.zhiwei\.ffmpegx\.((?:[a-z][\w]*\.)+)([A-Z][\w]*)')

bad = []
for f in files:
    txt = open(f, encoding="utf-8").read()
    for imp in imp_re.findall(txt):
        if imp in GENERATED:
            continue
        pkg, _, sym = imp.rpartition('.')
        if sym == '*':
            continue
        if sym not in symbols.get(pkg, {}):
            actual = sorted(p for p, d in symbols.items() if sym in d)
            bad.append((f.replace('\\', '/'), imp, actual))

    # 内联全限定引用（跳过 import 行本身）
    body = imp_re.sub('', txt)
    for pkgpart, sym in fq_re.findall(body):
        pkg = ("com.zhiwei.ffmpegx." + pkgpart).rstrip('.')
        if pkg + "." + sym in GENERATED:
            continue
        if sym not in symbols.get(pkg, {}):
            actual = sorted(p for p, d in symbols.items() if sym in d)
            bad.append((f.replace('\\', '/'), pkg + "." + sym + "  [内联]", actual))

if bad:
    print("发现 %d 处错误导入：\n" % len(bad))
    for f, imp, actual in sorted(bad):
        print("  " + f)
        print("      写了: " + imp)
        print("      实际: " + (", ".join(actual) if actual else "(不存在，可能拼写错误)"))
        print()
    sys.exit(1)

print("OK - 所有 com.zhiwei.ffmpegx.* 导入均能解析（共扫描 %d 个文件，登记 %d 个符号）"
      % (len(files), sum(len(v) for v in symbols.values())))
