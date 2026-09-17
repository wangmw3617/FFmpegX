"""静态检查：com.zhiwei.ffmpegx.* 的引用是否指向真实存在的符号。

Kotlin 编译器一次只报少量错误，来回试错成本较高。本脚本先扫一遍，
把「符号实际在 A 包、却从 B 包引用」这类系统性错误一次性列出。

用法：python importcheck.py <源码根目录>

实现要点：扫描前先把注释与字符串/字符字面量整体置空（长度不变，行号仍可用）。
否则 `const val ACTION = "com.zhiwei.ffmpegx.action.START"` 这类字符串常量
会被误判成类引用 —— 这是本脚本早期版本的真实误报来源。
"""
import collections
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "app/src"

DECL_PATTERNS = [
    re.compile(
        r'^\s*(?:@\w+(?:\([^)]*\))?\s+)*'
        r'(?:public |internal |private |abstract |open |sealed |data |enum |value |'
        r'annotation |inline |suspend )*'
        r'(?:class|interface|object|val|var|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)',
        re.M,
    ),
    re.compile(
        r'^\s*(?:@\w+(?:\([^)]*\))?\s+)*'
        r'(?:public |internal |private |inline |suspend |operator |infix )*'
        r'fun\s+(?:<[^>\n]*>\s*)?(?:[A-Za-z_][\w.<>?,\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)',
        re.M,
    ),
]

# AGP 生成，不存在于源码中
GENERATED = {"com.zhiwei.ffmpegx.R", "com.zhiwei.ffmpegx.BuildConfig"}

PACKAGE_RE = re.compile(r'^\s*package\s+([\w.]+)', re.M)
IMPORT_RE = re.compile(r'^\s*import\s+(com\.zhiwei\.ffmpegx\.[\w.]+)', re.M)
# 内联的全限定引用，例如 FFmpegNative.cancel() 写作 com.zhiwei.ffmpegx.native.FFmpegNative.cancel()
FQ_RE = re.compile(r'(?<![\w.])com\.zhiwei\.ffmpegx\.((?:[a-z][\w]*\.)+)([A-Z][\w]*)')


def blank_literals_and_comments(src):
    """把注释、字符串、字符字面量的内容替换为空格，保持长度不变。"""
    out = list(src)
    n = len(src)

    def blank(start, end):
        for k in range(start, min(end, n)):
            if out[k] != "\n":
                out[k] = " "

    i = 0
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            blank(i, j)
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            blank(i, j)
            i = j
        elif src[i] == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                j += 1
            blank(i, j)
            i = j
        elif src[i] == "'":
            j = i + 1
            if j < n and src[j] == "\\":
                j += 2
            else:
                j += 1
            if j < n and src[j] == "'":
                j += 1
            blank(i, j)
            i = j
        else:
            i += 1

    return "".join(out)


def collect_symbols(files):
    """package -> {symbol: file}"""
    symbols = collections.defaultdict(dict)
    for f in files:
        txt = open(f, encoding="utf-8").read()
        m = PACKAGE_RE.search(txt)
        if not m:
            continue
        pkg = m.group(1)
        clean = blank_literals_and_comments(txt)
        for pat in DECL_PATTERNS:
            for name in pat.findall(clean):
                symbols[pkg].setdefault(name, f)
    return symbols


def candidates(symbols, sym):
    return sorted(p for p, d in symbols.items() if sym in d)


def main():
    files = []
    for dirpath, _, names in os.walk(ROOT):
        for n in names:
            if n.endswith(".kt"):
                files.append(os.path.join(dirpath, n))

    symbols = collect_symbols(files)

    problems = []
    for f in files:
        clean = blank_literals_and_comments(open(f, encoding="utf-8").read())

        for ref in IMPORT_RE.findall(clean):
            if ref in GENERATED:
                continue
            pkg, _, sym = ref.rpartition('.')
            if sym == '*' or sym in symbols.get(pkg, {}):
                continue
            problems.append((f, ref, candidates(symbols, sym)))

        body = IMPORT_RE.sub('', clean)
        for pkgpart, sym in FQ_RE.findall(body):
            pkg = ("com.zhiwei.ffmpegx." + pkgpart).rstrip('.')
            ref = pkg + "." + sym
            if ref in GENERATED or sym in symbols.get(pkg, {}):
                continue
            problems.append((f, ref + "  [内联]", candidates(symbols, sym)))

    if problems:
        print("发现 %d 处无法解析的引用：\n" % len(problems))
        for f, ref, actual in sorted(problems):
            print("  " + f.replace(os.sep, "/"))
            print("      引用: " + ref)
            print("      实际: " + (", ".join(actual) if actual else "(不存在，可能拼写错误)"))
            print()
        sys.exit(1)

    total = sum(len(v) for v in symbols.values())
    print("OK - 扫描 %d 个文件、登记 %d 个符号，所有 com.zhiwei.ffmpegx.* 引用均可解析"
          % (len(files), total))


if __name__ == '__main__':
    main()
