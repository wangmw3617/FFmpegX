"""Kotlin/Compose：在非 @Composable 上下文里调用 @Composable API 的检测器。

这类错误编译不过，而且报错信息只给行号不给「应该怎么做」，
在 CI 上要等好几分钟才知道。本地先扫一遍能省掉整轮往返。

用法：python composecheck.py A.kt B.kt

原理：这些 API 的 getter 带 `@Composable` 注解，只能在 @Composable
函数（或 @Composable lambda）里读。检测方式是「向上找最近的函数声明，
看它有没有 @Composable」—— 是个启发式，会有少量漏报，但不会误伤
正常的 @Composable 代码。

已知的坑（本项目真实踩过两次）：
* `MaterialTheme.colorScheme` / `typography` / `shapes`
* `WindowInsets.statusBars` / `navigationBars` 等 —— 很多人以为它们是常量，
  其实都是 @Composable @ReadOnlyComposable 的取值器
* `LocalXxx.current`
"""
import re
import sys
import os

# (正则, 人类可读的名字)
COMPOSABLE_ONLY = [
    (r'\bMaterialTheme\.(colorScheme|typography|shapes)\b', 'MaterialTheme 主题取值'),
    (r'\bWindowInsets\.(statusBars|navigationBars|systemBars|ime|safeDrawing|'
     r'safeContent|safeGestures|displayCutout|imeAnimationTarget|systemGestures)\b',
     'WindowInsets 系统栏取值'),
    (r'\bisSystemInDarkTheme\s*\(', 'isSystemInDarkTheme()'),
    (r'\bLocal[A-Za-z0-9_]*\.current\b', 'Local*.current'),
    (r'\bremember[A-Z][A-Za-z0-9_]*\s*\(', 'remember*()'),
    (r'\b(LaunchedEffect|DisposableEffect|SideEffect|rememberCoroutineScope|'
     r'produceState|rememberUpdatedState)\s*[({]', 'Compose 副作用/记忆化 API'),
    (r'\b(stringResource|pluralStringResource|painterResource|dimensionResource|'
     r'colorResource|integerResource|booleanResource|stringArrayResource)\s*\(',
     '资源读取函数'),
    (r'\.(collectAsStateWithLifecycle|collectAsState)\s*\(', 'collectAsState'),
    (r'\bhiltViewModel\s*\(', 'hiltViewModel()'),
    (r'\bCompositionLocalProvider\s*\(', 'CompositionLocalProvider'),
]
COMPILED = [(re.compile(p), n) for p, n in COMPOSABLE_ONLY]

ANNOTATIONS = r'(?:@\w+(?:\([^)]*\))?\s+)*'
MODIFIERS = (
    r'(?:private\s+|internal\s+|public\s+|protected\s+|override\s+|open\s+|'
    r'abstract\s+|const\s+|lateinit\s+|suspend\s+|inline\s+|operator\s+|'
    r'infix\s+|tailrec\s+|external\s+|expect\s+|actual\s+|final\s+)*'
)
FUN_DECL = re.compile(
    r'^' + ANNOTATIONS + MODIFIERS + r'fun\s+'
    r'(?:<[^<>]*>\s*)?'
    r'(?:[A-Za-z_][\w.]*(?:<[^<>]*>)?\??\.)?'
    r'([A-Za-z_]\w*)'
)

# 这些函数的尾随 lambda 本身就是 @Composable 上下文。
# 典型是 `setContent { ... }` —— 它不在任何 @Composable 函数里，
# 但里面的代码是合法的，不认这一条就会误报。
COMPOSABLE_LAMBDA_HOSTS = re.compile(
    r'\b(setContent|CompositionLocalProvider|ProvideTextStyle|'
    r'ProvideContentColorTextStyle)\s*[({]'
)

# ---------------------------------------------------------------------------
# 项目约定：某些自研封装对参数类型有硬性要求
#
# `Modifier.liquidGlass(backdrop, shape)` 内部会调 backdrop 的 `lens` 效果，
# 而 lens 需要 Shape 的圆角半径来构造 SDF —— 库里写的是
# `shape as? CornerBasedShape ?: return null`，拿不到就
# `throw UnsupportedOperationException("Only CornerBasedShape is supported
# in lens effects.")`。
#
# 要命的是它发生在**绘制阶段**：不是编译错误、不是单测失败，
# 而是首帧绘制时主线程崩溃、应用秒退。CI 全绿也照样炸。
#
# `RectangleShape` 最容易踩 —— 看着像「圆角为 0 的矩形」，
# 类型上却不属于 CornerBasedShape 那一族。
#
# 封装层已经做了兜底（拿不到圆角就只画模糊，不会崩），
# 这条规则是为了让「折射效果悄悄失效」这件事在推送前就暴露出来。
#
# 只匹配**字面量** `RectangleShape`，不推断变量类型 ——
# `liquidGlass(backdrop, shape)` 里 shape 是什么类型这里无从得知，
# 报它只会制造误报。宁可漏报，不可误报。
SHAPE_ARG_PATTERNS = [
    (re.compile(r'\bliquidGlass\s*\([^)]*\bRectangleShape\b'),
     'liquidGlass 的形状不能是 RectangleShape（它不是 CornerBasedShape，'
     'lens 折射会失效；封装层已兜底不会崩）'),
]


def _is_comment(line):
    s = line.strip()
    return s.startswith('//') or s.startswith('*') or s.startswith('/*')


# 自身就叫 `rememberXxx` 的函数**声明**行。
#
# `remember*` 那条规则是按名字匹配的启发式，无法区分「调用 Compose 的
# rememberXxx()」和「声明一个恰好叫 rememberXxx() 的普通函数」。
# 后者是合法的（本项目就有一个 ViewModel 方法叫 rememberCurrentDir），
# 但它会被误报 —— 而声明行本身不可能构成「非法调用 @Composable」。
#
# 去掉这一类，保留对真实调用的检测：
#   fun rememberFoo() { ... }        ← 声明，跳过
#   val x = rememberFoo()            ← 调用，照报
#   rememberFoo()                    ← 调用，照报
SELF_DECL = re.compile(
    r'^' + ANNOTATIONS + MODIFIERS + r'fun\s+'
    r'(?:<[^<>]*>\s*)?'
    r'(?:[A-Za-z_][\w.]*(?:<[^<>]*>)?\??\.)?'
    r'(remember[A-Z]\w*)\s*[(<]'
)


def _is_self_remember_decl(line):
    """这一行是「声明一个名为 rememberXxx 的函数」吗（而非调用它）？"""
    return SELF_DECL.match(line.strip()) is not None


def check(text):
    """返回 [(line_no, line_text, api_name, enclosing_func, is_composable)]"""
    lines = text.split('\n')

    # 每行开始时的花括号深度
    depth_before = []
    depth = 0
    for ln in lines:
        depth_before.append(depth)
        depth += ln.count('{') - ln.count('}')

    # 收集函数声明：行号 -> (名字, 是否 @Composable)
    decls = []
    for i, raw in enumerate(lines):
        if _is_comment(raw):
            continue
        m = FUN_DECL.match(raw.strip())
        if not m:
            continue
        # 往上找注解
        j = i - 1
        is_composable = False
        while j >= 0:
            prev = lines[j].strip()
            if prev.startswith('@'):
                if prev.startswith('@Composable'):
                    is_composable = True
                j -= 1
                continue
            break
        decls.append((i, m.group(1), is_composable))

    findings = []
    for i, raw in enumerate(lines):
        if _is_comment(raw):
            continue
        stripped = raw.strip()
        # 「声明一个叫 rememberXxx 的普通函数」不是调用，详见 _is_self_remember_decl
        if _is_self_remember_decl(raw):
            continue
        for rx, label in COMPILED:
            if not rx.search(raw):
                continue
            if '@Composable' in stripped:
                break
            # 向上找最近的函数声明；途中若穿过一个 @Composable lambda 宿主，
            # 说明当前处在合法上下文里。
            enclosing = None
            in_composable_lambda = False
            for j in range(i, -1, -1):
                line_j = lines[j]
                if _is_comment(line_j):
                    continue
                if COMPOSABLE_LAMBDA_HOSTS.search(line_j):
                    in_composable_lambda = True
                    break
                mj = FUN_DECL.match(line_j.strip())
                if mj:
                    k = j - 1
                    comp = False
                    while k >= 0:
                        prev = lines[k].strip()
                        if prev.startswith('@'):
                            if prev.startswith('@Composable'):
                                comp = True
                            k -= 1
                            continue
                        break
                    enclosing = (mj.group(1), comp)
                    break
            if in_composable_lambda:
                break
            if enclosing is None:
                # 不在任何函数里（类属性初始化、顶层属性等）也读不到
                findings.append((i + 1, stripped, label, '非函数上下文（类属性 / 顶层属性同样读不到）'))
            elif not enclosing[1]:
                findings.append((i + 1, stripped, label, f'所在函数 {enclosing[0]}()'))
            break

    # ---- 项目约定：封装函数的参数类型 ----
    for i, raw in enumerate(lines):
        if _is_comment(raw):
            continue
        for rx, message in SHAPE_ARG_PATTERNS:
            if rx.search(raw):
                findings.append((i + 1, raw.strip(), message, '参数类型不满足封装要求'))

    return findings


def main():
    bad = 0
    for path in sys.argv[1:]:
        if not os.path.isfile(path):
            continue
        text = open(path, encoding='utf-8').read()
        findings = check(text)
        name = os.path.basename(path)
        if findings:
            bad += 1
            print(f'{name}: 发现 {len(findings)} 处疑似问题')
            for ln, code, label, context in findings:
                print(f'    第 {ln} 行 [{label}] {context}')
                print(f'        {code[:140]}')
        else:
            print(f'{name}: OK')
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
