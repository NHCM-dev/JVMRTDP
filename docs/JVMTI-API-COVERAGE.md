# JVMTI API Coverage

[English](JVMTI-API-COVERAGE.md) | [中文](JVMTI-API-COVERAGE_ZH.md)

JVMRTDP exposes commonly used JVMTI diagnostics and debugging features through a native agent and Java bridge. This page describes public support by functional area and identifies capabilities limited by JVM phases or the safety model.

## Capability Model

- At startup, the agent requests available capabilities during the `OnLoad` phase.
- Dynamic attach occurs during the `LIVE` phase and can acquire only capabilities still reported as potential.
- `jvmti capability add <name...>` uses the standard `AddCapabilities` function.
- `jvmti capability relinquish <name...>` uses the standard `RelinquishCapabilities` function.
- JVMRTDP does not modify HotSpot's internal capability tables or promise to bypass JVM phase restrictions.

Load the agent at JVM startup with `-agentpath` when method-entry events, breakpoints, local variables, retransformation, or other full debugging capabilities are required.

## Supported Functional Areas

### Environment and Capabilities

- Current, potential, and missing capability queries
- JVMTI phase, time, timers, processor count, and location format
- System property reads and writes
- `other`, `gc`, `class`, and `jni` verbose flags

### Classes, Methods, and Fields

- Loaded classes, signatures, status, modifiers, class loaders, and interfaces
- Classes visible to a class loader, Source Debug Extension, and constant pool
- Method names, signatures, modifiers, declaring classes, maximum locals, and argument counts
- Method bytecode, line-number tables, and local-variable tables
- Field names, signatures, modifiers, and declaring classes
- Class retransformation and class redefinition within HotSpot schema limits

### Threads, Frames, and Monitors

- Thread enumeration, information, state, and thread groups
- Stack frames, frame counts, frame locations, and thread CPU time
- Local-variable reads
- Thread suspend, resume, and interrupt
- Owned monitors, contended monitors, and monitor usage
- Frame-pop notification

### Objects, Tags, and Heap

- Object size and identity hash
- Object tag read, write, and lookup by tag
- Forced garbage collection

### Debugging and Events

- Breakpoint set and clear
- Field access and modification watchpoints
- Single-step, method entry/exit, exception, thread, class, monitor, GC, and VM events
- Persistent method-entry, method-exit, and exception event breakpoints
- Step into, step out, local-variable writes, and early return from paused Java frames
- Event notification enable/disable and selected generated events
- Java callback deployment and event dispatch

See the [Command Reference](COMMANDS.md) for complete command syntax.

## Event Callbacks

Callbacks are grouped by purpose:

- Lifecycle: VM, thread, and class events
- Execution: method entry/exit, exceptions, single-step, breakpoints, and frame pop
- Data: field access and modification
- Synchronization: monitor wait, enter, and contention events
- Runtime: GC, object free, compilation, dynamic code, and resource exhaustion

Callback handlers must be static and use a descriptor compatible with the event payload. High-frequency events may materially affect target performance and should use narrow class, method, or thread filters.

## Standard API Limits

- JVMTI does not expose the current JVM operand stack; `maxStack` is only the maximum depth declared by the class file.
- Native frames have no Java BCI, bytecode, or local variables.
- `ForceEarlyReturn` applies only to a suspended Java frame. A `METHOD_EXIT` callback is too late to replace the completed return value.
- Without a `LocalVariableTable`, locals can only be sampled by slot and inferred type.
- HotSpot class redefinition cannot arbitrarily add, remove, or structurally change fields and methods.
- Standard JVMTI cannot force a capability that is no longer potential during the `LIVE` phase.

## Operations Not Publicly Exposed

The following low-level interfaces are not regular user commands:

- Direct heap iteration and reference-graph traversal
- Raw JNI function-table replacement
- Raw memory allocation functions
- Raw monitor creation and destruction
- Extension function and extension event enumeration
- TLS, environment disposal, and other internal agent-lifecycle operations

These interfaces require additional lifecycle, callback, and concurrency guarantees. JVMRTDP favors controlled high-level commands that provide equivalent diagnostic results.

## Java Library API

An attached `JvmRtdpSession` exposes typed low-level control through `session.jvmti()` and a high-level deployment/instrumentation facade through `session.instrumentation()`. Library code can query capabilities, inspect threads and locals, set bytecode/event breakpoints, force early returns, deploy handlers or transformers, and retransform/redefine classes without parsing CLI output.

```java
try (JvmRtdpClient client = JvmRtdpClient.open();
     JvmRtdpSession session = client.attach(pid)) {
    for (JvmtiCapabilityStatus status : session.jvmti().capabilityStatuses()) {
        System.out.println(status);
    }
}
```

Close thread, object, callback, and deployment handles deterministically. Closing the session restores debugger-owned frozen threads before closing the protocol connection. See the [Java Library Guide](LIBRARY.md).

## Compatibility

- The native bridge uses the Java 8 JVMTI ABI as its compatibility baseline.
- Potential capabilities may differ across JDK distributions.
- The JAR and native DLL should come from the same build.
- Restart the target JVM after replacing a preloaded DLL.
