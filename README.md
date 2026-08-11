# JVMRTDP

[English](README.md) | [中文](README_ZH.md)

JVMRTDP is a diagnostics, analysis, and debugging tool for HotSpot Java Virtual Machines (JVMs) on Windows x64. It injects an agent into a target JVM and exposes class browsing, object inspection, decompilation, bytecode views, breakpoints, thread control, and Java Virtual Machine Tool Interface (JVMTI) operations through a terminal user interface (TUI), a command line, and scripts.

> Use this tool only with processes you are authorized to inspect. Debugging, field writes, method calls, and class redefinition change the state of the target JVM.

## Features

- Discover and connect to local Java processes, including embedded JVMs.
- Browse runtime state by package, class, field, method, object, stack frame, and static context.
- Read and modify fields, arrays, and collections; invoke methods and constructors.
- Decompile classes or individual methods with CFR or Procyon.
- Inspect JVM bytecode, bytecode indexes (BCI), source lines, constant-pool references, and method metadata.
- Manage breakpoints, field access/modification watchpoints, stepping, and paused threads.
- Inspect threads, frames, locals, monitors, tags, object sizes, and JVMTI properties.
- Export classes, objects, bytecode, decompiled source, and debugger snapshots.
- Integrate automation through batch files, scripts, and structured output.

## Requirements

- Windows x64
- Java 8 or later
- Visual Studio 2022 C++ toolchain when building native components from source
- The complete build expects these adjacent source trees by default:
  - `../cfr`
  - `../procyon`

The controller, target JVM, and native agent must use compatible architectures. The current native target is x64.

## Build

```powershell
.\gradlew.bat build
```

Build outputs:

```text
build/libs/JVMRTDP-2.0.0.jar
build/libs/jvmrtdp-2.0.0-library.jar
build/native-output/agent/x64/Release/jvmrtdp-agent-build.dll
```

`JVMRTDP-2.0.0.jar` is the self-contained executable. `jvmrtdp-2.0.0-library.jar` is the dependency-friendly library artifact and keeps terminal dependencies external.

To publish the agent DLL to the conventional location:

```powershell
.\gradlew.bat publishAgentNative
```

Published location:

```text
natives/x64/Release/jvmrtdp-agent.dll
```

The agent embedded in the JAR and a DLL preloaded by the target JVM should come from the same build. Restart the target JVM after replacing the DLL.

## Quick Start

Start the default TUI:

```powershell
java -jar build\libs\JVMRTDP-2.0.0.jar
```

Start in command-line mode:

```powershell
java -jar build\libs\JVMRTDP-2.0.0.jar --cli
```

Basic CLI workflow:

```text
jvmrtdp> ps
jvmrtdp> attach <pid>
target[<pid>|<unset>]> context class com.example.Application
target[<pid>|com.example.Application]> class methods
```

In the TUI, select a process and press `Enter` to connect. Long-running operations execute in the background so the interface remains responsive.

## Load the Agent at Startup

Some JVMTI capabilities are available only during JVM startup. Load the agent with `-agentpath` when full debugging support is required:

```powershell
java -agentpath:<path-to-jvmrtdp-agent.dll> -jar application.jar
```

Pause at the `main` entry:

```powershell
java -agentpath:<path-to-jvmrtdp-agent.dll>=break-main=com.example.Application -jar application.jar
```

Pause at a class initializer:

```powershell
java -agentpath:<path-to-jvmrtdp-agent.dll>=break-clinit=com.example.Component -jar application.jar
```

Separate multiple startup options with commas:

```powershell
java -agentpath:<path-to-jvmrtdp-agent.dll>=break-main=com.example.Application,break-clinit=com.example.Component -jar application.jar
```

Class names may use dotted or JVM internal slash notation.

## TUI Navigation

The TUI browses by package and hides common JDK implementation types by default. The footer lists actions available in the current view.

| Key | Action |
| --- | --- |
| `Up` / `Down` | Move selection |
| `PgUp` / `PgDn` | Move by page |
| `Home` / `End` | Move to the first or last item |
| `Tab` | Switch views |
| `Enter` | Open the selected item |
| `Backspace` | Return to the parent context or package |
| `Left` / `Right` | Scroll horizontally |
| `[` / `]` | Scroll horizontally faster |
| `/` | Filter the current list; `Esc` cancels |
| `F` | Find classes, fields, or methods |
| `P` | Enter a package name |
| `J` | Show or hide JDK types |
| `A` | Show or hide array types |
| `K` | Show or hide `<init>` / `<clinit>` |
| `F2` | Switch to the CLI |
| `Q` | Go back or exit |

Main views:

- `browse`: packages and classes
- `context`: current class, object, static value, or stack context
- `fields` / `methods`: member browsing and operations
- `decompile`: decompiled class or method source
- `bytecode`: BCI, source lines, and instruction stream
- `debug`: current stop, threads, stack, and locals
- `frames` / `locals`: stack frames and local variables
- `breakpoints`: breakpoint management
- `threads`: all JVM threads and states

## Decompilation and Bytecode

```text
decompile class com.example.Application --engine cfr
decompile method com.example.Application main "([Ljava/lang/String;)V" --engine procyon
bytecode com.example.Application main "([Ljava/lang/String;)V"
```

Decompile and bytecode views support search, horizontal scrolling, line or BCI navigation, breakpoints, and export. Native and abstract methods have no JVM `Code` attribute and therefore expose no Java bytecode.

## Debugging

Set and inspect breakpoints:

```text
debugger enable
debugger break com.example.Application run "()V" 0
debugger breakpoints
```

Inspect and control threads:

```text
debugger threads
debugger pause <thread-index>
debugger current <paused-index> 0 12
debugger locals <paused-index> 0
debugger step <paused-index>
debugger continue <paused-index>
```

Sample a running thread:

```text
debugger sample <thread-index> 0 12
```

Freeze the target for analysis:

```text
debugger freeze
debugger snapshot debugger-snapshot.json json
debugger thaw
```

`freeze` excludes sensitive threads such as agent service threads and resumes only threads suspended by the active freeze operation.

In the TUI, `F8` continues the current thread. While the target is running, the debugger view briefly suspends the tracked thread at intervals to refresh the current method, BCI, stack, and locals. Press `F4` to disable live following.

## JVMTI Capabilities

Dynamic injection occurs during the JVMTI `LIVE` phase. At that point, the agent can add only capabilities that the JVM still reports as potential. Capabilities restricted to the `OnLoad` phase cannot be forced through standard JVMTI after startup.

Inspect capability state:

```text
jvmti phase
jvmti capabilities
jvmti capability-status
jvmti capability add can_generate_method_entry_events
```

The command reports a failure when a capability is no longer potential. Loading the agent at startup with `-agentpath` is preferred over modifying HotSpot's internal capability tables.

JVMTI can read frame locations and local variables, but the standard API does not expose the current JVM operand stack. The bytecode `maxStack` value is only the maximum depth declared by the class file.

## Automation

Batch mode executes target commands directly:

```text
batch commands.txt
```

The script language adds variables, labels, conditional branches, object references, and export:

```text
script workflow.jrd
```

See the [Scripting Guide](docs/SCRIPTING.md) for the full syntax. Use debugger snapshots in `json` or `jsonl` format when another program consumes debugger data.

## Java Library

Publish the current build to the local Maven repository:

```powershell
.\gradlew.bat publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
dependencies { implementation("nhcm.jvmrtdp:jvmrtdp:2.0.0") }
```

Discover and attach to a JVM from Java:

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

The library API provides process discovery, configurable attach, captured command results, asynchronous agent commands, and direct access to JNI, JVMTI, contexts, remote objects, decompilation, and debugger services. See the [Java Library Guide](docs/LIBRARY.md).

## Safety and Behavior

- The agent listens only on the loopback interface and generates a random token for each session.
- Debugging, thread suspension, method calls, field writes, GC, and class redefinition affect the target application.
- Object handles are generally strong references; release handles that are no longer needed during long sessions.
- HotSpot schema restrictions limit class redefinition; fields and methods cannot be added or removed arbitrarily.
- Invoking a blocking method remotely blocks that request until the method returns.
- Validate required capabilities and operations in an equivalent test environment before production use.

## Documentation

- [Command Reference](docs/COMMANDS.md)
- [Scripting Guide](docs/SCRIPTING.md)
- [JVMTI API Coverage](docs/JVMTI-API-COVERAGE.md)
- [Java Library Guide](docs/LIBRARY.md)
