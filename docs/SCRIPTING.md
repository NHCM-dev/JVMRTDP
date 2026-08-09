# JVMRTDP 脚本语言手册

JVMRTDP 提供两种 UTF-8 自动化文件：

| 类型 | 启动命令 | 用途 |
|---|---|---|
| 命令批处理 | `batch commands.txt` | 逐行执行交互命令，适合线性操作 |
| 流程脚本 | `script flow.jrd` | 命名远程 handle、分支、跳转、输出和文件导出 |

本文主要描述 `.jrd` 流程脚本。

## 1. 运行脚本

先 attach 目标 JVM，然后：

```text
script path/to/flow.jrd
```

路径由控制端解析；相对路径相对于启动 JVMRTDP 时的工作目录。

脚本以 UTF-8 读取。空行和去除前后空白后以 `#` 开头的整行注释会被忽略：

```text
# 这是注释
print "script started"
```

当前没有行尾注释语法。以下 `#` 会成为 print 参数，而不是注释：

```text
print value # not-an-inline-comment
```

## 2. Token、引号和转义

脚本和交互 CLI 使用相同的 token 解析器：

- 空白分隔 token；
- 双引号包住含空格的 token；
- `\"` 表示双引号；
- `\\` 表示反斜杠；
- 未闭合引号会在解析阶段报出脚本行号。

```text
value message string "hello world"
print "message:" $message
```

引号只负责把文本保留为一个 token，不执行字符串内部插值。解析后仍以 `$`/`@` 开头的整个 token
仍然会被识别为变量引用：

```text
print "$message"          # 与 print $message 相同，输出变量值
print "value=$message"    # 输出字面文本 value=$message，不做内部插值
```

## 3. Workspace 和变量

脚本 workspace 分别保存：

- 命名类 handle；
- 命名对象 handle。

定义变量时通常使用不带前缀的名字，引用时推荐使用 `$name`：

```text
class userClass com.example.User
value name string Ada
construct user $userClass (Ljava/lang/String;)V $name
```

`$name` 和 `@name` 对脚本 workspace 是等价引用。变量名内部按去掉一个 `$`/`@` 前缀后的文本保存。

同名对象变量被重新定义时，旧远程对象 handle 会立即释放。会话关闭时，workspace 中剩余对象会全部释放。

类变量不需要 release；`release` 只接受对象变量。

## 4. 脚本与交互 Context 的关系

Workspace 对象变量和交互 context 是两套不同的引用机制，但共享同一个目标会话：

- handle 指令（`construct`、`call`、`get`、`set`）主要使用 `$variable`；
- `command ...` 执行完整的交互式 context 命令；
- `context` 关键字在 `print`、`if`、`ifnull`、`export` 中指当前交互 context；
- 普通 `command` 改变的 context 会被后续脚本指令看到；含顶层 `->` 的命令使用临时引用链，结束后恢复原 context、栈和书签；
- 脚本创建的 `$object` 可以作为后续交互命令参数。

```text
command context class com.example.App
command static field INSTANCE
print "current:" context
```

Context 书签（`context save/use`）与 workspace 对象变量不是同一张表。

## 5. 指令总览

```text
class <variable> <class-name>
value <variable> <type> <literal>
value <variable> null
construct <variable> <class-reference> <descriptor> [object-references ...]
call <variable> <receiver-reference> <method> <descriptor> [object-references ...]
get <variable> <receiver-reference> <field>
set <receiver-reference> <field> <value-reference>
print <literal-or-reference> [...]
export class <class-reference> <file>
export value <object-reference> <file>
export context <file>
if <object-reference|context> goto <label>
ifnull <object-reference|context> goto <label>
switch <literal-or-reference|context> <value=label> [...] [default=label]
goto <label>
command <interactive-command> [arguments ...]
release <object-reference> [...]
:<label>
```

## 6. `class`：定义类 handle

```text
class <variable> <class-name>
```

```text
class userClass com.example.User
class tools java.util.Objects
```

只查找目标 JVM 中已经加载的类。定义本身不会主动加载新业务类。

类引用的位置既可以使用 `$userClass`，也可以直接写完整类名。

## 7. `value`：创建目标端值

```text
value <variable> <type> <literal>
value <variable> null
```

支持类型：

```text
string
boolean
byte
short
int
long
float
double
char
bytes
null
```

`bytes` 的 literal 是 Base64。

```text
value nothing null
value enabled boolean true
value count int 7
value ratio float 1.5
value letter char A
value name string "Ada Lovelace"
value payload bytes AQIDBA==
```

与交互命令不同，脚本 handle 指令的参数不会自动把 `int:7` 创建为临时值。应先通过 `value` 定义对象变量，
再把 `$variable` 传给 `construct`、`call` 或 `set`。

## 8. `construct`：构造对象

```text
construct <result-variable> <class-reference> <descriptor> [argument-references ...]
```

```text
class userClass com.example.User
value name string Ada
value age int 37
construct user $userClass (Ljava/lang/String;I)V $name $age
```

参数必须是 workspace 对象引用，顺序必须与 descriptor 一致。构造结果保存在对象变量中。

流程脚本的 handle 级 `construct` 当前要求准确 descriptor；要使用交互式 `auto` 选择，可写：

```text
command construct com.example.User auto string:Ada int:37
```

## 9. `call`：调用方法

```text
call <result-variable> <receiver-reference> <method> <descriptor> [argument-references ...]
```

### 实例方法

```text
value prefix string "Hello "
call greeting $user greet (Ljava/lang/String;)Ljava/lang/String; $prefix
```

### 静态方法

静态 receiver 使用 `static:<class-reference>`：

```text
class tools com.example.Tools
value input string 42
call parsed static:$tools parse (Ljava/lang/String;)I $input
```

也可以直接使用类名：

```text
call now static:java.lang.System currentTimeMillis ()J
```

### 指定父类实现

```text
call result $child com.example.Parent::run ()Ljava/lang/String;
```

不带声明类时按 Java 虚分派调用；`Parent::method` 调用精确声明类实现。

方法结果总是保存到 result variable，包括 void 的远程占位结果。若不再需要应 `release`。

## 10. `get`：读取字段到变量

```text
get <result-variable> <receiver-reference> [declaring.Class::]<field>
```

实例字段：

```text
get userName $user name
get parentCount $child com.example.Parent::count
```

静态字段：

```text
class state com.example.State
get current static:$state CURRENT
get enabled static:com.example.Config ENABLED
```

与交互命令 `read` 不同，脚本 `get` 会把读取结果作为强引用保存到 workspace，直到覆盖、release 或会话关闭。

## 11. `set`：写入字段

```text
set <receiver-reference> [declaring.Class::]<field> <value-reference>
```

实例字段：

```text
value newName string Grace
set $user name $newName
set $child com.example.Parent::count $age
```

静态字段：

```text
value enabled boolean true
set static:com.example.Config ENABLED $enabled
```

接收者和值都必须是已经定义的对象引用。

## 12. `print`：输出

```text
print <literal-or-reference> [...]
```

所有 token 用一个空格连接并输出一行：

```text
print "user:" $user
print "current context:" context
print "plain literal"
```

规则：

- `$object` / `@object`：输出远程对象 `displayValue`；
- `$class` / `@class`：输出类名；
- `context`：对象 context 输出显示值，类 context 输出 context 描述；
- 其他 token：原样输出。

## 13. `export`：输出文件

### Dump 类

```text
export class <class-reference> <output.class>
```

```text
export class $userClass build/dump/User.class
```

通过 JVMTI 获取 class bytes 并写文件。

### 输出对象显示值

```text
export value <object-reference> <output-file>
export object <object-reference> <output-file>
export context <output-file>
```

```text
export value $greeting build/out/greeting.txt
export context build/out/current.txt
```

对象导出只写当前 `displayValue` 的 UTF-8 文本，不会序列化整个对象。父目录自动创建，文件默认覆盖。

如果需要捕获一条交互命令的完整输出，使用交互层 export：

```text
command export build/out/stats.txt stats
```

## 14. 标签与 `goto`

标签单独占一行：

```text
:start
print "running"
goto end

:unreachable
print "not printed"

:end
print "done"
```

语法：

```text
:<label>
goto <label>
```

空标签、重复标签或未知标签都会失败。标签名称区分大小写。

## 15. `if`：真假分支

```text
if <object-reference|context> goto <label>
```

条件为真时跳转，否则执行下一条指令。

对象按显示文本判断：

- null：假；
- 空字符串：假；
- `false`（不区分大小写）：假；
- `0`：假；
- `null`（不区分大小写）：假；
- 其他值：真。

```text
if $enabled goto enabled
print "disabled"
goto end

:enabled
print "enabled"

:end
```

`context` 用于 `if` 时必须是对象 context。

## 16. `ifnull`：null 分支

```text
ifnull <object-reference|context> goto <label>
```

只检查远程 null，不把空字符串、false 或 0 视为 null。

```text
get owner $user owner
ifnull $owner goto noOwner
print "owner:" $owner
goto end

:noOwner
print "owner is null"

:end
```

## 17. `switch`：精确文本分支

```text
switch <literal-or-reference|context> <match=label> [...] [default=label]
```

`switch` 使用 `printable` 显示文本进行区分大小写的精确匹配：

```text
switch $status READY=ready FAILED=failed default=unknown

:ready
print "ready"
goto end

:failed
print "failed"
goto end

:unknown
print "unknown status:" $status

:end
```

匹配文本包含空格时，将整个 `value=label` 作为一个引号 token：

```text
switch $status "WAITING FOR INPUT=waiting" default=unknown
```

没有匹配且没有 default 时继续执行下一条指令。

## 18. `command`：执行交互式命令

```text
command <target-command> [arguments ...]
```

它执行与 `target[pid|context]>` 完全相同的 context 命令：

```text
command context class com.example.App
command static field INSTANCE
command read status
command set enabled true
command value --deep 20
```

支持临时 `->` 引用链；链内的中间 context 仅供下一段使用，整行结束后不会改变脚本看到的 context 或栈：

```text
command context class com.example.App -> static field INSTANCE -> field service -> read status
```

交互命令的参数也支持可嵌套 `{...}` 值表达式，因此无需预先创建 workspace 变量就能直接构造或调用：

```text
command invoke install (Lcom/example/Service;)V {new com.example.Service ()V}
command set service {static com.example.Services create ()Lcom/example/Service;}
command resolve {context -> field service -> invoke status ()Ljava/lang/String;}
```

`command` 可以调用 `find`、`dump`、`export`、`stats` 等全部公开目标命令。

```text
command version
```

如果嵌入命令请求关闭会话（如 `back` 或 `exit`），脚本会报告错误并停止；不要在正常脚本中使用它们。

## 19. `release`：释放对象 handle

```text
release <object-reference> [...]
```

```text
release $name $age $greeting $user
```

释放后变量从 workspace 移除，再次引用会失败。类变量不使用 release。

建议在长脚本和循环中及时释放不再需要的对象，避免目标 JVM 被不必要的强引用保活。

## 20. 完整示例：命名 handle

```text
# 定义类型和值
class userClass com.example.User
value name string Ada
value age int 37

# 构造对象
construct user $userClass (Ljava/lang/String;I)V $name $age

# 读取和修改字段
get oldName $user name
print "old name:" $oldName

value newName string Grace
set $user name $newName

# 调用方法
value prefix string "Hello "
call greeting $user greet (Ljava/lang/String;)Ljava/lang/String; $prefix
print "greeting:" $greeting

# 文件输出
export value $greeting build/out/greeting.txt
export class $userClass build/dump/User.class

# 释放对象
release $oldName $greeting $prefix $newName $user $name $age
```

## 21. 完整示例：Context 流程

```text
command context class com.example.App
command static field INSTANCE

ifnull context goto missing
print "app:" context

command read status
command set enabled true
command field service
command invoke status ()Ljava/lang/String;

switch context READY=ready FAILED=failed default=unknown

:ready
print "service ready"
export context build/out/service-status.txt
goto end

:failed
print "service failed"
goto end

:unknown
print "unknown service status:" context
goto end

:missing
print "App.INSTANCE is null"

:end
command stats
```

## 22. 循环和执行上限

标签和 `goto` 可以构造循环：

```text
# 假定前面已经定义了 $worker
:poll
command read status
call status $worker status ()Ljava/lang/String;
if $status goto poll
```

脚本没有 `sleep` 指令；循环会立即运行，并可能给目标 JVM 带来很高负载。

每次脚本最多执行 100,000 条指令。超过限制会以错误终止，防止无界失控循环。

## 23. 错误处理

当前脚本是 fail-fast：

- 语法错误在执行前报告 `Script line N`；
- 找不到类、变量、字段、方法或标签时立即停止；
- descriptor 不匹配、类型不兼容或目标方法抛异常时立即停止；
- 文件写入失败时立即停止；
- 没有 try/catch 或忽略错误语法。

失败前已经完成的目标状态修改不会自动回滚。

## 24. Batch 与 Script 如何选择

使用 `batch`，如果任务只是按顺序复用交互命令：

```text
# commands.txt
context class com.example.App
static field INSTANCE
read status
set enabled true
value
```

使用 `script`，如果需要：

- 保存多个对象 handle；
- 重用调用参数；
- `if` / `ifnull` / `switch`；
- 标签和循环；
- 精确控制远程强引用的释放；
- 导出对象显示值或 class 文件。

两者都在当前 attach 会话内运行，并与当前 context 共享目标连接。
