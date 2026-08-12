# JVMRTDP 脚本指南

[English](SCRIPTING.md) | [中文](SCRIPTING_ZH.md)

JVMRTDP 支持顺序执行命令的批处理文件，以及包含变量和分支的 `.jrd` 工作流脚本。

## 1. 运行文件

```text
batch commands.txt
script workflow.jrd
```

| 方式 | 用途 |
| --- | --- |
| `batch` | 每行执行一条普通目标命令 |
| `script` | 执行类型化变量、调用、分支、导出和嵌入命令 |

两种格式均使用 UTF-8。空行和以 `#` 开头的行会被忽略，不支持行尾注释。

## 2. 引号

含空格的参数使用双引号。支持 `\n`、`\r`、`\t`、`\\` 和 `\"`。

```text
value message string "hello world"
print @message
```

指令名使用小写；变量名和标签名区分大小写。

## 3. 工作区引用

工作区保存：

- `$name`：类引用
- `@name`：对象或装箱基本值引用

定义变量时不需要前缀：

```text
class service com.example.Service
value count int 3
```

对象句柄通常会在目标 JVM 中持有强引用。不再使用对象时应执行 `release`。工作区值会保留到被覆盖、释放或会话关闭。

## 4. 指令

| 指令 | 语法 |
| --- | --- |
| 类 | `class <name> <binary-class-name>` |
| 值 | `value <name> <type> <literal>` |
| 构造 | `construct <name> <class-ref> <descriptor> [args...]` |
| 调用 | `call <dest> <receiver> <method> <descriptor> [args...]` |
| 读取 | `get <dest> <receiver> <field>` |
| 写入 | `set <receiver> <field> <value-ref>` |
| 输出 | `print <token...>` |
| 导出 | `export <class|value> <ref> <file>` |
| 导出上下文 | `export context <file>` |
| 条件 | `if <ref> goto <label>` |
| 空值判断 | `ifnull <ref> goto <label>` |
| 分支 | `switch <ref> <value=label...> [default=label]` |
| 跳转 | `goto <label>` |
| 普通命令 | `command <target-command...>` |
| 释放 | `release <object-ref...>` |

标签以冒号开头：

```text
:success
```

## 5. 值

支持的值类型：

```text
null string boolean byte short int long float double char bytes
```

`bytes` 使用 Base64 文本；`char` 必须正好包含一个字符。

```text
value empty null
value enabled boolean true
value retries int 3
value timeout long 5000
value letter char A
value payload bytes SGVsbG8=
```

## 6. 构造与调用

构造对象：

```text
class list java.util.ArrayList
construct items $list "()V"
```

调用实例方法：

```text
call count @items size "()I"
```

使用 `static:<class-ref>` 调用静态方法：

```text
class integer java.lang.Integer
value number int 42
call text static:$integer toString "(I)Ljava/lang/String;" @number
```

使用 `DeclaringClass::member` 指定父类或接口声明：

```text
call text @target java.lang.Object::toString "()Ljava/lang/String;"
```

脚本中的 `construct` 和 `call` 必须提供描述符，所有参数都必须是工作区对象引用。

## 7. 字段

实例字段：

```text
get current @service state
set @service state @next
```

静态字段使用 `static:<class-ref>`：

```text
get current static:$service INSTANCE
set static:$service ENABLED @enabled
```

继承字段可写为 `DeclaringClass::field`。

## 8. 输出与导出

`print` 接受变量和普通文本：

```text
print "count=" @count
```

导出类文件：

```text
export class $service output/Service.class
```

导出对象的显示值：

```text
export value @service output/service.txt
export context output/current.txt
```

结构化调试数据使用普通快照命令：

```text
command debugger snapshot output/debugger.json json
```

脚本也能通过同一 CLI 命令安装包含多个操作的原子字节码补丁：

```text
command bytecode patch-file com.example.Service patches/service.patch
```

补丁文件自身是一个类事务；外层脚本不是事务。后续脚本操作失败时，可显式执行 `bytecode undo com.example.Service`。

## 9. 分支

```text
call valid @service isValid "()Z"
if @valid goto success
goto failed

:success
print "valid"
goto done

:failed
print "invalid"

:done
```

`if` 将空值、空文本、`false`、`0` 和文本 `null` 视为假。`ifnull` 只判断真正的空对象。

`switch` 对显示值进行精确比较：

```text
switch @status READY=ready ERROR=failed default=unknown
```

标签必须唯一且存在。单次脚本最多执行 100,000 条指令。

## 10. 普通命令

`command` 可在脚本中使用 CLI 功能：

```text
command debugger status
command debugger freeze
command debugger snapshot output/debugger.json json
command debugger thaw
command jvmti capabilities
```

如果命令要求关闭会话，脚本会失败，不会继续使用失效句柄。

## 11. 释放对象

```text
release @items @service
```

`release` 会删除对象变量并关闭远程句柄。类引用在会话关闭时清理。

## 12. 完整示例

```text
# 创建列表、加入两个值并输出大小。
class list java.util.ArrayList
construct items $list "()V"

value first string "alpha"
value second string "beta"

call added @items add "(Ljava/lang/Object;)Z" @first
call added @items add "(Ljava/lang/Object;)Z" @second
call count @items size "()I"

print "size=" @count
export value @items output/items.txt
release @items @first @second @added @count
```

## 13. 通过 Java 库运行脚本

已连接的库会话可以执行相同的脚本命令并捕获结果：

```java
JvmRtdpCommandResult result = session.execute("script workflow.jrd");
if (!result.successful()) {
    throw new IllegalStateException(result.failureMessage());
}
System.out.print(result.standardOutput());
```

脚本中的 `command` 指令通过同一个持久会话上下文运行。远程变量会保留到被替换、释放或会话关闭。

需要机器可读的调试数据时，可在脚本或 Java API 中使用：

```text
command debugger snapshot output/debugger.json json
```

会话所有权和线程安全规则见 [Java 库指南](LIBRARY_ZH.md)。

## 14. 错误语义

- 解析或执行遇到第一个错误时停止。
- 解析错误包含源文件行号。
- 脚本不提供事务或自动回滚，已完成的目标修改会保留。
- 阻塞方法会阻塞当前脚本请求，直至方法返回。
- 未知变量、描述符、标签、字段或方法会使当前执行失败。

涉及字段写入、应用方法调用、线程暂停或类操作的脚本，应先在测试环境中验证。
