# JVMTI API 覆盖与运行时 capability

JVMRTDP native agent 使用构建工具链中的 Java 8 `jvmti.h` ABI。该头文件定义 139 个函数；当前 native
实现直接调用其中 89 个，约 64%。新增函数已经贯通 native JNI、目标端命令、控制端
`RemoteJVMTIEnv` 和交互 CLI。

## Runtime capability

```java
RemoteJVMTIEnv jvmti = server.javaVM().jvmtiEnv();

List<JvmtiCapabilityStatus> result = jvmti.addCapabilities(
        JvmtiCapability.CAN_GET_BYTECODES,
        JvmtiCapability.CAN_GET_LINE_NUMBERS);

jvmti.relinquishCapabilities(JvmtiCapability.CAN_GET_BYTECODES);
```

CLI：

```text
jvmti capability-status
jvmti capability relinquish can_get_bytecodes
jvmti capability add can_get_bytecodes
```

实现调用 `GetCapabilities`、`GetPotentialCapabilities`、`AddCapabilities` 和
`RelinquishCapabilities`。`enabled=false,potential=true` 可以在当前 phase 申请；
`enabled=false,potential=false` 不能通过写 HotSpot 内存强制变成真正能力，因为相关事件通知、解释器钩子、
调试信息保存或编译策略可能只在 OnLoad 阶段初始化。此时 API 抛出包含 phase 和 `-agentpath` 建议的错误。

## 已覆盖函数组

- capability/runtime：GetVersionNumber、GetCapabilities、GetPotentialCapabilities、AddCapabilities、
  RelinquishCapabilities、GetPhase、GetTime、GetTimerInfo、GetAvailableProcessors、GetJLocationFormat。
- class：GetLoadedClasses、GetClassSignature、GetClassStatus、GetSourceFileName、GetSourceDebugExtension、
  GetClassModifiers、GetImplementedInterfaces、IsInterface、IsArrayClass、GetClassLoader、
  GetClassLoaderClasses、IsModifiableClass、GetClassVersionNumbers、GetConstantPool、RetransformClasses、
  RedefineClasses、AddToBootstrapClassLoaderSearch、AddToSystemClassLoaderSearch。
- method/field：GetClassMethods、GetMethodName、GetMethodDeclaringClass、GetMethodModifiers、GetMaxLocals、
  GetArgumentsSize、GetLineNumberTable、GetMethodLocation、GetBytecodes、IsMethodNative、IsMethodSynthetic、
  IsMethodObsolete、GetClassFields、GetFieldName、GetFieldModifiers、GetFieldDeclaringClass、IsFieldSynthetic、
  breakpoint 和 field watch API。
- thread/frame：GetAllThreads、GetThreadInfo、GetThreadState、GetFrameCount、GetFrameLocation、GetStackTrace、
  SuspendThread、ResumeThread、InterruptThread、NotifyFramePop、GetThreadCpuTime、GetCurrentThreadCpuTime、
  GetOwnedMonitorInfo、GetCurrentContendedMonitor。
- object/heap：GetObjectSize、GetObjectHashCode、GetObjectMonitorUsage、GetTag、SetTag、GetObjectsWithTags、
  ForceGarbageCollection。
- local/event/system：GetLocal* 读取侧、GetLocalVariableTable、SetEventCallbacks、
  SetEventNotificationMode、GenerateEvents、Get/SetSystemProperty、GetSystemProperties、SetVerboseFlag，
  以及当前定义的完整事件 callback 集合。

## 暂未直接远程开放

以下函数需要额外安全协议或不同抽象：

- `StopThread`、`PopFrame`、`ForceEarlyReturn*`、`SetLocal*`：会直接改变目标执行流，必须先验证线程挂起、
  frame 身份、slot 类型和恢复策略。
- raw monitor、environment/thread local storage：更适合作为 native agent 内部同步设施，直接暴露远程句柄
  容易泄漏或死锁。
- heap iteration / FollowReferences：需要有界流式协议、回压、tag 生命周期和 callback 取消机制，不能把
  整个堆一次性物化进响应。
- JNI function table、extension callback、DisposeEnvironment：会影响 JVMRTDP 自己的底层连接和生命周期。
- Java 9 module JVMTI 函数不在当前 Java 8 native ABI 中；升级 native ABI 后再按版本探测提供。

后续扩展应优先实现“有界 heap visitor”和“经过 suspension token 校验的 frame mutation”，而不是提供
无约束的危险原语。
