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

双引号和反斜杠可用 `\"`、`\\` 转义。花括号把包含空格的值表达式组成一个参数，表达式可以嵌套。

命令顶层未加引号、且不在 `{...}` 内的 `->` 是临时引用链分隔符：

```text
context class com.example.App -> static field INSTANCE -> field service -> value
```

每个中间段解析出的 context 只供下一段使用，中间输出被抑制；链结束或抛错时，进入链前的 current
context、完整 context 栈和书签都会恢复。因此 `->` 不会留下导航状态。链内 `invoke`、`set` 等对目标
程序造成的副作用不会回滚。

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
| `resolve ...` | 否 | 求值并打印字面量、引用或 `{...}` 表达式 |
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

`invoke`、`construct`、`set`、`static invoke/set`、`array set`、`context value` 和 `code run`
使用同一套参数语法：

```text
null
true | false
byte:1 | short:2 | int:3 | long:4
float:1.5 | double:2.5
char:x
string:text | str:text
bytes:<base64>
class:<loaded-class>             # 目标 JVM 中的 java.lang.Class 对象
enum:<enum-class>:<constant>     # 目标 JVM 中的枚举常量
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

### 复合值表达式

`{...}` 在参数位置即时解析出一个远程对象，不会选择为 current context，也不会压入 context 栈。
表达式生成的临时 handle 在外层命令完成后释放。支持以下原子形式：

```text
{new <class> <descriptor|auto> [arguments ...]}
{construct <class> <descriptor|auto> [arguments ...]}
{invoke <receiver> [declaring.Class::]<method> <descriptor> [arguments ...]}
{call <receiver> [declaring.Class::]<method> <descriptor> [arguments ...]}
{static <class> <method> <descriptor> [arguments ...]}
{static invoke <class> <method> <descriptor> [arguments ...]}
{field <receiver> [declaring.Class::]<field[index]>}
{static-field <class> [declaring.Class::]<field[index]>}
{index <array-receiver> <index>}
```

`receiver` 可以是 `this`/`context`、`$name`/`@name`，也可以是另一段 `{...}`。参数同样可以递归嵌套：

```text
invoke install (Lcom/example/Service;)V {new com.example.Service (Lcom/example/Config;)V {static com.example.Config load ()Lcom/example/Config;}}
set service {invoke $factory create (Ljava/lang/String;)Lcom/example/Service; string:main}
static set com.example.Registry DEFAULT {new com.example.Service auto string:main}
```

表达式内部也支持一次性 `->` 引用链。根可以是任意对象表达式，或 `type <class>`；后续步骤支持
`field`、`invoke`/`call`、`index`、`as`、`runtime`，类型根还支持 `construct`。类型接收者上的
`field`/`invoke` 自动执行静态操作：

```text
{context -> field service -> invoke status ()Ljava/lang/String;}
{type com.example.Registry -> field DEFAULT -> field owner}
{type com.example.Service -> construct (Ljava/lang/String;)V string:main -> invoke start ()Lcom/example/Service;}
```

复合表达式必须产生非 `void` 对象；`void` 调用不能作为参数。需要单独查看表达式结果时使用：

```text
resolve <literal|reference|{value-expression}>
ref {static com.example.Services create ()Lcom/example/Service;}
eval {context -> field service -> field name}
```

`resolve`、`ref`、`eval` 等价，均只打印值和类型，不改变 context 或栈。

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
set owner {new com.example.Owner ()V}
set service {static com.example.Services create ()Lcom/example/Service;}
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
static invoke com.example.Tools use (Lcom/example/Config;)V {static-field com.example.Config DEFAULT}
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
invoke install (Lcom/example/Service;)V {new com.example.Service auto string:main}
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
construct com.example.Controller (Lcom/example/Config;)V {static com.example.Config load ()Lcom/example/Config;}
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

导出临时引用链的输出时，把整条链放在一个引号参数中；否则 `export` 外层会先执行 `->`：

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

## 21. Java 代码部署与 JVMTI

### 部署源码、项目或方法片段

```text
code source <name> <file.java|source-directory> [options]
code methods <name> <binary-class-name> <methods-file> [options]
```

`source` 会在控制端使用 JDK compiler 编译一个 `.java` 文件，或递归编译目录中的全部 `.java` 文件；因此目录可以是一个
简单源码项目的 source root。`methods` 文件只写字段/方法声明，JVMRTDP 会生成指定类的 package 和 class 外壳。

```text
code source hooks D:\hooks\src --classpath D:\app\api.jar --release 8
code methods utilities demo.InjectedMethods D:\hooks\methods.java --anchor com.example.App --same-loader
```

通用选项：

- `--anchor <loaded-class>`：使用该已加载类的 ClassLoader 作为目标 loader；默认 system loader。
- `--child`：在独立的 child-first loader 中定义，默认且最容易关闭。
- `--same-loader`：通过 JNI `DefineClass` 直接定义到 anchor 的 loader；定义后 JVM 无法卸载或撤销。
- `--classpath <paths>`：控制端 javac class path，多个路径使用本机 path separator。
- `--javac <option>`：追加一个 javac token，可重复。
- `--release <n>`、`-source <n>`、`-target <n>`：编译目标版本；默认 Java 8。

编译结果与 JAR 使用 512 KiB 分块传输，目标端校验声明长度和 SHA-256；单次 bundle/JAR 上限为 256 MiB，
不受单条命令 8 MiB 字符串上限约束。

### 部署 JAR / AddToClassLoaderSearch

```text
code jar <name> <file.jar> [--scope child|system|bootstrap] [--anchor <loaded-class>]
```

- `child`：为 JAR 建立独立 loader，可通过 `code close` 关闭。
- `system`：调用 JVMTI `AddToSystemClassLoaderSearch`。
- `bootstrap`：调用 JVMTI `AddToBootstrapClassLoaderSearch`。

system/bootstrap search path 无 JVMTI 撤销 API；关闭 deployment 只释放 JVMRTDP 的记录，已经加入的路径持续到目标 JVM 退出。

### 调用与生命周期

```text
code list
code run <deployment-id> <class> <method> <descriptor> static [arguments ...]
code run <deployment-id> <class> <method> <descriptor> <receiver> [arguments ...]
code close <deployment-id>
```

`code run` 的参数使用第 8 节的完整远程参数语法（包括 `{new ...}`、`{invoke ...}`、`{static ...}`
等复合表达式）。结果成为当前 context。
关闭 deployment 会自动注销属于它的 callback；child loader 会关闭，same-loader/system/bootstrap 的 JVM 级变更无法回滚。

### Java JVMTI callback 与 transformer

部署代码可以实现以下接口：

```java
import nhcm.jvmrtdp.api.jvmti.*;

public final class Hook implements JvmtiEventHandler, JvmtiClassFileTransformer {
    public void onEvent(JvmtiEvent event) {
        System.out.println(event.type() + " " + event.className()
                + " " + event.methodName() + event.methodDescriptor());
    }

    public byte[] transform(JvmtiClassFileEvent event) {
        // null 表示保留当前 bytes；也可以返回完整的 CA FE BA BE class 文件。
        return null;
    }
}
```

也可以实现 `JvmtiCategorizedEventHandler`，分别覆盖 `onVmEvent`、`onThreadEvent`、
`onClassEvent`、`onExecutionEvent`、`onMethodEvent`、`onFieldEvent`、`onExceptionEvent`、
`onMonitorEvent`、`onNativeCodeEvent`、`onHeapEvent`、`onGarbageCollectionEvent` 和
`onResourceEvent`，不需要在单一 `onEvent` 中自行分派。

`method_entry` 和 `method_exit` 会产生 `JvmtiMethodEvent`。它额外提供 receiver、静态/native
标记、按 descriptor 和 JVM slot 排列的 `JvmtiMethodArgument`、异常 pop 标记以及装箱后的返回值：

```java
public final class MethodHook implements JvmtiCategorizedEventHandler {
    @Override
    public void onMethodEvent(JvmtiMethodEvent event) {
        System.out.println(event.className() + "." + event.methodName());
        for (JvmtiMethodArgument argument : event.arguments()) {
            System.out.println(argument.slot() + " " + argument.descriptor()
                    + " " + (argument.available() ? argument.value() : argument.error()));
        }
    }
}
```

参数值不依赖 `LocalVariableTable`；缺少 `-g` 调试信息时只有参数名为 null。`long`/`double`
占两个 slot，实例方法 slot 0 为 receiver。native/opaque frame、缺少
`can_access_local_variables` 或 VM 无法物化栈帧时，每个参数的 `available()` 为 false，
`error()` 保存对应 JVMTI 错误。

注册与管理：

```text
code callback add <deployment-id> <handler-class> <event,event,...> [sync|async]
code callback remove <callback-id>
code callback list
code callback stats
```

handler 必须有无参数构造器。`sync` 在产生事件的线程上调用；`async` 使用有界队列，适合只观察事件。
ClassFile transformer 为保证能返回替换 bytes 始终同步执行，并按照 callback 注册顺序串联。统计包含 Java handler 的
delivered/failed，以及无 `JNIEnv` 原生事件转发队列的 queued/dropped/depth。

完整事件名：

```text
vm_init, vm_death, vm_start, thread_start, thread_end,
class_file_load_hook, class_load, class_prepare,
single_step, frame_pop, breakpoint, field_access, field_modification,
method_entry, method_exit, exception, exception_catch, native_method_bind,
compiled_method_load, compiled_method_unload, dynamic_code_generated, data_dump_request,
monitor_wait, monitor_waited, monitor_contended_enter, monitor_contended_entered,
resource_exhausted, garbage_collection_start, garbage_collection_finish,
object_free, vm_object_alloc
```

`JvmtiEvent` 提供 type/timestamp/thread/class/method/descriptor/location/subject/value，以及 related method、field member、
secondary subject 和 text 等事件附加参数。`JvmtiClassFileEvent` 另外提供 loader、重定义中的 Class、ProtectionDomain 和
当前 class bytes。

动态注入在 JVMTI live phase 建立环境；实际可用事件取决于 `jvmti capabilities`。JVM 不允许 live phase 获取的启动期
capability 会明确指出所需 capability。要获得 MethodEntry/MethodExit 等 OnLoad-only capability，使用启动模式并把
JVMRTDP JAR 放入 system class path：

Windows x64 控制端使用 Manual Map 和 `NtCreateThreadEx` 装入 bootstrap DLL；进入目标进程后仍通过
`JNI_GetCreatedJavaVMs`、`AttachCurrentThread` 和 `JavaVM.GetEnv(JVMTI_VERSION_1_2)` 接入现有 JVM。
Manual Map 只改变 DLL 进入目标进程的方式，不改变上述 live-phase capability 限制。

```text
java -agentpath:C:\path\jvmrtdp-agent.dll -cp JVMRTDP.jar;application.jar application.Main
```

原生 agent 在 `Agent_OnLoad` 请求该 VM 当时提供的全部 JVMTI 1.2 capability，并在 `VMInit` 绑定 Java dispatcher。

### 其他 JVMTI 操作

```text
jvmti capabilities
jvmti capability-status
jvmti capability <add|relinquish> <capability...>
jvmti phase
jvmti time
jvmti timer-info
jvmti current-thread-cpu-time
jvmti processors
jvmti location-format
jvmti property <get|set> <name> [value]
jvmti verbose <other|gc|class|jni> <enable|disable>
jvmti class <info|interfaces|loader-classes|source-debug|constant-pool> <class>
jvmti method <info|bytecodes|lines> <class> <method> <descriptor>
jvmti field info <class> <field> <descriptor>
jvmti events
jvmti events generate <compiled_method_load|dynamic_code_generated|data_dump_request>
jvmti retransform <class>
jvmti redefine <class> <class-file>
jvmti breakpoint <set|clear> <class> <method> <descriptor> <location>
jvmti watch <access|modification> <set|clear> <class> <field> <descriptor>
jvmti threads [variable-prefix] [limit]
jvmti thread <info|state|stack|frame-count|cpu-time|owned-monitors|contended-monitor|suspend|resume|interrupt|frame-pop> <thread-object> [depth|max]
jvmti size <object>
jvmti hash <object>
jvmti monitor-usage <object>
jvmti tag <object> [new-value]
jvmti tagged <tag> [variable-prefix] [limit]
jvmti gc
jvmti properties
```

`capability add` 调用真正的 `AddCapabilities`，不会篡改 HotSpot 内存位。若 capability 在当前 phase
不是 potential，会返回 `JVMTI_ERROR_NOT_AVAILABLE`、当前 phase 和启动期加载建议。

`method bytecodes`、`class constant-pool` 等命令需要对应 capability；库式 API
`RemoteJVMTIEnv.methodBytecodes()` / `constantPool()` 返回原始字节。交互命令只展示长度或适合终端的内容，
避免把很大的二进制数据直接刷到屏幕。

`jvmti threads` 把保留的线程保存为 `$prefix0`、`$prefix1`……远程对象。挂起线程可能冻结应用；JVMRTDP 拒绝挂起
当前命令处理线程。断点、field watch、single-step、frame-pop 等操作也要求对应 capability。

## 22. 批处理与脚本

### 批处理

```text
batch <commands.txt>
```

UTF-8 文件每个非空、非 `#` 注释行是一条交互命令。行间共享 context，行内可以使用临时 `->` 引用链。
遇到会话关闭命令或异常时停止。

### 流程脚本

```text
script <file.jrd>
```

支持命名 handle、`if`、`ifnull`、`switch`、`goto`、`print`、`export`、`release` 和
`command` 嵌入交互命令。完整语法见 [SCRIPTING.md](SCRIPTING.md)。

## 23. 连接诊断

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

## 24. JVM descriptor 速查

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

## 25. 常见错误

- `No context selected`：先使用 `context class ...` 或 `context static field ...`。
- `Current context is a class, not an object`：该命令需要实例接收者。
- `Method/Field was not found`：检查声明类、static/virtual 模式和 descriptor。
- 父类同名成员不明确：使用 `com.example.Parent::member`。
- `limit must be between 1 and 10000`：缩小或修正 limit。
- 找不到预期类：JVMRTDP 只搜索当前已经加载的类。
- 参数包含空格：使用双引号包住完整 token。
