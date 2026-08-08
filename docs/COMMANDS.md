# JVMRTDP 命令手册

本文描述 `build/libs/JVMRTDP.jar` 当前实现的全部公开交互命令。

## 1. 语法约定

| 记号 | 含义 |
|---|---|
| `<value>` | 必填参数 |
| `[value]` | 可选参数 |
| `a\|b` | 二选一 |
| `...` | 可重复参数 |
| `glob` | 支持 `*` 和 `?` 的通配符 |
| `descriptor` | JVM 字段/方法描述符 |

命令名不区分大小写，Java 类名、字段名、方法名和通配符匹配区分大小写。

包含空格的参数使用双引号：

```text
set title "string:hello world"
```

双引号和反斜杠可用 `\"`、`\\` 转义。未加引号的 `->` 是 pipeline 分隔符：

```text
context class com.example.App -> static field INSTANCE -> field service -> value
```

## 2. Context 改变规则

| 命令 | 是否改变 context | 说明 |
|---|---:|---|
| `context ...` | 是 | 显式选择类、对象、数组元素或字面量 |
| `field name` | 是 | 字段结果成为新 context |
| `static field ...` | 是 | 静态字段结果成为新 context |
| `invoke ...` | 通常是 | 非 void 返回值成为新 context；void 保持原 context |
| `construct ...` | 是 | 新对象成为 context |
| `array get n` | 是 | 数组元素成为 context |
| `read ...` | 否 | 只打印，临时 handle 随即释放 |
| `value` / `debug` / `stats` | 否 | 只输出 |
| `set ...` / `static set ...` | 否 | 修改对象，但接收者 context 不变 |
| `find` / `class` / `package` | 否 | 只列出元数据 |
| `version` | 否 | 输出构建、Agent 和协议版本 |

每次 context 改变时，原 context 会进入栈。Prompt 每次显示前都会尝试重新读取当前对象的显示值。

`context` 的别名是 `ctx`。

## 3. 程序入口

```text
java -jar JVMRTDP.jar
java -jar JVMRTDP.jar list
java -jar JVMRTDP.jar ps
java -jar JVMRTDP.jar inject <pid>
java -jar JVMRTDP.jar attach <pid>
java -jar JVMRTDP.jar version
java -jar JVMRTDP.jar --version
java -jar JVMRTDP.jar -V
java -jar JVMRTDP.jar --help
```

无参数启动进入 `jvmrtdp>` 控制端。`list`/`ps` 和 `inject`/`attach` 分别是同义写法。

## 4. 本地控制端 `jvmrtdp>`

### `help`

```text
help
help <command>
```

`help` 只列出命令名称和简短说明；`help <command>` 显示该命令语法。

别名：`?`。

### `ps`

```text
ps
```

列出可访问并已加载 JVM 的进程：

- PID；
- 架构；
- 任务管理器 Details 页中的映像名，例如 `java.exe`、`idea64.exe`；
- 进程运行时间；
- PID 对应的可见顶层窗口标题；
- Java 主类、模块或 JAR，嵌入式 JVM 显示 `<embedded JVM>`。

别名：`list`。

### `attach`

```text
attach <pid>
```

注入 Agent 并进入 `target[pid|context]>`。连接关闭时会释放当前会话 handle。

别名：`inject`。

### `version`

```text
version
```

输出控制端构建版本，例如 `JVMRTDP 1.0.0`。别名：`ver`。

### `exit`

```text
exit
```

退出本地控制端。别名：`quit`。

## 5. 目标端帮助和会话

### `help`

```text
help
help <command>
help syntax
```

- `help`：只列出目标命令名称和说明，不展开语法。
- `help <command>`：显示一个命令的完整 usage。
- `help syntax`：显示 context、调用、检索和 dump 示例。

别名：`?`。

### `back`

```text
back
```

断开当前目标并返回 `jvmrtdp>`。之后可以再次 attach 同一 JVM。

### `exit`

```text
exit
```

断开目标并同时退出控制端。别名：`quit`。

### `version`

```text
version
```

输出三个相互独立的版本值：

```text
controller=1.0.0
target.agent=1.0.0
protocol=1
```

`controller` 和 `target.agent` 不一致通常表示目标中运行的 Agent 与当前控制端不是同一次构建。
别名：`ver`。

## 6. Context 选择

### 查看当前 context

```text
context
```

### 选择类

```text
context class <class-name>
context <class-name>
```

示例：

```text
context class java.lang.System
context java.lang.System
```

### 选择静态字段值

```text
context static field <class-name> <field[index]>
```

示例：

```text
context static field com.example.App INSTANCE
context static field com.example.State VALUES[2]
```

### 从当前对象选择实例字段

```text
context field [declaring.Class::]<field[index]>
```

示例：

```text
context field service
context field com.example.Parent::value
context field names[0]
```

### 选择数组元素

```text
context index <non-negative-index>
```

当前 context 必须是数组。

### 选择字面量

```text
context value <literal>
context value <type> <literal>
```

示例：

```text
context value null
context value int 7
context value string "hello world"
```

### 类型视图

```text
context as <parent-class-or-interface>
context runtime
```

`context as` 保留对象身份，仅把后续成员查找视图改为指定父类或接口；类型必须与实际对象兼容。
`context runtime` 恢复运行时类型视图。

```text
context static field com.example.State CURRENT
context as com.example.Parent
read value
context runtime
```

### 列出当前类型成员

```text
context list fields [name-glob]
context list methods [name-glob]
```

类 context 列出静态成员；对象 context 列出实例成员。

### Context 书签

```text
context save <name>
context use <name>
context bookmarks
```

书签保存一个 context 栈值。`context use` 会把该值重新压到顶部。

### 清空

```text
context clear
```

清空当前 context、栈、书签并释放 context 保留的对象 handle。

## 7. Context 栈

当前 context 是 `[0]`，越大的索引越旧。

```text
stack
stack list [limit]
stack depth
stack pop [count]
stack back [count]
stack drop [count]
stack peek [index]
stack dup
stack push
stack swap
stack pick <index>
stack clear
```

- `pop`/`back`/`drop`：弹出当前项并恢复旧 context。
- `peek`：查看但不改变栈。
- `dup`/`push`：复制栈顶。
- `swap`：交换最上面两项。
- `pick n`：复制历史项到栈顶，原栈顶进入历史。

同样的操作可以写在 `context` 后：

```text
context history [limit]
context depth
context back [count]
context pop [count]
context peek [index]
context dup
context swap
context pick <index>
```

## 8. 字面量和对象参数

方法、构造器和 set 命令支持：

```text
null
true | false
byte:1 | short:2 | int:3 | long:4
float:1.5 | double:2.5
char:x
string:text | str:text
bytes:<base64>
123             # 自动 int
123L            # 自动 long
1.5             # 自动 double
1.5F            # 自动 float
plain-text      # 无法解析为数字时作为 String
this | context  # 当前对象
$name | @name   # 脚本 workspace 中的命名对象
```

包含空格时要给整个 token 加引号：

```text
invoke rename (Ljava/lang/String;)V "string:new display name"
```

## 9. 查看当前值

```text
value
value --deep [limit]
```

`value` 输出显示值、运行时类型和远程 ID。`--deep` 可展开：

- primitive/reference array；
- `Iterable`；
- `Map`。

默认最多展开 32 项；显式 limit 范围为 1..10000。

## 10. 字段读取与写入

### 读取并切换 context

```text
field [declaring.Class::]<field[index]>
```

```text
field name
field com.example.Parent::name
field values[3]
```

字段结果成为新 context，原 context 进入栈。

### 仅读取，不切换 context

```text
read [field] [declaring.Class::]<field[index]>
read static [field] [class-name] <field[index]>
read index <index>
```

```text
read status
read field com.example.Parent::status
read values[2]
read static com.example.Config ENABLED
read static field com.example.Config VALUES[1]
read index 4
```

`read` 只打印值和类型，不增加 context 栈；临时远程 handle 在打印后释放。

别名：`peekfield`。

### 修改当前对象

```text
set [field] [declaring.Class::]<field> <value>
set index <index> <value>
```

```text
set status string:ready
set field status string:ready
set com.example.Parent::count int:7
set owner this
set index 2 int:9
```

字段写入后 context 保持为接收者。

## 11. 静态操作

```text
static field [class-name] <field[index]>
static invoke [class-name] <method> <descriptor> [arguments ...]
static set [class-name] <field> <value>
```

省略类名时使用当前 class context 或当前对象视图的类。

```text
context class com.example.Tools
static field INSTANCE
static invoke reset ()V
static set ENABLED true

static invoke com.example.Tools parse (Ljava/lang/String;)I string:42
```

`static field` 的结果会成为 context；`static set` 不改变 context。

## 12. 方法调用

```text
invoke [declaring.Class::]<method> <descriptor> [arguments ...]
```

对象 context 使用实例方法；类 context 使用静态方法。

```text
invoke size ()I
invoke rename (Ljava/lang/String;)V string:Ada
invoke calculate (IF)J int:3 float:1.5
```

默认使用 Java 虚分派。指定声明类时调用该类的精确实现：

```text
invoke com.example.Parent::run ()V
```

返回值不是 void 时成为新 context。void 方法输出 `=> void (context unchanged)`。

## 13. 构造对象

```text
construct [class-name] <descriptor|auto> [arguments ...]
```

别名：`new`。

```text
construct com.example.User (Ljava/lang/String;I)V string:Ada int:37

context class com.example.User
construct (Ljava/lang/String;)V string:Ada
construct auto string:Ada int:37
```

`auto` 根据参数运行时类型选择构造器；没有匹配或存在歧义时失败。需要稳定结果时应提供 descriptor。

## 14. 数组

```text
array length
array get <index>
array set <index> <value>
array list [limit]
```

- `array get`：元素成为新 context。
- `array set`：修改当前数组，context 不变。
- `array list`：按 `value --deep` 规则展开。

字段名也支持紧凑的 `[index]`：

```text
static field com.example.State VALUES[2]
read values[0]
```

## 15. 类信息

```text
class
class info
class fields [all|static|virtual] [name-glob]
class methods [all|static|virtual] [name-glob]
class constructors
```

省略子命令等价于 `class info`。

字段、方法和构造器使用 Java 可读类型名，例如 `int`、`long`、`byte[]`；同时保留 JVM descriptor。
继承层次中的同名成员会保留其声明类，以便使用 `Parent::member` 精确选择。

## 16. Package 浏览

```text
package
package .
package <default>
package <package-name>
```

- 无参数、`.`、`<default>`：列出根下的直接子包和默认包中的类。
- 指定包：只列出直接子包和直接属于该包的类。
- 只显示目标 JVM 当前已加载的类。

## 17. 通配符检索

`*` 匹配任意长度文本，`?` 匹配一个字符。匹配完整字符串；类型名称同时允许完整名和简单类名匹配。
默认 limit 为 200，范围 1..10000。

### Package

```text
find package [glob] [--limit n]
```

```text
find package java.*
find package *internal* --limit 500
```

### Class / interface / enum / annotation / array

```text
find <class|interface|enum|annotation|array> [name-glob]
    [--package package-glob]
    [--extends type-glob]
    [--implements interface-glob]
    [--kind kind]
    [--limit n]
```

`--extends` 检查传递父类；对于 interface，检查其传递父接口。`--implements` 检查传递接口关系。
Package glob 以 `.**` 结尾时表示该包及所有子包。

```text
find class *Service --package com.example.**
find class *Repository --extends *BaseRepository --implements *Closeable
find interface *Listener
find enum *Status
find annotation *Inject*
```

关系快捷形式：

```text
find extends <parent-glob> [class-glob] [options]
find implements <interface-glob> [class-glob] [options]
```

### Field

```text
find field [name-glob]
    [--class declaring-class-glob]
    [--type java-type-glob]
    [--static|--virtual|--all]
    [--limit n]
```

```text
find field *cache* --class com.example.* --type java.util.Map --static
find field data --type byte[] --virtual
```

### Method

```text
find method [name-glob]
    [--class declaring-class-glob]
    [--returns java-type-glob]
    [--params comma-separated-java-type-glob]
    [--static|--virtual|--all]
    [--limit n]
```

```text
find method get* --class com.example.* --returns byte[]
find method calculate --params int,float --returns long
find method parse --params "java.lang.String,*" --static
```

跨类 field/method 检索搜索各加载类的 declared members；当前类的 `class fields/methods` 则包含父类层次。

别名：`search`。

## 18. 调试和统计

### `debug`

```text
debug
```

当前 context 必须是对象。输出：远程对象 ID、运行时类、shape、长度/大小、identity hash、
declared field/method 数量和显示值。

### `stats`

```text
stats
```

输出当前已加载/累计加载类数、线程数、远程强引用 handle 数、堆使用量、处理器数和目标 JVM uptime。

## 19. 输出到文件

```text
export [append] <file> [command ...]
```

执行嵌套命令并把标准输出写成 UTF-8 文件。省略命令时执行 `value --deep`。

```text
export build/out/value.txt
export build/out/fields.txt class fields
export append build/out/history.log stats
```

导出 pipeline 时把整个 pipeline 放在一个引号参数中，否则外层会先执行 `->`：

```text
export build/out/status.txt "context class com.example.App -> static field INSTANCE -> read status"
```

默认覆盖文件；`append` 追加。父目录会自动创建。

## 20. Dump class

单类：

```text
dumpclass [class-name] <output.class>
dumpclass class [class-name] <output.class>
```

省略类名时使用当前 context 的类。

```text
dumpclass build/dump/Current.class
dumpclass com.example.Model build/dump/Model.class
```

按包批量：

```text
dumpclass package <package|.|<default>> <output-directory>
    [--recursive|-r|--no-recursive]
    [--match class-name-glob]
    [--limit n]
```

```text
dump package com.example build/dump --no-recursive
dump package com.example build/dump --recursive --match *Service --limit 500
dump package . build/default-package --match *Main*
```

- 默认不递归。
- 默认 limit 为 1000。
- 输出目录保留 Java 包层次。
- 每个 class 都通过 JVMTI 读取并写入 `.class` 文件。
- 单个类失败不会终止整个批次，结束时输出成功/失败统计。
- 不会把 class bytes 打印到终端。

别名：`dump`。

## 21. 批处理与脚本

### 批处理

```text
batch <commands.txt>
```

UTF-8 文件每个非空、非 `#` 注释行是一条交互命令。行间共享 context，行内可以使用 pipeline。
遇到会话关闭命令或异常时停止。

### 流程脚本

```text
script <file.jrd>
```

支持命名 handle、`if`、`ifnull`、`switch`、`goto`、`print`、`export`、`release` 和
`command` 嵌入交互命令。完整语法见 [SCRIPTING.md](SCRIPTING.md)。

## 22. 连接诊断

```text
ping
info
native
echo [text ...]
version
```

- `ping`：验证目标响应。
- `info`：显示 JVM、Agent 和会话信息。
- `native`：显示目标 JNI/JVMTI 原生桥状态。
- `echo`：验证命令参数编码和传输。
- `version`：比较控制端、目标 Agent 和协议版本。

## 23. JVM descriptor 速查

| Java 类型 | descriptor |
|---|---|
| `void` | `V` |
| `boolean` | `Z` |
| `byte` | `B` |
| `char` | `C` |
| `short` | `S` |
| `int` | `I` |
| `long` | `J` |
| `float` | `F` |
| `double` | `D` |
| `java.lang.String` | `Ljava/lang/String;` |
| `byte[]` | `[B` |
| `java.lang.String[]` | `[Ljava/lang/String;` |

方法 descriptor 格式是 `(<参数...>)<返回值>`：

```text
()V
(I)Ljava/lang/String;
(Ljava/lang/String;IF)J
```

可以先用 `class methods` 或 `find method` 获取准确 descriptor，再用于 `invoke`。

## 24. 常见错误

- `No context selected`：先使用 `context class ...` 或 `context static field ...`。
- `Current context is a class, not an object`：该命令需要实例接收者。
- `Method/Field was not found`：检查声明类、static/virtual 模式和 descriptor。
- 父类同名成员不明确：使用 `com.example.Parent::member`。
- `limit must be between 1 and 10000`：缩小或修正 limit。
- 找不到预期类：JVMRTDP 只搜索当前已经加载的类。
- 参数包含空格：使用双引号包住完整 token。
