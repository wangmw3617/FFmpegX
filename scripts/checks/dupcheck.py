"""Kotlin 成员级重复声明检测器。

只检查 `class` / `object` / `interface` 体**直接层级**里的 val/var/fun 声明。
局部变量、函数参数、lambda 参数、构造参数都会被排除，避免误报。

用法：python dupcheck.py A.kt B.kt

要点（都是踩过的坑）：
* 扩展函数 `fun Modifier.foo()` 的成员名是 `foo`，不是接收者类型 `Modifier`。
  早期版本按第一个标识符取名，于是同一文件里两个不同接收者的扩展函数
  会被误报成「重复声明 Modifier」。
* 每个类体单独记账。不同 `data class` 之间有同名字段是完全正常的，
  共用一个 seen 字典会把它们全报成重复。
* **重载是合法的**（`fun raw(vararg v: String)` 与 `fun raw(v: List<String>)`），
  所以 fun 要带上归一化后的参数表一起做键，否则会把重载误报成重复。
  真正要抓的是「签名完全一样的两份」—— Edit 工具与脚本各插一段就是这个形态。
"""
import re
import sys
import os

ANNOTATIONS = r'(?:@\w+(?:\([^)]*\))?\s+)*'
MODIFIERS = (
    r'(?:private\s+|internal\s+|public\s+|protected\s+|override\s+|open\s+|'
    r'abstract\s+|const\s+|lateinit\s+|suspend\s+|inline\s+|operator\s+|'
    r'infix\s+|tailrec\s+|external\s+|expect\s+|actual\s+|final\s+)*'
)

VAL_RE = re.compile(r'^' + ANNOTATIONS + MODIFIERS + r'(?:val|var)\s+([A-Za-z_]\w*)')
# fun 可带类型参数与接收者类型。两处都要进键：
#   名字是最后一个 '.' 之后的标识符；
#   接收者类型也必须算进去 —— 同一个名字挂在不同接收者上是合法重载。
FUN_RE = re.compile(
    r'^' + ANNOTATIONS + MODIFIERS + r'fun\s+'
    r'(?:<[^<>]*>\s*)?'
    r'(?:([A-Za-z_][\w.]*(?:<[^<>]*>)?\??)\.)?'
    r'([A-Za-z_]\w*)'
)
CLASS_RE = re.compile(
    r'^' + ANNOTATIONS + MODIFIERS +
    r'(?:data\s+|sealed\s+|value\s+|annotation\s+|inner\s+|enum\s+|fun\s+)*'
    r'(?:class|object|interface)(?:\s+([A-Za-z_]\w*))?'
)


def params_sig(text, from_pos):
    """取函数参数表并归一化空白；取不到返回 None。

    从 `from_pos`（成员名之后）开始找第一个 '('，并做括号配对，
    这样多行参数表也能正确截取。
    """
    i = text.find('(', from_pos)
    if i < 0:
        return None
    # '(' 必须在 '{' 或 '=' 之前，否则那属于函数体/默认值而不是参数表
    for marker in ('{', '='):
        j = text.find(marker, from_pos)
        if 0 <= j < i:
            return None
    depth = 0
    for k in range(i, len(text)):
        c = text[k]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return re.sub(r'\s+', ' ', text[i + 1:k]).strip()
    return None


def detect(text):
    """返回 [(key, first_line, second_line), ...]"""
    lines = text.split('\n')
    # 每行起始的绝对偏移，用于跨行取参数表
    offsets = []
    pos = 0
    for ln in lines:
        offsets.append(pos)
        pos += len(ln) + 1

    depth = 0
    frames = []          # [{'body_depth': int, 'seen': {key: line}}]
    pending = False
    dups = []

    for idx, raw in enumerate(lines, 1):
        stripped = raw.strip()

        if pending and '{' in raw:
            frames.append({'body_depth': depth + 1, 'seen': {}})
            pending = False

        if CLASS_RE.match(stripped) and not stripped.startswith('//'):
            if '{' in raw:
                frames.append({'body_depth': depth + 1, 'seen': {}})
            else:
                pending = True

        if frames and depth == frames[-1]['body_depth']:
            mv = VAL_RE.match(stripped)
            mf = None if mv else FUN_RE.match(stripped)
            if mv:
                key = 'val/var ' + mv.group(1)
            elif mf:
                receiver = mf.group(1) or ''
                name = mf.group(2)
                sig = params_sig(text, offsets[idx - 1] + raw.find(name) + len(name))
                head = f'{receiver}.{name}' if receiver else name
                key = f'fun {head}({sig})' if sig is not None else f'fun {head}'
            else:
                key = None
            if key:
                seen = frames[-1]['seen']
                if key in seen:
                    dups.append((key, seen[key], idx))
                else:
                    seen[key] = idx

        depth += raw.count('{') - raw.count('}')
        while frames and frames[-1]['body_depth'] > depth:
            frames.pop()
        if pending and depth < 0:
            pending = False

    return dups


def main():
    bad = 0
    for path in sys.argv[1:]:
        if not os.path.isfile(path):
            continue
        text = open(path, encoding='utf-8').read()
        dups = detect(text)
        name = os.path.basename(path)
        if dups:
            bad += 1
            print(f'{name}: 发现 {len(dups)} 处重复成员声明')
            for n, first, second in dups:
                print(f'    {n}  第 {first} 行 与 第 {second} 行')
        else:
            print(f'{name}: OK')
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
