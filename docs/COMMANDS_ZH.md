# JVMRTDP 命令参考

[English](COMMANDS.md) | [中文](COMMANDS_ZH.md)

本文档说明 JVMRTDP 2.2.0 的 TUI、命令行和调试命令。示例中的 `<pid>`、`<class>`、`<method>` 和 `<file>` 均为占位符。

## 1. 基本约定

- 启动后默认进入 TUI；使用 `--cli` 直接进入命令行。
- 控制端提示符为 `jvmrtdp>`。
- 连接后提示符为 `target[<pid>|<context>]>`。
- 含空格或 JVM 描述符的参数建议使用双引号。
- 类名通常使用 `com.example.Type`；描述符使用 JVM 格式，例如 `([Ljava/lang/String;)V`。
- `help` 显示当前上下文可用命令，`help syntax` 显示值表达式语法。

## 2. 启动与会话

```powershell
java -jar JVMRTDP-2.2.0.jar
java -jar JVMRTDP-2.2.0.jar --cli
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

页脚始终按当前视图显示有效操作。CLI 与 TUI 是同一目标会话的两种交互方式，切换时会保留上下文和 TUI 的分析、调试视图。常用按键如下：

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
| `@` | 在 Fields/Methods 中显示或隐藏静态成员 |
| `#` | 在 Fields/Methods 中显示或隐藏实例字段和虚方法 |
| `n` / `N` | 下一个或上一个匹配项 |
| `P` | 输入包名 |
| `J` | 显示或隐藏 JDK 类型 |
| `a` | 在 Browse 中显示或隐藏数组类型 |
| `A` | 反编译选中或当前类 |
| `K` | 显示或隐藏 `<init>` / `<clinit>` |
| `O` | 导出当前内容 |
| `F2` | 切换到 CLI |
| `Q` | 返回或退出 |

调试视图常用按键：

| 按键 | 操作 |
| --- | --- |
| `F9` | 设置或清除当前 BCI 的普通断点 |
| `Shift+F9` | 设置或清除仅限当前对象 Context 的断点 |
| `F7` | 单步执行 |
| `F8` | 继续当前线程 |
| `F6` | 暂停选中的线程 |
| `F4` | 开启或关闭运行时实时跟踪 |
| `T` | 打开线程列表 |
| `G` | 选中并居中显示当前执行的 BCI |
| `M` | 打开局部变量 |
| `Z` | 打开断点列表 |
| `*` | 冻结或恢复分析线程集 |

暂停或实时跟踪到的执行 BCI 会始终以黄色 `>` 标记。移动列表光标不会清除该执行标记；按 `G` 可随时回到当前 BCI。

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

### 4.1 托管引用

```text
references list
references save <name> [strong|weak]
references field <name> [declaring.Class::]<field> [strong|weak]
references static <name> <class> [declaring.Class::]<field>
references use <name>
references refresh [name]
references info <name>
references set <name> <value>
references null <name>
references release <name|all>
```

`refs` 是 `references` 的别名。对象快照可使用强引用或弱引用；字段条目会在刷新时重新读取实例或静态字段。状态包括 `LIVE`、`NULL`、`COLLECTED`、`RELEASED` 和 `ERROR`。托管引用可在值表达式中写作 `$name` 或 `&name`。

TUI 的 `references` 页中，`Enter` 打开为 Context，`S`/`Shift+S` 强/弱保存当前对象，`=` 替换，`X` 写入 null，`Delete` 释放，`F5` 刷新。

### 4.2 String Hook

```text
strings list
strings allocation <name> <content-glob> [creator-class creator-method descriptor]
                   [fast|complete] [ignore-case|case-sensitive]
                   [once|max=N] [sample=N]
strings field <name> <read|write> <class> <field> [object]
strings method <name> <entry|exit> <class> <method> <descriptor>
strings on <name>
strings off <name>
strings rearm <name>
strings read <name>
strings use <name>
strings set <name> <value>
strings track <hook> <reference> [strong|weak]
strings call <hook> <method> <descriptor> [arguments...]
strings remove <name>
strings clear
```

Allocation Hook 默认使用 `fast`：临时在 `java.lang.String.<init>` 的构造器返回处加入
轻量 bootstrap 探针，不开启全局方法退出或对象分配回调；删除最后一个 Allocation Hook 时会
还原该探针。`complete` 额外开启 `VM_OBJECT_ALLOC`，用于捕获没有
可见构造器退出的 VM/native 或已被 JIT intrinsic 化的 String；该模式会产生 JVM 全局分配事件开销。同一物理对象不会因
两条路径重复命中。

内容先在 Java bootstrap 桥中预过滤，未匹配时不会进入 native/JVMTI；之后再按需读取创建栈。
三个 creator pattern 都是 `*` 时不会做完整栈遍历。
创建者 class/method/descriptor 必须在同一帧匹配。`once` 等价于 `max=1`，`max=N` 限制停止
次数，`sample=N` 每 N 次语义命中停止一次。高分配程序应优先使用窄内容条件、`fast` 和有界策略；
没有仍处于 armed 状态的 Hook 时桥接热路径会自动关闭。
达到上限的 Hook 显示 `DONE`；`rearm` 会重置原生计数并重新启用。

字段 Hook 仅接受 `Ljava/lang/String;` 字段，分别映射到 JVMTI 字段读取/修改监视点。可选的 `object` 使用当前对象 Context，只匹配该实例；省略时匹配所有实例。方法 Hook 映射到进入/退出事件断点。命中记录和 Frames/Locals 由共享调试器提供。

`read`、`use`、`track` 和 `call` 也可操作最近一次 Allocation 命中的 String；`set` 仅用于字段型
Hook，因为字符串对象不可变。TUI 的 `strings` 页使用 `A` 添加、`Enter` 打开、`F9` 启用/禁用/重新启用、`=` 替换、`&` 加入 References、`Delete` 删除。

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
class load <class> [--no-init]
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
find unloaded [class|field|method] [glob] [--class owner-glob] [--limit n]
```

`find unloaded` 从控制端扫描目标 classpath 以及可用的 `rt.jar`/`jmods`，不触发类加载或初始化。结果与已加载类分开显示；指定未加载类时，`decompile` 和只读 `bytecode` 会直接使用 class bytes。

`class load` 在目标 JVM 中执行等价于 `Class.forName` 的加载操作并初始化类。添加
`--no-init` 后只加载和链接类，不运行 `<clinit>`；可先重定义初始化器或其他字节码，再恢复执行。

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
bytecode <insert-before|insert-after|replace> <class> <method> <descriptor> <bci> <assembly>
bytecode delete <class> <method> <descriptor> <from-bci> [to-bci]
bytecode <returns-insert|returns-replace> <class> <method> <descriptor> <assembly>
bytecode intercept-return <class> <method> <descriptor> <hook-class> <hook-method>
bytecode patch-file <class> <file> [--preview] [--out <class-file>]
bytecode <undo|redo> <class>
```

单方法反编译按名称和完整描述符定位方法。视图支持搜索、行号/BCI 跳转、断点和导出。

原生方法与抽象方法没有 `Code` 属性。方法信息栏会明确显示 `NATIVE`、`ABSTRACT` 或 `BYTECODE`。

文本汇编使用 JVM 指令名，多条指令以 `;;` 或换行分隔。owner 可写点号名或 internal name；跳转目标使用本地 `LABEL name`，也可引用已有的 `@BCI`。例如：

```text
bytecode insert-before com.example.Service value "()I" 0 "LDC \"entered\" ;; INVOKESTATIC example/Trace log (Ljava/lang/String;)V"
bytecode replace com.example.Service value "()I" 8 "BIPUSH 42 ;; IRETURN"
bytecode delete com.example.Service value "()I" 2 7
bytecode returns-insert com.example.Service value "()I" "DUP ;; INVOKESTATIC example/Trace onInt (I)V"
bytecode intercept-return com.example.Service value "()I" example.ReturnHooks onInt
```

`intercept-return` 会在每个正常 return 前调用可见的静态 hook。返回类型为 `T` 时 hook 描述符必须是 `(T)T`，`void` 则是 `()V`；hook 返回值会替换原结果。通常应以 `SAME_LOADER` 把 hook 部署到被编辑类可见的类加载器。

使用 `returns-insert` 时，非 `void` 返回值已经位于操作数栈顶，片段必须为原 return 留下等价结果。`returns-replace` 只删除 return 指令，旧结果仍在栈上，因此替换片段必须消费旧值并执行兼容的 return 或 throw。例如把所有 `int` 结果改为 42：`POP ;; BIPUSH 42 ;; IRETURN`。

补丁文件把所有行作为一个类事务执行：

```text
# operation|method|descriptor|arguments
insert-before|value|()I|0|LDC "entered" ;; INVOKESTATIC example/Trace log (Ljava/lang/String;)V
replace|value|()I|8|BIPUSH 42 ;; IRETURN
delete|other|()V|4|9
returns-insert|compute|(J)J|DUP2 ;; INVOKESTATIC example/Trace onLong (J)V
```

`--preview` 只验证并生成字节，不安装；`--out` 保存生成的 class。改写会重新计算 stack-map frame 和 max，再执行一次 JVMTI 类重定义。重定义前失败不会修改目标类；受管断点会清除并按新 BCI 恢复。HotSpot 的类结构限制仍然适用，已经执行中的 frame 可能继续运行旧字节码，应再次调用方法来稳定观察新结果。

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
debugger break-context <method> <descriptor> <bci> [caller-class [caller-method [caller-descriptor]]]
debugger clear-context <method> <descriptor> <bci> [caller-class [caller-method [caller-descriptor]]]
debugger breakpoints
debugger breakpoints clear-all
```

`debugger break` 支持尚未加载的类。注册会保持 pending，在目标类触发 `ClassPrepare` 时自动解析方法并安装；在加载前执行 `clear` 会删除 pending 注册。

### 9.3 字段监视点

```text
debugger watch read set <class> <field> <descriptor>
debugger watch write set <class> <field> <descriptor>
debugger watch read clear <class> <field> <descriptor>
debugger watch write clear <class> <field> <descriptor>
debugger watches
debugger watches clear-all
```

字段 read/write watch 同样支持未加载 owner，并在 `ClassPrepare` 时安装。此时还没有对象引用，因此 pending 断点/watch 面向该成员的所有实例。

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
debugger local-set <paused-index> <depth> <local-index> <value>
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

冻结会保存线程原状态，排除代理服务、Attach Listener 等敏感线程，并只恢复本次冻结暂停的线程。快照 schema v5 包含断点、监视点、托管引用、String Allocation 条件与命中、暂停栈帧/local、方法退出返回值和事件对象，供离线分析或其他程序读取。

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
code callback enable <callback-id>
code callback disable <callback-id>
code callback reset <callback-id>
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
session.references()
session.stringHooks()
```

依赖、生命周期、异步调用和完整示例见 [Java 库指南](LIBRARY_ZH.md)。

## 14. 常见错误

- `JNI/JVMTI bridge is unavailable`：JAR 与原生代理不匹配，或目标未正确加载代理。
- `capability is not potential`：当前阶段无法获取该 capability；使用 `-agentpath` 在启动阶段加载。
- `JVMTI_ERROR_NOT_FOUND`：目标断点、字段监视点或成员不存在，或已被清除。
- `NATIVE_METHOD`：当前帧为原生方法，没有 Java 局部变量或 BCI。
- `OPAQUE_FRAME`：当前帧不能执行所请求的栈操作。
- `INVALID_SLOT` / `TYPE_MISMATCH`：局部变量槽无效、已经失活或类型不匹配。
## 新增调试事件与控制

启动时可在控制端连接前暂停：

```text
-agentpath:agent.dll=break-entry=类#方法#描述符
-agentpath:agent.dll=break-exit=类#方法#描述符
-agentpath:agent.dll=break-exception=异常类通配符
```

多个 agent 选项用逗号分隔。运行时命令：

```text
debugger event-break <entry|exit> <class> <method> <descriptor> [subtypes]
debugger exception-break <class-glob>
debugger event-breakpoints [clear-all]
debugger event-clear <index>
debugger step-out [paused-index]
debugger force-return <paused-index> <value>
debugger force-return-void <paused-index>
```

`subtypes` 可从 abstract/interface 声明断到实际实现。native 方法可以触发进入/退出事件，
但 native frame 没有 Java BCI/local，也不能用标准 JVMTI 强制返回。`METHOD_EXIT` 发生时返回值
已提交；要改返回值，应在方法进入、字节码断点或单步暂停时使用 `force-return`。

<!-- English COMMANDS.md is canonical. -->
