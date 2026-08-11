# JVMRTDP Java 库指南

[English](LIBRARY.md) | [中文](LIBRARY_ZH.md)

JVMRTDP 可通过 `nhcm.jvmrtdp.api` 中的公开 API 嵌入其他 Java 应用。构建仍会同时生成供 CLI 和 TUI 使用的独立可执行 JAR。

## 1. 安装

将开发构建发布到本地 Maven 仓库：

```powershell
.\gradlew.bat publishToMavenLocal
```

Gradle：

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("nhcm.jvmrtdp:jvmrtdp:2.0.0")
}
```

Maven：

```xml
<dependency>
  <groupId>nhcm.jvmrtdp</groupId>
  <artifactId>jvmrtdp</artifactId>
  <version>2.0.0</version>
</dependency>
```

本地文件依赖使用 `build/libs/jvmrtdp-2.0.0-library.jar`。库产物包含 JVMRTDP 类、Windows x64 原生组件和反编译器实现。Maven/Gradle 元数据会提供 JLine 运行时依赖；使用文件依赖并调用终端控制类时，需要自行添加 JLine。`build/libs/JVMRTDP-2.0.0.jar` 仍是自包含可执行程序。

发布产物包括库 JAR、源码包、Javadoc 和 Maven POM。自动模块名为 `nhcm.jvmrtdp`。

可编译示例位于 [`examples/library/LibraryExample.java`](../examples/library/LibraryExample.java)。

## 2. 发现进程

```java
import nhcm.jvmrtdp.api.JvmProcessInfo;
import nhcm.jvmrtdp.api.JvmRtdpClient;

try (JvmRtdpClient client = JvmRtdpClient.open()) {
    for (JvmProcessInfo process : client.processes()) {
        System.out.printf("%d  %s  %s%n",
                process.pid(), process.architecture(), process.displayName());
    }
}
```

`JvmProcessInfo` 是不可变快照。再次调用 `client.process(pid)` 或 `client.processes()` 可刷新进程状态。

## 3. 连接与关闭

```java
import nhcm.jvmrtdp.api.JvmRtdpClient;
import nhcm.jvmrtdp.api.JvmRtdpSession;

try (JvmRtdpClient client = JvmRtdpClient.open();
     JvmRtdpSession session = client.attach(pid)) {
    System.out.println(session.targetDisplayName());
    System.out.println(session.agentVersion());
    System.out.println(session.nativeAvailable());
}
```

`JvmRtdpClient` 拥有其创建的会话。关闭会话会释放调试器状态、上下文对象、工作区句柄、协议连接和客户端注册；关闭客户端会关闭仍处于打开状态的全部会话。

## 4. 连接选项

```java
import nhcm.jvmrtdp.api.AttachOptions;

AttachOptions options = AttachOptions.builder()
        .agentJar(java.nio.file.Paths.get("<path-to-JVMRTDP.jar>"))
        .timeout(java.time.Duration.ofSeconds(30))
        .build();

try (JvmRtdpSession session = client.attach(pid, options)) {
    // 使用目标 JVM。
}
```

通过 JAR 依赖加载 JVMRTDP 时，默认选项会自动定位该 JAR。从 IDE 或展开的 classes 目录运行时，需要显式指定 `agentJar`。

## 5. 执行上下文命令

`execute` 可在不要求宿主程序模拟终端输入的情况下使用 CLI 的上下文命令：

```java
import nhcm.jvmrtdp.api.JvmRtdpCommandResult;

JvmRtdpCommandResult result = session.execute(
        "decompile method com.example.Service run ()V --engine cfr");

if (result.successful()) {
    System.out.print(result.standardOutput());
} else {
    System.err.println(result.failureType() + ": " + result.failureMessage());
}
```

`standardOutput` 和 `standardError` 分开保存。命令写入错误诊断或抛出异常时，结果会标记为失败。`requireSuccess()` 成功时返回当前结果，失败时抛出包含该结果的 `JvmRtdpCommandException`。

CLI 控制命令（例如 `back` 或 `exit`）被请求时，`sessionContinuationRequested()` 为 false，但不会自动关闭库会话。

## 6. 类型化 API

不希望解析文本输出时，使用类型化 API：

```java
import nhcm.jvmrtdp.controllerside.analysis.DecompilerEngine;
import nhcm.jvmrtdp.handles.java.RemoteClass;

RemoteClass service = session.findClass("com.example.Service");
String source = service.decompile(DecompilerEngine.CFR).source();
byte[] classBytes = service.getClassBytes();

System.out.println(session.jvmti().phase());
System.out.println(session.jvmti().capabilityStatuses());
```

主要入口：

| API | 用途 |
| --- | --- |
| `session.findClass()` | 解析已加载类 |
| `session.forceLoadClass()` | 在目标端执行 `Class.forName` |
| `session.jni()` | 类、对象、字段、方法、数组、搜索和物化 |
| `session.jvmti()` | capability、线程、栈、local、事件、断点、tag 和类操作 |
| `session.operations()` | 基于工作区的构造、调用、读取和写入 |
| `session.context()` | 嵌入式 CLI 命令使用的上下文栈 |
| `session.workspace()` | 具名类和对象句柄 |
| `session.debugger()` | 可逆分析冻结和调试器协调 |
| `session.serverHandle()` | 高级认证协议访问 |

远程对象句柄是强引用。不再使用 `RemoteObject`、`RemoteJvmtiThread`、部署、回调和其他可关闭句柄时，应尽快关闭。

## 7. Agent 命令与异步调用

目标 agent 直接实现的命令可绕过控制端上下文层：

```java
import nhcm.jvmrtdp.protocol.CommandReply;

CommandReply reply = session.executeAgent("ping");
CommandReply timed = session.executeAgent("info", java.time.Duration.ofSeconds(5));

java.util.concurrent.CompletableFuture<CommandReply> future =
        session.executeAgentAsync("native");
```

使用 `executeAgentBatch(List<String>)` 可在一次协议请求中发送多个 agent 命令。`decompile`、`debugger` 和 `context` 等上下文命令必须使用 `session.execute` 或类型化 API。

## 8. 调试器与 JVMTI

```java
session.jvmti().configureDebugger(true);
session.jvmti().setBreakpoint(
        "com.example.Service", "run", "()V", 0, true);

session.execute("debugger snapshot output/debugger.json json")
        .requireSuccess();
```

Capability 取决于 JVM 阶段。需要仅限 `OnLoad` 的能力时，应在目标启动时使用 `-agentpath`。动态注入不能强制开启 HotSpot 已不再报告为 potential 的能力。

## 9. 线程安全

- `session.execute` 会串行执行该会话的上下文命令。
- `executeAgentAsync` 和协议句柄支持并发 agent 请求。
- 除非 API 另有说明，上下文、工作区、远程对象和调试器操作应由调用方串行化。
- 不要在不同会话之间共享远程句柄。
- 工作执行期间关闭会话会使未完成操作失败。

## 10. 错误处理

- 进程发现和注入可能抛出 `InjectionException` 或平台/原生库加载错误。
- 嵌入式命令返回 `JvmRtdpCommandResult`；需要异常处理方式时调用 `requireSuccess()`。
- 类型化 API 会针对目标错误、成员缺失、句柄失效和不支持的 JVMTI 操作抛出运行时异常。
- 客户端、会话和可关闭远程句柄应始终使用 try-with-resources。
