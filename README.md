# JVMRTDP

JVMRTDP 是面向 Windows x64 的目标 JVM 诊断与对象操作工具。控制端将 Java Agent 注入目标 JVM，
然后通过仅监听回环地址、带随机会话令牌认证的协议进行通信。

进入目标后可以像使用命令行调试器一样：浏览已加载类、检索字段和方法、维护对象 context 栈、读取或修改字段、
调用静态/虚方法、构造对象、查看数组和集合、通过 JVMTI dump class，以及运行批处理和流程脚本。

- [完整命令手册](docs/COMMANDS.md)
- [脚本语言手册](docs/SCRIPTING.md)

## 当前能力

- 列出可访问 JVM 的 PID、架构、任务管理器映像名、窗口标题、运行时间和 Java 主类/JAR。
- 使用 `RemoteClass`、`RemoteObject`、`RemoteField`、`RemoteMethod` 等面向对象 handle 操作目标 JVM。
- 支持父类视图、父类同名字段、正常虚分派以及精确父类实现调用。
- 支持 primitive、String、enum、数组、`Iterable`、`Map` 和远程对象强引用。
- 按 package、class、interface、enum、annotation、extends、implements、field、method 检索。
- 检索支持 `*`、`?` 通配符和结果数量限制。
- 单类或按包批量 dump；class bytes 始终写入文件。
- 将单个 Java 源文件、源码目录/项目、多段方法或 JAR 分块部署到目标 JVM，并调用其中的方法。
- 支持 child、目标类同 ClassLoader、system 与 bootstrap class path 四种装载语义。
- 覆盖 JVMTI 1.2 的全部非 reserved 事件回调；callback 可按 VM、线程、类、执行、方法、字段、异常、monitor、native code、heap/GC 和资源分类。
- `JvmtiMethodEvent` 可读取 receiver、完整参数 slot/descriptor/值、参数名（有调试表时）、返回值和异常 pop 状态，并逐参数报告不可读取原因。
- 支持同步/异步 Java 回调、ClassFileLoadHook 字节码转换链、回调统计、断点、字段 watch、重转换/重定义、线程/栈、tag、对象大小和 GC 操作。
- Context 栈、书签、不会污染栈的临时 `->` 引用链、UTF-8 输出捕获、批处理和 `.jrd` 流程脚本。
- Prompt 每次显示前都会尝试刷新当前对象的类型、null 状态和 `toString()` 值。
- Gradle 版本是唯一版本来源；构建时生成 `BuildInfo.java`，CLI、Agent 握手和 manifest 共同引用。
- 目标 JVM 使用暂存的 Agent JAR，退出控制端后不会继续锁定项目中的原始 JAR。

## 环境要求

- Windows x64。
- Java 8 或更高版本；项目以 Java 8 字节码为基线。
- 构建原生 DLL 需要 Visual Studio 2022 C++ 工具链。
- 默认 MSBuild 路径为
  `C:/Program Files/Microsoft Visual Studio/2022/Community/MSBuild/Current/Bin/MSBuild.exe`。

如果 MSBuild 安装在其他位置：

```powershell
.\gradlew.bat build -Pmsbuild="D:\Path\To\MSBuild.exe"
```

## 构建与启动

```powershell
.\gradlew.bat build
java -jar build\libs\JVMRTDP.jar
```

版本只需要在 [build.gradle](build.gradle) 中修改：

```groovy
version = '1.0.0'
```

构建任务 `generateBuildInfo` 会生成
`build/generated/sources/version/java/nhcm/jvmrtdp/BuildInfo.java`。JAR 文件名保持为 `JVMRTDP.jar`，
版本同时写入 `BuildInfo.VERSION` 和 manifest 的 `Implementation-Version`。

直接启动后进入本地控制端：

```text
JVMRTDP controller. Type 'help' for commands.
jvmrtdp>
```

最短使用流程：

```text
jvmrtdp> ps
jvmrtdp> attach 12345
target[12345|<unset>]> context class com.example.App
target[12345|class com.example.App]> static field INSTANCE
target[12345|com.example.App#1(...)]> read status
target[12345|com.example.App#1(...)]> back
jvmrtdp> exit
```

也支持一次性入口：

```powershell
java -jar JVMRTDP.jar list
java -jar JVMRTDP.jar inject 12345
java -jar JVMRTDP.jar --version
java -jar JVMRTDP.jar -V
java -jar JVMRTDP.jar --help
```

无参数进入交互控制端时会显示包含当前版本的 ASCII banner；`version` 命令可在控制端或目标会话中再次查看版本。

## 命令设计

目标命令以“当前 context 就是接收者”为核心。Context 可以是类或对象：

```text
context class com.example.App
static field INSTANCE
field service
invoke status ()Ljava/lang/String;
value
```

未加引号的 `->` 是一次性引用链：前一段解析出的对象只作为后一段的临时接收者。整条链结束或失败后，
进入链前的 current context、context 栈和书签都会恢复，中间步骤的输出也不会逐段打印：

```text
context class com.example.App -> static field INSTANCE -> field service -> invoke status ()Ljava/lang/String; -> value
```

链中的 `invoke`、`set` 等目标程序副作用不会回滚；临时性只针对控制端的 context 导航状态。

所有接受对象参数的命令都支持 `{...}` 值表达式，可以直接构造、调用、读取并嵌套：

```text
invoke install (Lcom/example/Service;)V {new com.example.Service (Ljava/lang/String;)V string:main}
set service {static com.example.Services create ()Lcom/example/Service;}
construct com.example.Controller (Lcom/example/Config;)V {static-field com.example.Config DEFAULT}
resolve {context -> field service -> invoke status ()Ljava/lang/String;}
```

`resolve`（别名 `ref`、`eval`）只求值和打印，不改变 context。另有 `class:com.example.Type` 与
`enum:com.example.Mode:FAST`，用于把目标 JVM 中的 `Class` 对象或枚举常量直接作为参数。

读取字段有两种明确语义：

```text
field status       # 读取并将结果切换为新 context
read status        # 只打印；context 和 context 栈均不改变
```

修改当前对象可以使用精简写法：

```text
set status string:ready
set com.example.Parent::count int:7
set owner this
```

## 常用示例

检索已加载内容：

```text
find package java.* --limit 100
find class *Service --package com.example.** --extends *Base --implements *Api
find interface *Listener
find field *cache* --class com.example.* --type java.util.Map --static
find method get* --class com.example.* --returns byte[] --params "java.lang.String,*"
```

浏览类成员时输出 Java 可读类型，同时保留 JVM descriptor：

```text
class fields virtual *count*
class methods all get*
```

例如字段类型显示为 `int`、`float`、`long`、`byte[]`，方法仍显示 `descriptor=(IF)J`，便于直接调用。

父类字段和父类方法：

```text
context static field com.example.State CURRENT
context as com.example.Parent
read com.example.Parent::value
set com.example.Parent::value int:9
invoke com.example.Parent::reset ()V
context runtime
```

普通 `invoke method descriptor` 使用 Java 虚分派；带 `Parent::method` 的形式调用指定父类实现。

按包 dump：

```text
dumpclass com.example.Model build\dump\Model.class
dump package com.example build\dump --no-recursive
dump package com.example build\dump --recursive --match *Service --limit 500
```

`dumpclass`/`dump` 不会将 class bytes 或 Base64 打印到终端，始终输出文件。

## 项目结构

```text
src/main/java/nhcm/jvmrtdp/controllerside   控制端 CLI、context、脚本和高层操作
src/main/java/nhcm/jvmrtdp/remoteside      目标 JVM 内的对象注册表、反射和检索服务
src/main/java/nhcm/jvmrtdp/handles         面向对象的控制端 RemoteXXX API
src/main/java/nhcm/jvmrtdp/protocol        命令协议和结构化文本编码
CPPProjects/Injector                       Windows x64 注入器和进程信息读取
CPPProjects/JVMRTDP                        目标端 JNI/JVMTI 原生桥
```

核心对象关系：

- `JVMRTDP` / `JRDInjector`：发现 JVM 并建立注入会话。
- `ServerHandle` / `RemoteJavaVM`：会话和 JVM 根 handle。
- `RemoteJNIEnv`：类、对象、字段、方法、数组、集合、检索和统计操作。
- `RemoteJVMTIEnv`：JVMTI 操作、Java/JAR 部署、目标代码调用和 callback 生命周期。
- `RemoteClass` / `RemoteClassInfo`：类元数据、成员与构造器。
- `RemoteObject` / `RemoteObjectView`：远程对象强引用和指定类型视图。
- `RemoteField` / `RemoteMethod` / `RemoteConstructor`：成员操作。
- `RemoteClassQuery` / `RemoteMemberQuery`：面向对象的检索条件。

## RemoteXXX API 示例

```java
try (ServerHandle server = jvmrtdp.inject(pid)) {
    RemoteJNIEnv jni = server.javaVM().jniEnv();
    RemoteClass type = jni.findClass("com.example.User");

    try (RemoteObject name = jni.valueOf("Ada");
         RemoteObject user = type.getConstructor("(Ljava/lang/String;)V").construct(name);
         RemoteObject result = type
                 .getVirtualMethod("displayName", "()Ljava/lang/String;")
                 .call(user)) {
        String local = result.asObject(String.class);
        System.out.println(local);
    }

    type.dumpClass(Paths.get("build/dump/User.class"));
}
```

`RemoteObject.asObject(Class<T>)` 支持 null、String、包装 primitive、char、enum 和 `byte[]`。
普通业务对象不会被整体反序列化到控制端，而是继续作为目标 JVM 中的远程 handle 使用。

## 安全与行为边界

- 通信只绑定回环地址，并使用随机会话令牌；它不是远程网络管理协议。
- 对象 handle 在目标 JVM 中持有强引用，应及时 `close()`；会话关闭时会整体释放。
- 字段写入、构造器、方法调用、迭代器和某些 `toString()` 会执行目标应用代码并可能改变状态。
- Prompt 刷新是尽力而为；目标对象 `toString()` 失败时保留最后一次可用显示值。
- 私有成员访问仍可能被 SecurityManager、模块边界或 JVM 实现限制。
- 只列出当前已经加载的类；搜索和 package 浏览不会主动加载新业务类。
- 当前原生注入器只构建 Windows x64 版本。
- 动态注入发生在 JVMTI live phase；JVM 只会授予此阶段仍可添加的 capability。`jvmti capability-status` 会完整显示每个 JVMTI 1.2 capability 的 enabled/potential 状态。MethodEntry/MethodExit 等启动期能力可通过 `-agentpath` 模式在 `Agent_OnLoad` 获取；不可用能力会返回包含 capability 名称和启动建议的明确错误。

完整语法和行为细节见 [命令手册](docs/COMMANDS.md)，脚本控制流见 [脚本语言手册](docs/SCRIPTING.md)。
