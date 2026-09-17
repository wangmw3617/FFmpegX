# Kotlin 词法级括号/字符串检查器（状态机实现，见下方 scan 函数）
import sys


def scan(path):
    src = open(path, encoding="utf-8").read()
    i, n = 0, len(src)
    line = 1
    depth_brace = depth_paren = 0
    problems = []
    str_open_line = None  # 普通字符串开始的行，用于检测跨行

    while i < n:
        c = src[i]

        if c == "\n":
            line += 1
            # 普通字符串里出现裸换行 = Kotlin 语法错误
            if str_open_line is not None:
                problems.append(
                    f"第 {str_open_line} 行的普通字符串在第 {line} 行仍在继续（跨行，非法）"
                )
                str_open_line = None
            i += 1
            continue

        # 行注释
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue

        # 块注释
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            if j < 0:
                problems.append(f"第 {line} 行：块注释未闭合")
                break
            line += src.count("\n", i, j)
            i = j + 2
            continue

        # 原始字符串
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            if j < 0:
                problems.append(f"第 {line} 行：原始字符串未闭合")
                break
            line += src.count("\n", i, j)
            i = j + 3
            continue

        # 字符字面量
        if c == "'":
            j = i + 1
            if j < n and src[j] == "\\":
                j += 2
            else:
                j += 1
            j += 1  # 收尾的 '
            i = j
            continue

        # 普通字符串
        if c == '"':
            j = i + 1
            closed = False
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    closed = True
                    break
                if src[j] == "\n":
                    break  # 裸换行 -> 非法的跨行字符串
                if src[j] == "$" and j + 1 < n and src[j + 1] == "{":
                    # 插值块：跳过配对的 { }
                    k, d = j + 2, 1
                    while k < n and d:
                        if src[k] == "{":
                            d += 1
                        elif src[k] == "}":
                            d -= 1
                        elif src[k] == "\n":
                            line += 1
                        k += 1
                    j = k
                    continue
                j += 1
            if closed:
                i = j + 1
            else:
                problems.append(f"第 {line} 行：普通字符串未在同一行闭合")
                str_open_line = line
                i = j
            continue

        if c == "{":
            depth_brace += 1
        elif c == "}":
            depth_brace -= 1
            if depth_brace < 0:
                problems.append(f"第 {line} 行：多余的 }}")
                depth_brace = 0
        elif c == "(":
            depth_paren += 1
        elif c == ")":
            depth_paren -= 1
            if depth_paren < 0:
                problems.append(f"第 {line} 行：多余的 )")
                depth_paren = 0

        i += 1

    return depth_brace, depth_paren, problems, line


if __name__ == "__main__":
    for path in sys.argv[1:]:
        db, dp, probs, last = scan(path)
        name = path.split("/")[-1]
        ok = db == 0 and dp == 0 and not probs
        print(f"{name:<26} 行数={last:<5} {{}}余={db:<3} ()余={dp:<3} {'OK' if ok else 'FAIL'}")
        for p in probs:
            print(f"    !! {p}")
