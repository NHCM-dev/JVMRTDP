# JVMRTDP Java API Reference

[English](JAVA-API.md) | [中文](JAVA-API_ZH.md)

This is the practical reference for embedding JVMRTDP 2.2.0. It complements the generated
Javadoc: this guide explains which API to choose, what each callback contains, how debugger stops
work, and which handles must be closed.

## 1. Entry points

```java
try (JvmRtdpClient client = JvmRtdpClient.open();
     JvmRtdpSession session = client.attach(pid)) {
    System.out.println(session.agentVersion());
    System.out.println(session.jvmti().phase());
}
```

| Entry point | Use it for |
| --- | --- |
| `JvmRtdpClient` | Process discovery and attach |
| `JvmRtdpSession.execute()` | Captured CLI/TUI-equivalent command execution |
| `session.jni()` | Classes, object handles, fields, methods, arrays and values |
| `session.jvmti()` | Capabilities, class metadata, threads, debugger, breakpoints, watches and callbacks |
| `session.instrumentation()` | Deployment, redefine/retransform and transactional ASM editing |
| `session.context()` / `operations()` / `workspace()` | The shared Context stack and named command-style values |
| `session.references()` | Session-owned strong/weak object and live field references |
| `session.stringHooks()` | Conditional String allocations, field watches, and method entry/exit hooks |
| `session.debugger()` | Reversible multi-thread analysis freeze |

`JvmRtdpSession`, `RemoteObject`, `RemoteJvmtiThread`, `JvmDebuggerState`,
`JvmDebuggerLocal`, `RemoteCodeDeployment`, and `RemoteJvmtiCallback` own resources. Close them
deterministically. `RemoteClass`, `RemoteMethod`, and `RemoteField` are lightweight session handles.
Never use a handle with another session.

## 2. Names, descriptors, patterns and BCI

- Public class-name arguments use binary names such as `com.example.Service`. Slash names are
  normalized where documented, but dot names are the portable choice.
- Method and field types use JVM descriptors: `()V`, `(Ljava/lang/String;I)Z`,
  `Ljava/lang/String;`, and `[I`.
- A bytecode breakpoint location is a bytecode index (BCI), not an instruction ordinal or source
  line. Use `RemoteClass.bytecode(...)`, `methodBytecodes(...)`, or `lineNumberTable(...)` to find it.
- Caller and event-breakpoint patterns support `*` and `?`. Descriptors are matched as descriptor
  strings, so quote them in a shell but pass them unchanged in Java.
- A native or abstract method has no Java `Code` attribute and therefore no BCI breakpoint. Use a
  method-entry or method-exit event breakpoint instead.

Common descriptors:

| Java declaration | Descriptor |
| --- | --- |
| `void run()` | `()V` |
| `String parse(String value)` | `(Ljava/lang/String;)Ljava/lang/String;` |
| `long sum(int[] values)` | `([I)J` |
| `String field` | `Ljava/lang/String;` |

## 3. Classes, objects, fields and method calls

```java
RemoteClass serviceClass = session.findClass("com.example.Service");
RemoteField instanceField = serviceClass.getStaticField("INSTANCE");

try (RemoteObject service = instanceField.readStatic();
     RemoteObject argument = session.jni().valueOf("demo");
     RemoteObject result = service.call(
             "lookup", "(Ljava/lang/String;)Ljava/lang/String;", argument)) {
    System.out.println(result.asObject(String.class));
}
```

Use `getStaticMethods()`, `getVirtualMethods()`, `getStaticFields()`, and `getVirtualFields()` to
enumerate members. When an inherited member name is ambiguous, use the overload that includes its
declaring class. `callSpecial` invokes a declaring-class implementation without virtual override
dispatch. Constructors use `RemoteClass.construct(descriptor, arguments...)`.

`forceLoadClass(name)` runs `Class.forName` and initializes the class. In contrast,
`loadClassWithoutInitialization(name)` loads and links without running `<clinit>`. The class-path
catalog is available through `classPathCatalog()` and `refreshClassPathCatalog()` and does not mix
unloaded entries with live `RemoteClass` handles.

## 4. Capabilities and JVM phase

```java
RemoteJVMTIEnv jvmti = session.jvmti();
for (JvmtiCapabilityStatus status : jvmti.capabilityStatuses()) {
    System.out.printf("%s enabled=%s potential=%s%n",
            status.capability(), status.enabled(), status.potential());
}

jvmti.addCapabilities(
        JvmtiCapability.CAN_GENERATE_BREAKPOINT_EVENTS,
        JvmtiCapability.CAN_ACCESS_LOCAL_VARIABLES);
```

Every `JvmtiEventType` exposes `requiredCapability()`. A callback registration fails clearly when
its capability is unavailable. Dynamic attach occurs in the JVMTI `LIVE` phase and cannot recover
capabilities that HotSpot no longer reports as potential. Load the agent with `-agentpath` when
startup-only capability acquisition or a startup stop is required.

## 5. Choosing a stop or observation mechanism

| Mechanism | Pauses a thread | Best use | Works for unloaded classes |
| --- | --- | --- | --- |
| BCI breakpoint | Yes | One concrete Java instruction | Yes, string overload |
| Conditional BCI breakpoint | Yes | Exact receiver and/or caller | Class must be loaded for receiver scope |
| Method event breakpoint | Yes | Entry/exit, native, abstract/interface declarations | Symbolic patterns are retained; subtype expansion needs a loaded exact base type |
| Exception event breakpoint | Yes | Throw sites matching an exception-class glob | Yes |
| Field watch | Yes | Field read/write, optionally one receiver | Yes without receiver scope |
| `JvmtiEventHandler` | No | Telemetry, audit, custom target-side code | Receives future events after registration |
| `JvmtiClassFileTransformer` | No | Change class bytes during load/retransform | Yes |

Callbacks and debugger stops are different systems. A callback observes an event and runs Java
code in the target JVM. A breakpoint/watch suspends the event thread and creates a
`JvmDebuggerState` controlled from the client.

## 6. Bytecode breakpoints

```java
RemoteJVMTIEnv jvmti = session.jvmti();
jvmti.configureDebugger(true);

// Persistent BCI breakpoint. It may be registered before the class is loaded.
jvmti.setBreakpoint("com.example.Service", "run", "()V", 12, true);

for (JvmBreakpointInfo info : jvmti.managedBreakpoints()) {
    System.out.println(info.registrationId() + " " + info.className()
            + "." + info.methodName() + "@" + info.location());
}

JvmBreakpointInfo first = jvmti.managedBreakpoints().get(0);
jvmti.clearBreakpoint(first);
```

Use the returned managed list to clear a registration reliably. Calling `setBreakpoint(...,
false)` is useful only when all identifying fields, including the condition, are exactly the same.
Managed breakpoints are restored after class-byte capture/retransform and relocated when
`JvmBytecodeEditor` emits a BCI relocation map.

### Receiver and caller conditions

```java
JvmBreakpointCondition condition = JvmBreakpointCondition
        .receiver(serviceObject)
        .calledFrom("com.example.web.*", "dispatch*", "*");

jvmti.setBreakpoint("com.example.Service", "run", "()V", 12, condition, true);
```

An empty caller component is also a wildcard. Receiver matching uses object identity, not
`equals`. Keep the receiver handle or a strong `session.references()` entry alive until the
breakpoint is cleared, especially if redefine may reinstall it.

## 7. Method-entry, method-exit and exception breakpoints

```java
JvmEventBreakpointInfo entry = jvmti.setEventBreakpoint(
        JvmEventBreakpointSpec.methodEntry(
                "java.lang.Runnable", "run", "()V").includingSubtypes());

JvmEventBreakpointInfo exit = jvmti.setEventBreakpoint(
        JvmEventBreakpointSpec.methodExit(
                "com.example.Parser", "parse",
                "(Ljava/lang/String;)Ljava/lang/String;"));

JvmEventBreakpointInfo exceptions = jvmti.setEventBreakpoint(
        JvmEventBreakpointSpec.exception("com.example.*Exception"));

jvmti.clearEventBreakpoint(entry);
jvmti.clearManagedEventBreakpoints();
```

`includingSubtypes()` requires one exact, already-loaded base class or interface; it expands the
declaration to matching implementations. Exception subtype matching is expressed with the class
glob instead. Entry/exit breakpoints are the correct way to stop native methods and abstract
declarations, but a native stop still has no Java BCI or readable Java local-variable frame.

At a method-exit stop, `JvmDebuggerState.returnState()` is `value`, `void`, or `exception` and
`returnValue()` is a boxed `RemoteObject` when available. The result is already committed at
`METHOD_EXIT`; modify it earlier with `forceEarlyReturn` or an inserted return hook.

## 8. Field read/write watches

```java
// false = access/read, true = modification/write
jvmti.setFieldWatch("com.example.Config", "name", "Ljava/lang/String;",
        false, true);
jvmti.setFieldWatch("com.example.Config", "name", "Ljava/lang/String;",
        true, exactConfigObject, true);

for (JvmFieldWatchInfo watch : jvmti.managedFieldWatches()) {
    System.out.println(watch.kind() + " " + watch.className() + "." + watch.fieldName());
}
jvmti.clearManagedFieldWatches();
```

A null receiver means every instance and is also required for a static field. A non-null receiver
limits an instance-field watch to that exact identity. The string overload remains pending for an
unloaded class. `JvmStringHookManager` is a higher-level manager when the field descriptor is
`Ljava/lang/String;`.

## 9. Reading and controlling debugger stops

Always close every state and local value handle:

```java
List<JvmDebuggerState> states = jvmti.debuggerStates();
try {
    for (JvmDebuggerState state : states) {
        if (!state.paused()) continue;
        System.out.printf("%s %s.%s%s @%d line=%d%n",
                state.reason(), state.className(), state.methodName(),
                state.descriptor(), state.location(), state.sourceLine());

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

Control methods:

| API | Effect |
| --- | --- |
| `pauseExecution(thread)` | Suspend one thread at its current debuggable Java location |
| `continueExecution(thread)` | Resume one debugger-paused thread |
| `continueAllExecutions()` | Resume all debugger-paused threads |
| `stepInstruction(thread)` | Resume and stop at the next Java bytecode event |
| `stepOut(thread)` | Run until the selected frame returns, then stop in the caller |
| `debuggerLocals(thread, depth)` | Read LocalVariableTable entries, with inferred slots as fallback |
| `setDebuggerLocal(...)` | Replace a live local slot using its concrete descriptor |
| `forceEarlyReturn(thread, value)` | Return immediately from a compatible Java frame |
| `forceEarlyReturnVoid(thread)` | Return immediately from a void Java frame |

Example local replacement:

```java
try (RemoteObject replacement = session.jni().valueOf("replacement")) {
    jvmti.setDebuggerLocal(state.thread(), 0, local.slot(),
            local.descriptor(), replacement);
}
```

The standard JVMTI API exposes locals and frame locations, not the live JVM operand stack. Native,
opaque, obsolete, and out-of-scope frames can reject local or early-return operations.

### Thread inventory and analysis freeze

`jvmti.threads()` returns closeable `RemoteJvmtiThread` snapshots. It also exposes thread state,
stack, CPU time, owned monitors, the contended monitor, suspend/resume, interrupt and frame-pop
notification. `session.debugger().freeze()` safely pauses eligible application threads while
excluding JVMRTDP and sensitive JVM service threads; `restore()` resumes only threads owned by that
freeze.

## 10. Writing a `JvmtiEventHandler`

The handler class runs inside the target JVM. It receives ordinary target-JVM Java objects, not
controller-side `RemoteObject` handles.

```java
package example;

import nhcm.jvmrtdp.api.jvmti.JvmtiCategorizedEventHandler;
import nhcm.jvmrtdp.api.jvmti.JvmtiEvent;
import nhcm.jvmrtdp.api.jvmti.JvmtiMethodArgument;
import nhcm.jvmrtdp.api.jvmti.JvmtiMethodEvent;

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

Deploy and register it from the controller:

```java
String source = new String(Files.readAllBytes(Paths.get("AuditHook.java")),
        StandardCharsets.UTF_8);

try (RemoteCodeDeployment deployment = session.instrumentation().deploySource(
            "audit", "example.AuditHook", source);
     RemoteJvmtiCallback callback = session.instrumentation().hook(
            deployment, "example.AuditHook",
            EnumSet.of(JvmtiEventType.METHOD_ENTRY,
                    JvmtiEventType.METHOD_EXIT,
                    JvmtiEventType.FIELD_MODIFICATION),
            false)) {
    // The registration is active in this scope.
    callback.disable();
    callback.enable();
    callback.resetStatistics();
}
```

The callback object must have a public no-argument constructor. `RemoteJvmtiCallback.close()`
unregisters it; closing its deployment also removes callbacks associated with that deployment.
Inspect `jvmti.callbacks()` for per-registration counters and `callbackStatistics()` for aggregate
delivery, native queue and drop counters.

### Synchronous versus asynchronous delivery

- Use asynchronous (`false`) for logging, metrics and inspection. Delivery uses one bounded daemon
  queue, preserves dispatcher order, and does not block the application callback thread.
- Use synchronous (`true`) only when the callback must complete before the event thread proceeds.
  Class-file transformers require synchronous semantics to return replacement bytes.
- Callback exceptions are recorded in statistics and do not cross back into application code.
- Avoid blocking I/O, unbounded work, application locks and operations that recursively generate
  the same event. Method callbacks suppress JVMRTDP's own recursive capture path, but user code can
  still create expensive event storms.

### Method event values

`JvmtiMethodEvent` is used only for `METHOD_ENTRY` and `METHOD_EXIT`:

- `receiverAvailable()` must be checked before `receiver()`. Static methods have no receiver and
  report `hasReceiver() == false`.
- `arguments()` contains descriptor, JVM slot, optional LocalVariableTable name, boxed primitive or
  object value, and an error. Check each argument's `available()`; native/opaque frames and missing
  local access can make individual arguments unavailable.
- `nativeMethod()` and `staticMethod()` describe the target method.
- `poppedByException()` distinguishes exceptional exit. `normalExit()` is false in that case.
- `returnValueAvailable()` is true only for a normal, non-void exit. A Java null return is still a
  valid available value represented by Java null.

## 11. `JvmtiEvent` payload reference

Accessors that do not apply to an event return null, zero, or an empty value. Test `type()` before
interpreting `subject()`, `secondarySubject()`, `value()`, or `location()`.

| Event type | Main payload |
| --- | --- |
| `VM_INIT` | `thread` |
| `VM_START`, `VM_DEATH`, `DATA_DUMP_REQUEST`, `GARBAGE_COLLECTION_START`, `GARBAGE_COLLECTION_FINISH` | Type/timestamp only |
| `THREAD_START`, `THREAD_END` | `thread` |
| `CLASS_LOAD`, `CLASS_PREPARE` | `thread`, `className`, `subject` = `Class<?>` |
| `CLASS_FILE_LOAD_HOOK` | For event handlers, `subject` = `JvmtiClassFileEvent`; transformers receive that type directly |
| `SINGLE_STEP`, `BREAKPOINT` | `thread`, method identity, `location` = BCI |
| `FRAME_POP` | `thread`, method identity, `value` = popped-by-exception flag |
| `METHOD_ENTRY`, `METHOD_EXIT` | Delivered as `JvmtiMethodEvent`; see the previous section |
| `FIELD_ACCESS` | `thread`, `className` = field owner, current method/location, `subject` = receiver, member name/descriptor |
| `FIELD_MODIFICATION` | Field-access payload plus `secondarySubject` for an object/array new value; `value` contains primitive bits |
| `EXCEPTION` | Throwing method/location, `subject` = exception; `related*` = catch method/location when known |
| `EXCEPTION_CATCH` | Catching method/location, `subject` = exception |
| `MONITOR_CONTENDED_ENTER`, `MONITOR_CONTENDED_ENTERED` | `thread`, `subject` = monitor object |
| `MONITOR_WAIT` | Monitor payload, `value` = timeout milliseconds |
| `MONITOR_WAITED` | Monitor payload, `value` = timed-out flag |
| `VM_OBJECT_ALLOC` | `thread`, allocated `className`, `subject` = object, `value` = allocation size |
| `NATIVE_METHOD_BIND` | `thread`, method identity, `location` = native address |
| `COMPILED_METHOD_LOAD` | Method identity, `location` = code address, `value` = code size, `relatedLocation` = map length |
| `COMPILED_METHOD_UNLOAD` | Method identity, `location` = old code address |
| `DYNAMIC_CODE_GENERATED` | `text` = code name, `location` = address, `value` = byte length |
| `OBJECT_FREE` | `value` = the object's former JVMTI tag; no object reference is available |
| `RESOURCE_EXHAUSTED` | `value` = JVMTI resource flags, `text` = description |

Event groups for `JvmtiCategorizedEventHandler` are `VM`, `THREAD`, `CLASS`, `EXECUTION`,
`METHOD`, `FIELD`, `EXCEPTION`, `MONITOR`, `NATIVE_CODE`, `HEAP`, `GARBAGE_COLLECTION`, and
`RESOURCE`. Override only the category methods you need.

## 12. Class-file transformers

```java
package example;

import nhcm.jvmrtdp.api.jvmti.JvmtiClassFileEvent;
import nhcm.jvmrtdp.api.jvmti.JvmtiClassFileTransformer;

public final class ServiceTransformer implements JvmtiClassFileTransformer {
    @Override public byte[] transform(JvmtiClassFileEvent event) {
        if (!"com.example.Service".equals(event.className())) return null;
        byte[] original = event.classBytes();
        return transformWithAsm(original); // complete, verifier-valid class file
    }
}
```

```java
try (RemoteCodeDeployment deployment = instrumentation.deploySource(
            "service-transformer", "example.ServiceTransformer", source);
     RemoteJvmtiCallback transformer = instrumentation.transformer(
            deployment, "example.ServiceTransformer", true)) {
    instrumentation.retransform("com.example.Service");
}
```

Return null to keep the current bytes. Multiple transformers run in registration order and each
receives the previous transformer's result. `classBeingRedefined()` is null on first definition and
non-null on redefine/retransform. A transformer must return a complete class file and remains
subject to HotSpot schema restrictions.

Deployment choices:

- `DefinitionMode.CHILD`: define helper classes in an isolated child loader whose parent is the
  anchor's loader (or system loader when no anchor is supplied).
- `DefinitionMode.SAME_LOADER`: define helper classes directly in the exact anchor class loader;
  use this when edited application bytecode must link to the helper.
- `JarScope.CHILD`: private child-loader JAR; `SYSTEM` and `BOOTSTRAP` append to the corresponding
  JVM search path and have process-wide effects.

## 13. Transactional bytecode editing

```java
JvmBytecodeEditor editor = session.instrumentation().bytecode();

editor.stage(JvmBytecodePatch.builder("com.example.Service")
        .delete("check", "()V", 4, 11)
        .insertBeforeReturns("value", "()I",
                "DUP ;; INVOKESTATIC example/Trace onInt (I)V")
        .addExceptionHandler("value", "()I", 0, 20, 24,
                "java/lang/RuntimeException")
        .build());

for (JvmExceptionHandlerInfo handler :
        editor.exceptionHandlers("com.example.Service", "value", "()I")) {
    System.out.println(handler.index() + " " + handler.startBci()
            + ".." + handler.endBci() + " -> " + handler.handlerBci());
}

byte[] staged = editor.classBytes("com.example.Service");
JvmBytecodePatchResult installed = editor.flush("com.example.Service");
```

`stage` composes multiple provisional edits without redefining the live class. `flush` recomputes
frames/max values, updates exception ranges, verifies the full transaction, redefines once and
relocates managed breakpoints. `discard` drops staged bytes. `undo`/`redo` apply to installed class
history. `apply` and `editMethod` are immediate compatibility operations; prefer staging for
multi-instruction edits. `stageMethod` exposes an ASM `MethodNode` for instructions not supported
by the text assembler, including advanced `INVOKEDYNAMIC` construction.

## 14. References and String hooks

`JvmReferenceManager` tracks object snapshots and live field slots across Context changes:

```java
session.references().trackObject("service", service,
        JvmReferenceStrength.WEAK);
session.references().trackStaticField("current", staticField);

for (JvmReferenceInfo info : session.references().refreshAll()) {
    System.out.println(info.name() + " " + info.state());
}
try (RemoteObject current = session.references().acquire("current")) {
    // acquire() returns a new caller-owned strong handle.
}
```

States are `LIVE`, `NULL`, `COLLECTED`, `RELEASED`, and `ERROR`. `replace` and `setNull` write
through field-backed entries. Weak tracking does not consume a JVMTI tag.

`JvmStringHookManager` combines conditional String allocation stops, String field watches, and
String-bearing method event breakpoints:

```java
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
                .maximumHits(10)
                .sampleEvery(2)
                .build());
```

Allocation filtering happens synchronously in the target before a debugger stop. The content glob
is evaluated first. Creator components match one frame, but any frame may satisfy them; no full
stack walk is performed when all creator patterns are wildcards. `FAST` (the default) temporarily
adds lightweight probes to `String.<init>` returns and prefilters content in Java before
native/JVMTI work; it does not subscribe to global method-exit or allocation events. `COMPLETE`
adds `VM_OBJECT_ALLOC` for VM/native-created or already JIT-intrinsified Strings and therefore
has JVM-wide allocation-event overhead. One physical object is de-duplicated across both paths.
`oneShot()`, `maximumHits(long)`, and `sampleEvery(int)` bound stop frequency; the hot path is
disabled when no allocation hook remains armed. A hit has reason
`string_alloc:<registration>`,
`returnState() == "allocation"`, and exposes the matched String through `eventValue()`.

Field-backed hooks support `acquireValue`, `replaceValue`, and `trackValue`. Allocation hooks
support `acquireValue` and `trackValue` for the latest match. Pass debugger states
to `observe(...)` in a custom UI to update last-hit metadata. Replacing a String changes the owning
field reference; it does not mutate String internals.

Fast hooks require breakpoint, bytecode-reading, and local-variable capabilities. Complete hooks
also require `CAN_GENERATE_VM_OBJECT_ALLOC_EVENTS`. JVMRTDP acquires capabilities while they remain
potential; start with `-agentpath` when HotSpot no longer reports one as potential after dynamic
attach. Prefer fast mode, narrow patterns, sampling, and hit limits for production-like targets.

## 15. Remaining typed JVMTI services

`RemoteJVMTIEnv` also exposes:

- Class/method/field metadata, bytecodes, line tables, source debug extension and constant pool.
- Timers, current/thread CPU time, processor count, system properties and verbose flags.
- Thread state/info, stack frames, frame count, owned/contended monitors, interrupt and frame-pop.
- Object size, identity hash, monitor usage, tags, tag lookup and forced GC.
- Loaded-class bytes, dump, redefine, retransform, deployment lists and callback statistics.

These calls map closely to JVMTI and can fail with a named `JVMTI_ERROR_*` when the phase,
capability, object, frame or method does not support the operation. Consult
[JVMTI API coverage](JVMTI-API-COVERAGE.md) for the native function-by-function support matrix.

## 16. Error and lifecycle checklist

- Check `session.nativeAvailable()` before native-only work and inspect `nativeDescription()` when
  the controller JAR and target DLL may be mismatched.
- Use `JvmRtdpCommandResult.requireSuccess()` for exception-style command handling.
- Close every caller-owned target object returned from a read, call, construct, local, debugger
  state, thread list or tag lookup.
- Clear breakpoints/watches and close callbacks before releasing receiver-specific handles.
- Do not invoke controller-side APIs from a target-side callback; they execute in different JVMs.
- Do not assume method arguments, locals, source lines or return values are always available.
- Do not expect redefine to add/remove fields or methods or change inheritance.
- Serialize Context/workspace/debugger mutations unless the API explicitly documents concurrency.

<!-- English is the canonical Java API reference. -->
