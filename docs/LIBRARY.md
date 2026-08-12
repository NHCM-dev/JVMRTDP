# JVMRTDP Java Library Guide

[English](LIBRARY.md) | [中文](LIBRARY_ZH.md)

JVMRTDP can be embedded in another Java application through the public API in `nhcm.jvmrtdp.api`. The build also continues to produce the standalone executable JAR used by the CLI and TUI.

## 1. Install

Publish a development build to the local Maven repository:

```powershell
.\gradlew.bat publishToMavenLocal
```

Gradle:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("nhcm.jvmrtdp:jvmrtdp:2.1.0")
}
```

Maven:

```xml
<dependency>
  <groupId>nhcm.jvmrtdp</groupId>
  <artifactId>jvmrtdp</artifactId>
  <version>2.1.0</version>
</dependency>
```

For a local file dependency, use `build/libs/jvmrtdp-2.1.0-library.jar`. The library artifact contains JVMRTDP classes, native Windows x64 components, and decompiler implementations. Maven/Gradle metadata supplies JLine as a runtime dependency; file-based consumers that use terminal controller classes must add JLine themselves. The standalone `build/libs/JVMRTDP-2.1.0.jar` remains a self-contained executable.

Published artifacts include the library JAR, sources, Javadocs, and Maven POM. The automatic module name is `nhcm.jvmrtdp`.

A compilable example is available at [`examples/library/LibraryExample.java`](../examples/library/LibraryExample.java).

## 2. Discover Processes

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

`JvmProcessInfo` is an immutable snapshot. Call `client.process(pid)` or `client.processes()` again to refresh process state.

## 3. Attach and Close

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

`JvmRtdpClient` owns its sessions. Closing a session releases its debugger state, context objects, workspace handles, protocol connection, and callback registration with the client. Closing the client closes every session that remains open.

## 4. Attach Options

```java
import nhcm.jvmrtdp.api.AttachOptions;

AttachOptions options = AttachOptions.builder()
        .agentJar(java.nio.file.Paths.get("<path-to-JVMRTDP.jar>"))
        .timeout(java.time.Duration.ofSeconds(30))
        .build();

try (JvmRtdpSession session = client.attach(pid, options)) {
    // Use the target JVM.
}
```

When JVMRTDP is loaded from a JAR dependency, the default options locate that JAR automatically. Specify `agentJar` when running from an IDE or an exploded classes directory.

## 5. Execute Context Commands

`execute` exposes the same context-oriented commands as the CLI without terminal parsing by the host application:

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

`standardOutput` and `standardError` are captured separately. A command that writes an error diagnostic or throws an exception produces an unsuccessful result. `requireSuccess()` returns the same result or throws `JvmRtdpCommandException` containing the result.

`sessionContinuationRequested()` is false when a CLI-only control command such as `back` or `exit` was requested. It does not close the library session automatically.

## 6. Typed API

Use the typed API when output parsing is undesirable:

```java
import nhcm.jvmrtdp.controllerside.analysis.DecompilerEngine;
import nhcm.jvmrtdp.api.jvmti.JvmBreakpointCondition;
import nhcm.jvmrtdp.handles.java.RemoteClass;

RemoteClass service = session.findClass("com.example.Service");
String source = service.decompile(DecompilerEngine.CFR).source();
byte[] classBytes = service.getClassBytes();

System.out.println(session.jvmti().phase());
System.out.println(session.jvmti().capabilityStatuses());
```

Important accessors:

| API | Purpose |
| --- | --- |
| `session.findClass()` | Resolve a loaded class |
| `session.forceLoadClass()` | Run target-side `Class.forName` |
| `session.jni()` | Classes, objects, fields, methods, arrays, search, and materialization |
| `session.jvmti()` | Capabilities, threads, stacks, locals, events, breakpoints, tags, and class operations |
| `session.instrumentation()` | Source/JAR deployment, hooks, transformers, retransform, and redefine |
| `session.operations()` | Workspace-oriented construct/call/get/set helpers |
| `session.context()` | Context stack used by embedded CLI commands |
| `session.workspace()` | Named class and object handles |
| `session.debugger()` | Reversible analysis freeze and debugger coordination |
| `session.serverHandle()` | Advanced authenticated protocol access |

Remote object handles are strong references. Close `RemoteObject`, `RemoteJvmtiThread`, deployment, callback, and other closeable handles as soon as they are no longer needed.

## 7. Agent Commands and Asynchronous Calls

Commands implemented directly by the target agent can bypass the controller-side context layer:

```java
import nhcm.jvmrtdp.protocol.CommandReply;

CommandReply reply = session.executeAgent("ping");
CommandReply timed = session.executeAgent("info", java.time.Duration.ofSeconds(5));

java.util.concurrent.CompletableFuture<CommandReply> future =
        session.executeAgentAsync("native");
```

Use `executeAgentBatch(List<String>)` to send multiple agent commands in one protocol request. Context commands such as `decompile`, `debugger`, and `context` must use `session.execute` or the typed APIs.

## 8. Debugger and JVMTI

```java
session.jvmti().configureDebugger(true);
session.jvmti().setBreakpoint(
        "com.example.Service", "run", "()V", 0, true);

// Stop only when this exact receiver is invoked by a matching caller.
JvmBreakpointCondition condition = JvmBreakpointCondition.receiver(serviceObject)
        .calledFrom("com.example.web.*", "dispatch*", "*");
session.jvmti().setBreakpoint(
        "com.example.Service", "run", "()V", 0, condition, true);

// Event breakpoints also work for native methods and abstract declarations.
JvmEventBreakpointInfo entry = session.jvmti().setEventBreakpoint(
        JvmEventBreakpointSpec.methodEntry(
                "java.lang.Runnable", "run", "()V").includingSubtypes());
JvmEventBreakpointInfo exceptions = session.jvmti().setEventBreakpoint(
        JvmEventBreakpointSpec.exception("com.example.*Exception"));

try (RemoteObject replacementInteger = session.jni().valueOf(42);
     JvmDebuggerState stop = session.jvmti().debuggerState()) {
    if (stop.paused()) {
        session.jvmti().setDebuggerLocal(stop.thread(), 0, 1, "I", replacementInteger);
        session.jvmti().stepOut(stop.thread());
    }
}

session.execute("debugger snapshot output/debugger.json json")
        .requireSuccess();
```

`managedBreakpoints()` and debugger JSON/JSONL exports include the registration ID, receiver identity, and condition summary. Use `clearBreakpoint(info)` to clear a listed registration even after its original object handle is no longer selected. Callback handles support `enable()`, `disable()`, and `resetStatistics()` in addition to `close()`.

`forceEarlyReturn(thread, value)` and `forceEarlyReturnVoid(thread)` replace the result of the
currently paused Java frame; continue the thread afterward. Native frames are opaque to
`ForceEarlyReturn`, and a `METHOD_EXIT` stop occurs after the result has already been committed.
At an exit stop, `JvmDebuggerState.returnState()` identifies `value`, `void`, or `exception`, and
`returnValue()` exposes the boxed target-JVM value when one exists.

### Hooks, transformers, and redefine

`session.instrumentation()` is the high-level facade for code running inside the target JVM:

```java
JvmInstrumentation instrumentation = session.instrumentation();

try (RemoteCodeDeployment deployment = instrumentation.deploySource(
        "audit-hook", "example.AuditHook", hookSource);
     RemoteJvmtiCallback hook = instrumentation.hook(
        deployment, "example.AuditHook",
        java.util.EnumSet.of(JvmtiEventType.METHOD_ENTRY), false)) {
    // The deployed class implements JvmtiEventHandler.
    session.jvmti().retransformClass("com.example.Service");
}

try (RemoteCodeDeployment deployment = instrumentation.deploySource(
        "transformer", "example.ServiceTransformer", transformerSource);
     RemoteJvmtiCallback transformer = instrumentation.transformer(
        deployment, "example.ServiceTransformer", true)) {
    // ServiceTransformer implements JvmtiClassFileTransformer and returns class bytes.
    instrumentation.retransform("com.example.Service");
}

instrumentation.redefine("com.example.Service", java.nio.file.Paths.get("Service.class"));

// A controller-side bytecode editor can also replace the current class in one operation.
byte[] installed = instrumentation.transformAndRedefine(
        "com.example.Service", bytes -> bytecodeEditor.transform(bytes));
```

Use synchronous delivery only when the hook must affect the callback result, such as a class-file
transformer. Asynchronous delivery avoids blocking the application thread for observational hooks.
HotSpot redefine/retransform schema limits still apply; transformers must return a valid class file.

Capabilities depend on the JVM phase. Use `-agentpath` at target startup when an `OnLoad`-only capability is required. Dynamic attach cannot force capabilities that HotSpot no longer reports as potential.

## 9. Thread Safety

- `session.execute` serializes context-command execution for that session.
- `executeAgentAsync` and the protocol handle support concurrent target-agent requests.
- Context, workspace, remote object, and debugger operations should be externally serialized unless their API states otherwise.
- Do not share a remote handle between sessions.
- Closing a session while work is running causes pending operations to fail.

## 10. Errors

- Process discovery and injection may throw `InjectionException` or platform/native loading errors.
- Embedded commands return `JvmRtdpCommandResult`; call `requireSuccess()` for exception-based handling.
- Direct typed APIs throw runtime exceptions for target errors, missing members, invalid handles, and unsupported JVMTI operations.
- Always use try-with-resources for clients, sessions, and closeable remote handles.
