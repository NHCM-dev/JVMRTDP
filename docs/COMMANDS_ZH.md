# JVMRTDP 命令参考

[English](COMMANDS.md) | [中文](COMMANDS_ZH.md)

本文档说明 JVMRTDP 2.0.0 的 TUI、命令行和调试命令。示例中的 `<pid>`、`<class>`、`<method>` 和 `<file>` 均为占位符。

## 1. 基本约定

- 启动后默认进入 TUI；使用 `--cli` 直接进入命令行。
- 控制端提示符为 `jvmrtdp>`。
- 连接后提示符为 `target[<pid>|<context>]>`。
- 含空格或 JVM 描述符的参数建议使用双引号。
- 类名通常使用 `com.example.Type`；描述符使用 JVM 格式，例如 `([Ljava/lang/String;)V`。
- `help` 显示当前上下文可用命令，`help syntax` 显示值表达式语法。

## 2. 启动与会话

```powershell
java -jar JVMRTDP-2.0.0.jar
java -jar JVMRTDP-2.0.0.jar --cli
```

控制端命令：

```text
ps | list
attach <pid>
inject <pid>
tui
version | ver
help
exit | quit
```

目标会话命令：

```text
help [syntax]
back
tui
version
exit
```

`back` 断开当前目标并返回进程列表。`exit` 关闭程序。

## 3. TUI 快捷键

页脚始终按当前视图显示有效操作。常用按键如下：

| 按键 | 操作 |
| --- | --- |
| `↑` / `↓` | 移动选择 |
| `PgUp` / `PgDn` | 翻页 |
| `Home` / `End` | 首项或末项 |
| `Tab` | 切换视图 |
| `Enter` | 打开或执行默认操作 |
| `Backspace` | 返回上级上下文或包 |
| `←` / `→` | 水平滚动 |
| `[` / `]` | 快速水平滚动 |
| `0` | 重置水平位置 |
| `/` | 过滤当前列表；`Esc` 取消 |
| `F` | 查找类、字段或方法 |
| `n` / `N` | 下一个或上一个匹配项 |
| `P` | 输入包名 |
| `J` | 显示或隐藏 JDK 类型 |
| `A` | 显示或隐藏数组类型 |
| `K` | 显示或隐藏 `<init>` / `<clinit>` |
| `O` | 导出当前内容 |
| `F2` | 切换到 CLI |
| `Q` | 返回或退出 |

调试视图常用按键：

| 按键 | 操作 |
| --- | --- |
| `F9` | 设置或清除当前 BCI 的断点 |
| `F7` | 单步执行 |
| `F8` | 继续当前线程 |
| `F6` | 暂停选中的线程 |
| `F4` | 开启或关闭运行时实时跟踪 |
| `T` | 打开线程列表 |
| `G` | 跳到当前执行位置 |
| `M` | 打开局部变量 |
| `Z` | 打开断点列表 |
| `*` | 冻结或恢复分析线程集 |

## 4. 上下文与栈

JVMRTDP 的操作以当前上下文为中心。上下文可以是类、静态上下文、对象、字段值、数组元素或调试局部变量。

```text
context
context class <class>
context <class>
context static field <class> <field[index]>
context field [declaring.Class::]<field[index]>
context index <index>
context value <literal>
context value <type> <literal>
context as <parent-or-interface>
context runtime
context list fields [glob]
context list methods [glob]
context save <name>
context use <name>
context bookmarks
context clear
```

上下文栈：

```text
stack [list [limit]]
stack depth
stack peek [index]
stack pop [count]
stack dup
stack push
stack swap
stack pick <index>
stack clear
```

`stack pop`、`stack back` 和 `stack drop` 等价。栈索引 `0` 表示栈顶。

## 5. 值表达式

常用字面量：

```text
null
true | false
123
123L
3.14F
3.14D
'A'
"text"
```

显式类型：

```text
context value int 42
context value java.lang.String "text"
```

可组合表达式：

```text
{new <class> [descriptor] [args...]}
{invoke <method> [descriptor] [args...]}
{static <class> <method> [descriptor] [args...]}
{field [declaring.Class::]<field>}
{static-field <class> <field>}
{index <index>}
```

表达式可嵌套。方法重载不明确时必须提供描述符。

使用 `resolve <literal|reference|{value-expression}>` 可在不改变上下文的情况下计算一个值。

## 6. 对象、字段与调用

```text
value [--deep [limit]]
field [declaring.Class::]<field[index]>
read [field] [declaring.Class::]<field[index]>
read static [field] [class] <field[index]>
read index <index>
set [field] [declaring.Class::]<field> <value>
set index <index> <value>

static field [class] <field[index]>
static set [class] <field> <value>
static invoke [class] <method> <descriptor> [args...]

invoke [declaring.Class::]<method> <descriptor> [args...]
construct [class] <descriptor|auto> [args...]
new [class] <descriptor|auto> [args...]
```

继承成员可使用 `DeclaringClass::member` 指定声明类。列表中的同名成员可使用 `[index]` 消除歧义。

数组与集合：

```text
array length
array get <index> [--deep [limit]]
array set <index> <value>
array list [limit]
```

`value` 会为数组、`Collection`、`Map`、`Iterable` 和 `CharSequence` 显示可用的大小信息。

`debug` 显示当前对象的 identity、形状、大小和反射计数；`stats` 显示已加载类、线程、句柄、堆使用量和运行时间。

## 7. 类浏览、搜索与导出

```text
class
class load <class>
class info
class fields [all|static|virtual] [glob]
class methods [all|static|virtual] [glob]
class constructors

package [name]
find package [glob] [--limit n]
find <class|interface|enum|annotation|array> [name-glob] [--package glob] [--extends glob] [--implements glob] [--limit n]
find <extends|implements> <type-glob> [name-glob] [--limit n]
find field [name-glob] [--class glob] [--type glob] [--static|--virtual] [--limit n]
find method [name-glob] [--class glob] [--returns glob] [--params glob] [--static|--virtual] [--limit n]
```

`class load` 在目标 JVM 中执行等价于 `Class.forName` 的加载操作，可能触发类初始化。

导出与转储：

```text
export [append] <file> [command ...]
dumpclass [class] <output.class>
dumpclass package <name|.> <directory> [--recursive|--no-recursive] [--match glob] [--limit n]
```

`export` 执行指定命令并将 UTF-8 输出写入文件；未提供命令时导出 `value --deep`。类文件使用 `dumpclass`，反编译使用 `--out`，调试器快照提供结构化输出。

## 8. 反编译与字节码

```text
decompile class [class] [--engine cfr|procyon] [--out <file>]
decompile method [class] <method> <descriptor> [--engine cfr|procyon] [--out <file>]
bytecode [class] <method> <descriptor> [--out <file>]
```

单方法反编译按名称和完整描述符定位方法。视图支持搜索、行号/BCI 跳转、断点和导出。

原生方法与抽象方法没有 `Code` 属性。方法信息栏会明确显示 `NATIVE`、`ABSTRACT` 或 `BYTECODE`。

## 9. 调试器

### 9.1 状态与事件

```text
debugger status
debugger enable
debugger disable
debugger locations
debugger locations
```

启动阶段的 `break-main` 和 `break-clinit` 是一次性入口暂停。普通断点会在命中后重新安装并保持有效，直到显式清除。

### 9.2 断点

```text
debugger break <class> <method> <descriptor> <bci>
debugger clear <class> <method> <descriptor> <bci>
debugger breakpoints
debugger breakpoints clear-all
```

### 9.3 字段监视点

```text
debugger watch read set <class> <field> <descriptor>
debugger watch write set <class> <field> <descriptor>
debugger watch read clear <class> <field> <descriptor>
debugger watch write clear <class> <field> <descriptor>
debugger watches
debugger watches clear-all
```

监视点适用于字段读取或写入。调用点可通过目标方法入口 BCI 或包含调用指令的 BCI 设置断点。

### 9.4 线程、栈与局部变量

```text
debugger threads
debugger pause <all-thread-index>
debugger pause-all
debugger frames [paused-index] [max]
debugger stack [paused-index] [max]
debugger locals [paused-index] [depth]
debugger local-context <paused-index> <depth> <slot>
debugger current [paused-index] [depth] [radius]
debugger sample <all-thread-index> [depth] [radius]
```

`all-thread-index` 来自 `debugger threads` 的完整线程列表；`paused-index` 来自已暂停线程列表。`local-context` 将局部变量保存为当前对象上下文。

没有 `LocalVariableTable` 时，工具会按 `maxLocals` 尝试读取槽位；非存活槽、二槽值的后续槽和无法确定类型的值会标注原因。

`sample` 会短暂停指定线程，采集当前位置、栈和局部变量后恢复原状态。它适合定位正在运行、等待或刚进入新方法的线程。

### 9.5 单步与继续

```text
debugger step [paused-index]
debugger continue [paused-index|all]
```

### 9.6 分析冻结与快照

```text
debugger freeze
debugger freeze refresh
debugger freeze-status
debugger thaw
debugger snapshot <file|-> [json|jsonl] [max-frames] [locals-depth]
```

冻结会保存线程原状态，排除代理服务、Attach Listener 等敏感线程，并只恢复本次冻结暂停的线程。快照用于离线分析或其他程序读取。

### 9.7 调试限制

- 标准 JVMTI 不提供当前操作数栈的值。
- 原生帧没有 Java BCI 或局部变量。
- 优化、缺失调试表或未存活槽位会限制局部变量读取。
- 暂停 UI、GC、类加载或锁相关线程可能影响目标程序响应。

## 10. JVMTI

能力与运行环境：

```text
jvmti capabilities
jvmti capability-status
jvmti capability add <name...>
jvmti capability relinquish <name...>
jvmti phase
jvmti time
jvmti timer-info
jvmti current-thread-cpu-time
jvmti processors
jvmti location-format
jvmti property get <name>
jvmti property set <name> <value>
jvmti verbose <other|gc|class|jni> <enable|disable>
```

类、方法与字段：

```text
jvmti class info <class>
jvmti class interfaces <class>
jvmti class loader-classes <class>
jvmti class source-debug <class>
jvmti class constant-pool <class>
jvmti method info <class> <method> <descriptor>
jvmti method bytecodes <class> <method> <descriptor>
jvmti method lines <class> <method> <descriptor>
jvmti field info <class> <field> <descriptor>
```

事件与类变换：

```text
jvmti events
jvmti events generate <event>
jvmti retransform <class>
jvmti redefine <class> <class-file>
jvmti breakpoint set <class> <method> <descriptor> <location>
jvmti breakpoint clear <class> <method> <descriptor> <location>
jvmti watch access <set|clear> <class> <field> <descriptor>
jvmti watch modification <set|clear> <class> <field> <descriptor>
```

线程、监视器与对象：

```text
jvmti threads [prefix] [limit]
jvmti thread info <object>
jvmti thread state <object>
jvmti thread stack <object> [max]
jvmti thread frame-count <object>
jvmti thread cpu-time <object>
jvmti thread owned-monitors <object>
jvmti thread contended-monitor <object>
jvmti thread suspend|resume|interrupt <object>
jvmti thread frame-pop <object> [depth]
jvmti size <object>
jvmti hash <object>
jvmti monitor-usage <object>
jvmti tag <object> [value]
jvmti tagged <tag> [prefix] [limit]
jvmti gc
jvmti properties
```

动态注入位于 `LIVE` 阶段，只能添加 JVM 仍报告为 potential 的 capability。完整覆盖情况见 [JVMTI API 覆盖范围](JVMTI-API-COVERAGE_ZH.md)。

## 11. Java 部署与回调

```text
code source <name> <file|dir> [options]
code methods <name> <class> <file> [options]
code jar <name> <jar-file> [options]
code list
code run <deployment-id> <class> <method> <descriptor> <static|this|object-ref> [args...]
code close <deployment-id>
code callback add <deployment-id> <handler-class> <event,event,...> [sync|async]
code callback remove <callback-id>
code callback list
code callback stats
```

回调事件覆盖线程、类、方法、异常、字段、监视器、GC、编译和资源事件。处理器必须为静态方法，且其描述符必须与事件载荷匹配。高频事件应使用窄过滤条件并限制处理开销。

部署选项包括 `--anchor`、`--same-loader`、`--child`、`--scope`、`--classpath`、`--javac` 和 Java 编译器的源码/版本选项。

## 12. 批处理与脚本

```text
batch <commands.txt>
script <file.jrd>
```

批处理文件每行是一条目标命令，并在命令关闭会话时停止。脚本提供变量、条件分支、标签和引用管理，详见[脚本指南](SCRIPTING_ZH.md)。

## 13. Java 库 API

本参考中的所有上下文命令都可以通过已连接的库会话执行：

```java
JvmRtdpCommandResult result = session.execute(
        "bytecode com.example.Application main ([Ljava/lang/String;)V");
result.requireSuccess();
System.out.print(result.standardOutput());
```

`session.executeAgent()` 只用于目标 agent 直接实现的命令，例如 `ping`、`info` 和 `native`。`context`、`find`、`decompile` 和 `debugger` 等控制端命令使用 `session.execute()` 或对应类型化 API。

以下入口可避免解析文本输出：

```text
session.jni()
session.jvmti()
session.operations()
session.context()
session.workspace()
session.debugger()
```

依赖、生命周期、异步调用和完整示例见 [Java 库指南](LIBRARY_ZH.md)。

## 14. 常见错误

- `JNI/JVMTI bridge is unavailable`：JAR 与原生代理不匹配，或目标未正确加载代理。
- `capability is not potential`：当前阶段无法获取该 capability；使用 `-agentpath` 在启动阶段加载。
- `JVMTI_ERROR_NOT_FOUND`：目标断点、字段监视点或成员不存在，或已被清除。
- `NATIVE_METHOD`：当前帧为原生方法，没有 Java 局部变量或 BCI。
- `OPAQUE_FRAME`：当前帧不能执行所请求的栈操作。
- `INVALID_SLOT` / `TYPE_MISMATCH`：局部变量槽无效、已经失活或类型不匹配。
