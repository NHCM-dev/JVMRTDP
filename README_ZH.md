# JVMRTDP

[English](README.md) | [中文](README_ZH.md)

JVMRTDP 是面向 Windows x64 HotSpot Java 虚拟机（JVM）的诊断、分析与调试工具。它将代理注入目标 JVM，并通过终端用户界面（TUI）、命令行和脚本提供类浏览、对象检查、反编译、字节码查看、断点、线程控制和 Java 虚拟机工具接口（JVMTI）操作。

> 仅在获得授权的进程中使用本工具。调试、字段写入、方法调用和类重定义会改变目标 JVM 的状态。

## 功能概览

- 扫描并连接本机 Java 进程，包括嵌入式 JVM。
- 按包、类、字段、方法、对象、调用栈和静态上下文浏览运行时状态。
- 读取和修改字段、数组与集合，调用方法和构造器。
- 使用 CFR 或 Procyon 反编译类和单个方法。
- 查看 JVM 字节码、字节码索引（BCI）、源码行号、常量池引用和方法元数据。
- 管理断点、字段访问/修改监视点、单步执行和多线程暂停状态。
- 查看线程、栈帧、局部变量、监视器、标签、对象大小和 JVMTI 属性。
- 将类、对象、字节码、反编译结果和调试快照导出为文件。
- 通过批处理、脚本和结构化输出集成自动化工具。

## 系统要求

- Windows x64
- Java 8 或更高版本
- 从源码构建原生组件时需要 Visual Studio 2022 C++ 工具链
- 完整构建默认需要以下相邻源码目录：
  - `../cfr`
  - `../procyon`

控制端、目标 JVM 和原生代理必须使用兼容的架构。当前原生构建目标为 x64。

## 构建

```powershell
.\gradlew.bat build
```

构建产物位于：

```text
build/libs/JVMRTDP-2.0.0.jar
build/libs/jvmrtdp-2.0.0-library.jar
build/native-output/agent/x64/Release/jvmrtdp-agent-build.dll
```

`JVMRTDP-2.0.0.jar` 是自包含可执行程序；`jvmrtdp-2.0.0-library.jar` 是适合作为依赖的库产物，终端相关依赖保持外置。

如需将代理 DLL 发布到传统目录：

```powershell
.\gradlew.bat publishAgentNative
```

发布位置：

```text
natives/x64/Release/jvmrtdp-agent.dll
```

JAR 中包含的代理和目标 JVM 预加载的 DLL 应来自同一次构建。更新 DLL 后需要重启目标 JVM。

## 快速开始

启动默认 TUI：

```powershell
java -jar build\libs\JVMRTDP-2.0.0.jar
```

启动命令行模式：

```powershell
java -jar build\libs\JVMRTDP-2.0.0.jar --cli
```

命令行基本流程：

```text
jvmrtdp> ps
jvmrtdp> attach <pid>
target[<pid>|<unset>]> context class com.example.Application
target[<pid>|com.example.Application]> class methods
```

也可以在 TUI 中选择进程并按 `Enter` 连接。耗时操作会在后台执行，界面保持可响应。

## 启动时加载代理

某些 JVMTI capability 只能在 JVM 启动阶段获取。需要完整调试能力时，建议使用 `-agentpath` 启动目标：

```powershell
java -agentpath:<path-to-jvmrtdp-agent.dll> -jar application.jar
```

在 `main` 入口暂停：

```powershell
java -agentpath:<path-to-jvmrtdp-agent.dll>=break-main=com.example.Application -jar application.jar
```

在类初始化器入口暂停：

```powershell
java -agentpath:<path-to-jvmrtdp-agent.dll>=break-clinit=com.example.Component -jar application.jar
```

多个启动选项使用逗号分隔：

```powershell
java -agentpath:<path-to-jvmrtdp-agent.dll>=break-main=com.example.Application,break-clinit=com.example.Component -jar application.jar
```

类名可使用点号或 JVM 内部斜杠格式。

## TUI 导航

TUI 默认按包浏览类，并隐藏常见 JDK 内部类型。页脚会根据当前视图显示可用操作。

| 按键 | 操作 |
| --- | --- |
| `↑` / `↓` | 移动选择 |
| `PgUp` / `PgDn` | 翻页 |
| `Home` / `End` | 跳到首项或末项 |
| `Tab` | 切换视图 |
| `Enter` | 打开当前项 |
| `Backspace` | 返回上级上下文或包 |
| `←` / `→` | 水平滚动 |
| `[` / `]` | 快速水平滚动 |
| `/` | 过滤当前列表；`Esc` 取消 |
| `F` | 全局查找类、字段或方法 |
| `P` | 输入包名 |
| `J` | 显示或隐藏 JDK 类型 |
| `A` | 显示或隐藏数组类型 |
| `K` | 显示或隐藏 `<init>` / `<clinit>` |
| `F2` | 切换到 CLI |
| `Q` | 返回或退出 |

主要视图：

- `browse`：包和类浏览
- `context`：当前类、对象、静态值或栈上下文
- `fields` / `methods`：成员浏览与操作
- `decompile`：类或方法反编译结果
- `bytecode`：BCI、行号和指令流
- `debug`：当前停止位置、线程、栈和局部变量
- `frames` / `locals`：栈帧与局部变量
- `breakpoints`：断点管理
- `threads`：所有 JVM 线程及其状态

## 反编译与字节码

```text
decompile class com.example.Application --engine cfr
decompile method com.example.Application main "([Ljava/lang/String;)V" --engine procyon
bytecode com.example.Application main "([Ljava/lang/String;)V"
```

反编译和字节码视图支持搜索、水平滚动、定位行号或 BCI、设置断点和导出。原生方法与抽象方法没有 JVM `Code` 属性，因此不会显示 Java 字节码。

## 调试

设置并查看断点：

```text
debugger enable
debugger break com.example.Application run "()V" 0
debugger breakpoints
```

查看和控制线程：

```text
debugger threads
debugger pause <thread-index>
debugger current <paused-index> 0 12
debugger locals <paused-index> 0
debugger step <paused-index>
debugger continue <paused-index>
```

临时采样正在运行的线程：

```text
debugger sample <thread-index> 0 12
```

整体分析冻结：

```text
debugger freeze
debugger snapshot debugger-snapshot.json json
debugger thaw
```

`freeze` 会排除代理服务线程等敏感线程，并仅恢复由本次冻结暂停的线程。

TUI 中 `F8` 继续当前线程；目标运行时，调试视图会定期短暂停线程以刷新当前方法、BCI、栈和局部变量。该行为可以通过 `F4` 关闭。

## JVMTI 能力

动态注入发生在 JVMTI `LIVE` 阶段。此时只能获取 JVM 仍声明为 potential 的 capability。JVM 启动后，某些仅限 `OnLoad` 阶段的能力不能通过标准 JVMTI 强制开启。

查看状态：

```text
jvmti phase
jvmti capabilities
jvmti capability-status
jvmti capability add can_generate_method_entry_events
```

如果 capability 不再 potential，命令会报告失败。推荐通过 `-agentpath` 在启动阶段加载代理，不修改 HotSpot 内部 capability 表。

JVMTI 可以读取栈帧位置和局部变量，但标准接口不提供当前 JVM 操作数栈内容。字节码中的 `maxStack` 仅是类文件声明的最大深度。

## 自动化

批处理直接执行目标命令：

```text
batch commands.txt
```

脚本语言提供变量、标签、条件分支、对象引用和导出：

```text
script workflow.jrd
```

详细语法见[脚本指南](docs/SCRIPTING_ZH.md)。需要供其他程序读取调试数据时，使用 `json` 或 `jsonl` 格式的调试器快照。

## Java 库

将当前构建发布到本地 Maven 仓库：

```powershell
.\gradlew.bat publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
dependencies { implementation("nhcm.jvmrtdp:jvmrtdp:2.0.0") }
```

在 Java 中发现并连接 JVM：

```java
import nhcm.jvmrtdp.api.JvmRtdpClient;
import nhcm.jvmrtdp.api.JvmRtdpCommandResult;
import nhcm.jvmrtdp.api.JvmRtdpSession;

try (JvmRtdpClient client = JvmRtdpClient.open();
     JvmRtdpSession session = client.attach(pid)) {
    JvmRtdpCommandResult result = session.execute("jvmti phase").requireSuccess();
    System.out.print(result.standardOutput());
}
```

库 API 提供进程发现、可配置连接、命令结果捕获、异步 agent 命令，以及 JNI、JVMTI、上下文、远程对象、反编译和调试器服务的直接访问。详见 [Java 库指南](docs/LIBRARY_ZH.md)。

## 安全与行为说明

- 代理仅监听回环地址，并为每次会话生成随机令牌。
- 调试、暂停线程、调用方法、修改字段、触发 GC 和类重定义会影响目标程序。
- 对象句柄通常是强引用；长时间会话应及时释放不再使用的句柄。
- 类重定义受 HotSpot schema 限制，不支持任意增加或删除字段和方法。
- 对阻塞方法进行远程调用会阻塞对应请求，直至调用返回。
- 生产环境使用前应先在等价测试环境验证操作和 capability。

## 文档

- [命令参考](docs/COMMANDS_ZH.md)
- [脚本指南](docs/SCRIPTING_ZH.md)
- [JVMTI API 覆盖范围](docs/JVMTI-API-COVERAGE_ZH.md)
- [Java 库指南](docs/LIBRARY_ZH.md)
