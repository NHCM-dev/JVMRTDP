# JVMRTDP Java API 参考

[English](JAVA-API.md) | [中文](JAVA-API_ZH.md)

本文档说明如何把 JVMRTDP 2.2.0 作为 Java 库使用，重点覆盖事件回调、各种断点、字段监视、调试器、插桩和句柄生命周期。英文版是主文档，生成的 Javadoc 提供逐方法签名。

## 1. 入口与生命周期

```java
try (JvmRtdpClient client = JvmRtdpClient.open();
     JvmRtdpSession session = client.attach(pid)) {
    System.out.println(session.jvmti().phase());
}
```

| 入口 | 用途 |
| --- | --- |
| `JvmRtdpClient` | 发现进程、attach |
| `session.execute()` | 执行并捕获与 CLI 相同的上下文命令 |
| `session.jni()` | 类、对象、字段、方法、数组和值 |
| `session.jvmti()` | capability、元数据、线程、调试器、断点、watch 和回调 |
| `session.instrumentation()` | 部署、redefine/retransform、ASM 字节码编辑 |
| `session.context()` / `operations()` / `workspace()` | 共享 Context 栈和具名值 |
| `session.references()` | 强/弱对象引用和实时字段引用 |
| `session.stringHooks()` | String 分配条件、字段与方法 Hook |
| `session.debugger()` | 可恢复的多线程分析冻结 |

`JvmRtdpSession`、`RemoteObject`、`RemoteJvmtiThread`、`JvmDebuggerState`、`JvmDebuggerLocal`、`RemoteCodeDeployment` 和 `RemoteJvmtiCallback` 都持有资源，应使用 try-with-resources 或显式关闭。不要跨 session 传递任何远程句柄。

## 2. 名称、描述符和 BCI

- 类名推荐使用 `com.example.Service`。
- 方法/字段类型使用 JVM descriptor，例如 `()V`、`(Ljava/lang/String;I)Z`、`Ljava/lang/String;`、`[I`。
- 字节码断点位置是 BCI，不是指令序号或源码行号。通过 `RemoteClass.bytecode(...)`、`methodBytecodes(...)` 或 `lineNumberTable(...)` 获取。
- caller/event pattern 支持 `*` 和 `?`。
- native/abstract 方法没有 Java `Code` 属性，不能下 BCI 断点；应使用方法进入/退出事件断点。

## 3. 类、对象、字段和调用

```java
RemoteClass type = session.findClass("com.example.Service");
RemoteField singleton = type.getStaticField("INSTANCE");

try (RemoteObject service = singleton.readStatic();
     RemoteObject argument = session.jni().valueOf("demo");
     RemoteObject result = service.call(
             "lookup", "(Ljava/lang/String;)Ljava/lang/String;", argument)) {
    System.out.println(result.asObject(String.class));
}
```

`forceLoadClass` 会运行 `Class.forName` 并初始化类；`loadClassWithoutInitialization` 只加载和链接，不运行 `<clinit>`。未加载类目录通过 `classPathCatalog()` / `refreshClassPathCatalog()` 获取。

## 4. Capability

```java
for (JvmtiCapabilityStatus status : session.jvmti().capabilityStatuses()) {
    System.out.println(status);
}
session.jvmti().addCapabilities(
        JvmtiCapability.CAN_GENERATE_BREAKPOINT_EVENTS,
        JvmtiCapability.CAN_ACCESS_LOCAL_VARIABLES);
```

每个 `JvmtiEventType` 都有 `requiredCapability()`。动态 attach 位于 JVMTI `LIVE` 阶段，无法获取 HotSpot 已不再标为 potential 的能力。需要启动期能力时使用 `-agentpath`。

## 5. 应该选择哪种机制

| 机制 | 暂停线程 | 用途 |
| --- | --- | --- |
| BCI 断点 | 是 | 一个具体 Java 字节码位置 |
| 条件 BCI 断点 | 是 | 限定 receiver 和/或 caller |
| 方法事件断点 | 是 | entry/exit、native、abstract/interface |
| 异常事件断点 | 是 | 匹配异常类的 throw 位置 |
| 字段 watch | 是 | 字段 read/write，可限定对象 |
| `JvmtiEventHandler` | 否 | 日志、审计、统计、目标端代码 |
| `JvmtiClassFileTransformer` | 否 | 类加载或 retransform 时修改 class bytes |

回调与调试断点是两套机制。回调在目标 JVM 内执行 Java 代码；断点/watch 会暂停事件线程，并由控制端通过 `JvmDebuggerState` 操作。

## 6. BCI 断点与条件

```java
RemoteJVMTIEnv jvmti = session.jvmti();
jvmti.configureDebugger(true);

// 即使类尚未加载，也可以先登记符号断点。
jvmti.setBreakpoint("com.example.Service", "run", "()V", 12, true);

JvmBreakpointCondition condition = JvmBreakpointCondition
        .receiver(serviceObject)
        .calledFrom("com.example.web.*", "dispatch*", "*");
jvmti.setBreakpoint("com.example.Service", "run", "()V", 12, condition, true);

for (JvmBreakpointInfo info : jvmti.managedBreakpoints()) {
    System.out.println(info.registrationId() + " " + info.conditionSummary());
}
jvmti.clearBreakpoint(jvmti.managedBreakpoints().get(0));
```

receiver 使用对象 identity，不调用 `equals`。请在断点清除前保留 receiver 句柄或强 `session.references()` 条目，尤其是类重定义可能重装断点时。推荐从 `managedBreakpoints()` 取得记录后调用 `clearBreakpoint(info)`；这样无需重新拼出完全相同的条件。

字符串形式的普通断点可作用于未加载类，ClassPrepare 时自动安装。带对象条件的断点必须先有一个已加载对象引用。

## 7. 方法进入/退出和异常断点

```java
JvmEventBreakpointInfo entry = jvmti.setEventBreakpoint(
        JvmEventBreakpointSpec.methodEntry(
                "java.lang.Runnable", "run", "()V").includingSubtypes());

JvmEventBreakpointInfo exit = jvmti.setEventBreakpoint(
        JvmEventBreakpointSpec.methodExit(
                "com.example.Parser", "parse",
                "(Ljava/lang/String;)Ljava/lang/String;"));

JvmEventBreakpointInfo exception = jvmti.setEventBreakpoint(
        JvmEventBreakpointSpec.exception("com.example.*Exception"));

jvmti.clearEventBreakpoint(entry);
jvmti.clearManagedEventBreakpoints();
```

`includingSubtypes()` 需要一个精确且已经加载的基类/接口，用于匹配实现。异常子类通过 class glob 表达。native 方法可以在 entry/exit 停止，但 native frame 没有 Java BCI/local。

方法退出停止中，`returnState()` 为 `value`、`void` 或 `exception`；非 void 正常返回可从 `returnValue()` 获取装箱值。此时结果已经提交，若要修改返回值，应更早使用 `forceEarlyReturn` 或字节码 return hook。

## 8. 字段读取/修改 Watch

```java
// modification=false 为读取，true 为写入。
jvmti.setFieldWatch("com.example.Config", "name", "Ljava/lang/String;",
        false, true);
jvmti.setFieldWatch("com.example.Config", "name", "Ljava/lang/String;",
        true, exactConfigObject, true);

for (JvmFieldWatchInfo watch : jvmti.managedFieldWatches()) {
    System.out.println(watch.kind() + " " + watch.className() + "." + watch.fieldName());
}
jvmti.clearManagedFieldWatches();
```

receiver 为 null 时监视所有实例；静态字段也必须传 null。非 null receiver 只匹配该对象 identity。无 receiver 的字符串登记可等待未加载类。String 字段可直接使用更高层的 `JvmStringHookManager`。

## 9. 读取停止状态、栈和 Local

```java
List<JvmDebuggerState> states = jvmti.debuggerStates();
try {
    for (JvmDebuggerState state : states) {
        if (!state.paused()) continue;
        System.out.printf("%s %s.%s%s @%d%n",
                state.reason(), state.className(), state.methodName(),
                state.descriptor(), state.location());

        for (JvmStackFrame frame : jvmti.stackFrames(state.thread(), 32)) {
            System.out.println(frame.display());
        }

        List<JvmDebuggerLocal> locals = jvmti.debuggerLocals(state.thread(), 0);
        try {
            for (JvmDebuggerLocal local : locals) System.out.println(local);
        } finally {
            for (JvmDebuggerLocal local : locals) local.close();
        }

        jvmti.continueExecution(state.thread());
    }
} finally {
    for (JvmDebuggerState state : states) state.close();
}
```

| API | 操作 |
| --- | --- |
| `pauseExecution(thread)` | 在线程当前可调试 Java 位置暂停 |
| `continueExecution(thread)` | 继续一个线程 |
| `continueAllExecutions()` | 继续所有 debugger-paused 线程 |
| `stepInstruction(thread)` | 单步到下一个 Java 字节码事件 |
| `stepOut(thread)` | 当前 frame 返回后在 caller 停止 |
| `debuggerLocals(thread, depth)` | 读取 local，必要时推断槽 |
| `setDebuggerLocal(...)` | 使用具体 descriptor 修改 local 槽 |
| `forceEarlyReturn(...)` | 让兼容 Java frame 立即返回指定值 |
| `forceEarlyReturnVoid(...)` | 立即从 void Java frame 返回 |

JVMTI 标准接口不能读取实时 JVM 操作数栈。native、opaque、obsolete 或已离开作用域的 frame 可能拒绝 local/early-return 操作。

`jvmti.threads()` 返回需关闭的 `RemoteJvmtiThread`。它支持状态、栈、CPU 时间、monitor、暂停/恢复、中断和 frame-pop。`session.debugger().freeze()` 排除 JVMRTDP 与敏感 JVM 服务线程，`restore()` 只恢复该次 freeze 自己暂停的线程。

## 10. 编写 `JvmtiEventHandler`

Handler 在目标 JVM 内运行，收到的是目标 JVM 的普通 Java 对象，而不是控制端 `RemoteObject`。

```java
package example;

import nhcm.jvmrtdp.api.jvmti.*;

public final class AuditHook implements JvmtiCategorizedEventHandler {
    @Override public void onMethodEvent(JvmtiMethodEvent event) {
        System.out.println(event.type() + " " + event.className() + "."
                + event.methodName() + event.methodDescriptor());

        if (event.receiverAvailable() && event.hasReceiver()) {
            System.out.println("receiver=" + event.receiver());
        }
        for (JvmtiMethodArgument argument : event.arguments()) {
            System.out.println(argument.available()
                    ? argument.name() + "=" + argument.value()
                    : argument.name() + " unavailable: " + argument.error());
        }
        if (event.returnValueAvailable()) {
            System.out.println("return=" + event.returnValue());
        }
    }

    @Override public void onFieldEvent(JvmtiEvent event) {
        System.out.println(event.type() + " " + event.className() + "."
                + event.memberName() + event.memberDescriptor());
    }
}
```

控制端部署和注册：

```java
String source = new String(Files.readAllBytes(Paths.get("AuditHook.java")),
        StandardCharsets.UTF_8);

try (RemoteCodeDeployment deployment = session.instrumentation().deploySource(
            "audit", "example.AuditHook", source);
     RemoteJvmtiCallback callback = session.instrumentation().hook(
            deployment, "example.AuditHook",
            EnumSet.of(JvmtiEventType.METHOD_ENTRY,
                    JvmtiEventType.METHOD_EXIT,
                    JvmtiEventType.FIELD_MODIFICATION), false)) {
    callback.disable();
    callback.enable();
    callback.resetStatistics();
}
```

Handler 必须有 public 无参构造器。关闭 callback 会注销；关闭 deployment 会清理其关联 callback。`jvmti.callbacks()` 返回每个注册的计数和最后错误，`callbackStatistics()` 返回总投递量、失败量、native queue 和 dropped 计数。

异步模式（`false`）适合日志、审计和指标，不阻塞应用事件线程；同步模式（`true`）只用于必须在事件返回前完成的操作。class-file transformer 为了返回 class bytes 必须同步。回调异常只记入统计，不抛进业务代码。回调中应避免阻塞 I/O、无限工作、业务锁和递归事件风暴。

### `JvmtiMethodEvent`

- 先检查 `receiverAvailable()`，静态方法 `hasReceiver()` 为 false。
- `arguments()` 的每个元素包含 descriptor、slot、可能存在的参数名、装箱值或错误；必须逐项检查 `available()`。
- `nativeMethod()` / `staticMethod()` 描述目标方法。
- `poppedByException()` 表示异常退出；此时 `normalExit()` 为 false。
- `returnValueAvailable()` 只对正常、非 void 的 METHOD_EXIT 为 true。Java null 返回也是可用值，只是 `returnValue()` 为 null。

## 11. `JvmtiEvent` 参数表

不适用于当前事件的字段返回 null、0 或空值。读取 `subject()`、`secondarySubject()`、`value()`、`location()` 前先判断 `type()`。

| 事件 | 主要参数 |
| --- | --- |
| `VM_INIT` | `thread` |
| `VM_START`、`VM_DEATH`、`DATA_DUMP_REQUEST`、GC start/finish | 只有类型和时间 |
| `THREAD_START`、`THREAD_END` | `thread` |
| `CLASS_LOAD`、`CLASS_PREPARE` | `thread`、`className`、`subject=Class<?>` |
| `CLASS_FILE_LOAD_HOOK` | EventHandler 中 `subject=JvmtiClassFileEvent`；Transformer 直接接收该类型 |
| `SINGLE_STEP`、`BREAKPOINT` | `thread`、方法信息、`location=BCI` |
| `FRAME_POP` | 方法信息，`value` 为是否因异常弹出 |
| `METHOD_ENTRY`、`METHOD_EXIT` | 实际类型为 `JvmtiMethodEvent` |
| `FIELD_ACCESS` | 字段 owner、当前方法/BCI、`subject=receiver`、member 名称/descriptor |
| `FIELD_MODIFICATION` | 同上；对象新值在 `secondarySubject`，基本类型 bits 在 `value` |
| `EXCEPTION` | 抛出方法/BCI、`subject=exception`、`related*` 为 catch 位置 |
| `EXCEPTION_CATCH` | catch 方法/BCI、`subject=exception` |
| monitor contended enter/entered | `thread`、`subject=monitor` |
| `MONITOR_WAIT` | monitor，`value=timeout` |
| `MONITOR_WAITED` | monitor，`value=是否超时` |
| `VM_OBJECT_ALLOC` | 类名、`subject=object`、`value=size` |
| `NATIVE_METHOD_BIND` | 方法信息、`location=native address` |
| `COMPILED_METHOD_LOAD` | 方法、code address、code size、map length |
| `COMPILED_METHOD_UNLOAD` | 方法、旧 code address |
| `DYNAMIC_CODE_GENERATED` | `text=name`、address、byte length |
| `OBJECT_FREE` | `value=对象原 JVMTI tag`，对象引用已经不可用 |
| `RESOURCE_EXHAUSTED` | `value=flags`、`text=description` |

`JvmtiCategorizedEventHandler` 可按 `VM`、`THREAD`、`CLASS`、`EXECUTION`、`METHOD`、`FIELD`、`EXCEPTION`、`MONITOR`、`NATIVE_CODE`、`HEAP`、`GARBAGE_COLLECTION`、`RESOURCE` 分类覆写。

## 12. Class-file Transformer

```java
public final class ServiceTransformer implements JvmtiClassFileTransformer {
    @Override public byte[] transform(JvmtiClassFileEvent event) {
        if (!"com.example.Service".equals(event.className())) return null;
        return transformWithAsm(event.classBytes());
    }
}
```

```java
try (RemoteCodeDeployment deployment = instrumentation.deploySource(
            "transformer", "example.ServiceTransformer", source);
     RemoteJvmtiCallback transformer = instrumentation.transformer(
            deployment, "example.ServiceTransformer", true)) {
    instrumentation.retransform("com.example.Service");
}
```

返回 null 保留当前 bytes。多个 transformer 按注册顺序串联。`classBeingRedefined()` 在首次定义时为 null，redefine/retransform 时非 null。返回值必须是完整、可验证的 class 文件。

`DefinitionMode.CHILD` 使用独立子加载器；`SAME_LOADER` 直接在 anchor 类加载器定义，适合修改后的业务字节码需要链接 helper 的场景。`JarScope.SYSTEM`/`BOOTSTRAP` 会修改进程级搜索路径，应谨慎使用。

## 13. 事务化字节码编辑

```java
JvmBytecodeEditor editor = session.instrumentation().bytecode();
editor.stage(JvmBytecodePatch.builder("com.example.Service")
        .delete("check", "()V", 4, 11)
        .insertBeforeReturns("value", "()I",
                "DUP ;; INVOKESTATIC example/Trace onInt (I)V")
        .addExceptionHandler("value", "()I", 0, 20, 24,
                "java/lang/RuntimeException")
        .build());

byte[] staged = editor.classBytes("com.example.Service");
JvmBytecodePatchResult installed = editor.flush("com.example.Service");
```

`stage` 允许组合尚未完整的修改，不会立即 redefine；`flush` 一次性重算 frame/max、更新异常表、验证、重定义并迁移受管断点。`discard` 放弃暂存内容，`undo`/`redo` 管理已安装历史。高级 ASM 指令使用 `stageMethod(..., Consumer<MethodNode>)`。

## 14. 引用与 String Hook

```java
session.references().trackObject("service", service,
        JvmReferenceStrength.WEAK);
session.references().trackStaticField("current", staticField);
for (JvmReferenceInfo info : session.references().refreshAll()) {
    System.out.println(info.name() + " " + info.state());
}

session.stringHooks().watchField("message-write", messageField, true, null);
session.stringHooks().breakMethod("parse-exit", JvmStringHookKind.METHOD_EXIT,
        "com.example.Parser", "parse",
        "(Ljava/lang/String;)Ljava/lang/String;");
session.stringHooks().breakAllocation("secret-created",
        JvmStringAllocationSpec.builder()
                .contentGlob("*secret-token*")
                .createdFrom("com.example.*", "*", "*")
                .caseSensitive(false)
                .mode(JvmStringAllocationMode.FAST)
                .includeLdc(true)
                .maximumHits(10)
                .sampleEvery(2)
                .build());
```

Allocation 条件会在目标端同步匹配后才产生调试停止。实现先匹配内容；creator 条件全为 `*`
时不遍历完整栈。默认 `FAST` 临时在 `String.<init>` 构造器返回处加入轻量探针，并在进入
native/JVMTI 前先于 Java bootstrap 桥中过滤内容；它不监听所有方法退出或所有对象分配。
`COMPLETE` 才额外开启 `VM_OBJECT_ALLOC` 来覆盖 VM/native 或已被 JIT intrinsic 化的 String，因此会有
JVM 全局分配事件开销。同一对象会去重。`oneShot()`、`maximumHits(...)`、`sampleEvery(...)`
可限制停止频率；没有 armed Hook 后热路径会自动关闭。
可选的 `includeLdc(true)` 会额外观察匹配 String 常量的 `ldc`/`ldc_w` 执行。已加载方法直接
设置精确 JVMTI 断点而不重转换，后续加载类使用过滤探针；命中时以
`returnState() == "ldc"` 暂停在实际方法/BCI。它观察字面量使用而非对象分配；intern 常量可被
反复执行，所以默认关闭。
停止状态的 `returnState()` 为 `allocation`（LDC 命中为 `ldc`），匹配对象从
`eventValue()` 获取。

引用状态为 `LIVE`、`NULL`、`COLLECTED`、`RELEASED`、`ERROR`。弱引用不占用 JVMTI tag。
字段型 String Hook 可读取、替换和加入引用管理器；Allocation Hook 可读取和追踪最近命中值；
自定义 UI 将 `debuggerStates()` 传入 `observe(...)` 可更新最后命中信息。

Fast 模式需要 retransform/redefine capability；Complete 还需要
`CAN_GENERATE_VM_OBJECT_ALLOC_EVENTS`。动态 attach 后不可再获得时应使用 `-agentpath`。

## 15. 其他 JVMTI 类型化能力

`RemoteJVMTIEnv` 还提供类/方法/字段元数据、bytecodes、行号表、constant pool、timer、CPU time、系统属性、verbose flag、线程状态、栈、monitor、object size/hash/tag、按 tag 查对象、GC、class dump/redefine/retransform、部署列表和 callback 统计。

这些调用与 JVMTI 原语接近，phase、capability、对象、frame 或方法不支持时会返回明确的 `JVMTI_ERROR_*`。完整原生函数覆盖见 [JVMTI API 覆盖](JVMTI-API-COVERAGE_ZH.md)。

## 16. 常见错误检查

- 原生功能前检查 `session.nativeAvailable()`，DLL/JAR 不匹配时查看 `nativeDescription()`。
- 命令式 API 使用 `JvmRtdpCommandResult.requireSuccess()`。
- 关闭 read/call/construct/local/debugger state/thread/tag lookup 返回的所有对象句柄。
- 释放对象限定 receiver 前先清理对应断点/watch。
- 不要从目标端 Handler 调用控制端 session API；它们位于不同 JVM。
- 不要假设参数、local、源码行号和返回值始终可用。
- redefine 不能普通地增删字段/方法或改变继承结构。
- Context、workspace 和 debugger 变更应由调用方串行化。

<!-- English JAVA-API.md is canonical. -->
