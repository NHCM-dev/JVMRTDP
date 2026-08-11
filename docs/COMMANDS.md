# JVMRTDP Command Reference

[English](COMMANDS.md) | [中文](COMMANDS_ZH.md)

This document describes the TUI, command line, and debugger commands in JVMRTDP 2.0.0. Values such as `<pid>`, `<class>`, `<method>`, and `<file>` are placeholders.

## 1. Conventions

- JVMRTDP starts in the TUI by default; use `--cli` to start at the command line.
- The controller prompt is `jvmrtdp>`.
- After connecting, the prompt is `target[<pid>|<context>]>`.
- Quote arguments that contain spaces or JVM descriptors.
- Class names normally use `com.example.Type`; descriptors use JVM notation such as `([Ljava/lang/String;)V`.
- `help` lists commands for the current context; `help syntax` describes value expressions.

## 2. Startup and Sessions

```powershell
java -jar JVMRTDP-2.0.0.jar
java -jar JVMRTDP-2.0.0.jar --cli
```

Controller commands:

```text
ps | list
attach <pid>
inject <pid>
tui
version | ver
help
exit | quit
```

Target-session commands:

```text
help [syntax]
back
tui
version
exit
```

`back` disconnects from the target and returns to the process list. `exit` closes JVMRTDP.

## 3. TUI Keys

The footer lists actions available in the current view. CLI and TUI are two interaction layers over one target session, so switching between them preserves context and TUI analysis/debugger views.

| Key | Action |
| --- | --- |
| `Up` / `Down` | Move selection |
| `PgUp` / `PgDn` | Move by page |
| `Home` / `End` | First or last item |
| `Tab` | Switch views |
| `Enter` | Open or run the default action |
| `Backspace` | Return to the parent context or package |
| `Left` / `Right` | Scroll horizontally |
| `[` / `]` | Scroll horizontally faster |
| `0` | Reset horizontal position |
| `/` | Filter the current list; `Esc` cancels |
| `f` | Find only in the currently displayed Browse/Fields/Methods list |
| `F` | Search all loaded classes, fields, methods, and packages |
| `:` | Enter an exact class, package, field, or method target |
| `@` | Show or hide static members in Fields/Methods |
| `#` | Show or hide instance fields and virtual methods in Fields/Methods |
| `=` | Set a field, paused local, or writable context source |
| `x` / `X` | Invoke a selected method virtually / exact declaring implementation |
| `n` / `N` | Next or previous match |
| `P` | Enter a package name |
| `J` | Show or hide JDK types |
| `a` | Show or hide array types in Browse |
| `A` | Decompile the selected or current class |
| `K` | Show or hide `<init>` / `<clinit>` |
| `O` | Export the current content |
| `F2` | Switch to the CLI |
| `Q` | Go back or exit |

Debugger keys:

| Key | Action |
| --- | --- |
| `F9` | Set or clear a normal breakpoint at the selected BCI |
| `Shift+F9` | Set or clear a breakpoint limited to the current object Context |
| `F7` | Step |
| `F8` | Continue the current thread |
| `F6` | Pause the selected thread |
| `F4` | Toggle live following while the target runs |
| `T` | Open the thread list |
| `G` | Go to the current execution location |
| `M` | Open locals |
| `Z` | Open breakpoints |
| `*` | Freeze or restore the analysis thread set |

## 4. Context and Stack

Operations are centered on the current context, which may be a class, static context, object, field value, array element, or debugger local.

```text
context
context class <class>
context <class>
context static field <class> <field[index]>
context field [declaring.Class::]<field[index]>
context index <index>
context value <literal>
context value <type> <literal>
context as <parent-or-interface>
context runtime
context list fields [glob]
context list methods [glob]
context save <name>
context use <name>
context bookmarks
context clear
```

Context stack:

```text
stack [list [limit]]
stack depth
stack peek [index]
stack pop [count]
stack dup
stack push
stack swap
stack pick <index>
stack clear
```

`stack pop`, `stack back`, and `stack drop` are equivalent. Stack index `0` is the top.

## 5. Value Expressions

Common literals:

```text
null
true | false
123
123L
3.14F
3.14D
'A'
"text"
```

Explicit types:

```text
context value int 42
context value java.lang.String "text"
```

Composable expressions:

```text
{new <class> [descriptor] [args...]}
{invoke <method> [descriptor] [args...]}
{static <class> <method> [descriptor] [args...]}
{field [declaring.Class::]<field>}
{static-field <class> <field>}
{index <index>}
```

Expressions may be nested. Supply a descriptor when overload resolution is ambiguous.

Use `resolve <literal|reference|{value-expression}>` to evaluate a value without changing the context.

## 6. Objects, Fields, and Calls

```text
value [--deep [limit]]
field [declaring.Class::]<field[index]>
read [field] [declaring.Class::]<field[index]>
read static [field] [class] <field[index]>
read index <index>
set [field] [declaring.Class::]<field> <value>
set index <index> <value>

static field [class] <field[index]>
static set [class] <field> <value>
static invoke [class] <method> <descriptor> [args...]

invoke [declaring.Class::]<method> <descriptor> [args...]
construct [class] <descriptor|auto> [args...]
new [class] <descriptor|auto> [args...]
```

Use `DeclaringClass::member` for inherited members. Use `[index]` to disambiguate duplicate names in a list.

Arrays and collections:

```text
array length
array get <index> [--deep [limit]]
array set <index> <value>
array list [limit]
```

`value` reports size information when available for arrays, `Collection`, `Map`, `Iterable`, and `CharSequence` values.

`debug` prints identity, shape, size, and reflection counts for the current object. `stats` reports loaded classes, threads, handles, heap use, and uptime.

## 7. Class Browsing, Search, and Export

```text
class
class load <class>
class info
class fields [all|static|virtual] [glob]
class methods [all|static|virtual] [glob]
class constructors

package [name]
find package [glob] [--limit n]
find <class|interface|enum|annotation|array> [name-glob] [--package glob] [--extends glob] [--implements glob] [--limit n]
find <extends|implements> <type-glob> [name-glob] [--limit n]
find field [name-glob] [--class glob] [--type glob] [--static|--virtual] [--limit n]
find method [name-glob] [--class glob] [--returns glob] [--params glob] [--static|--virtual] [--limit n]
```

`class load` performs an operation equivalent to `Class.forName` in the target JVM and may initialize the class.

Export and dump commands:

```text
export [append] <file> [command ...]
dumpclass [class] <output.class>
dumpclass package <name|.> <directory> [--recursive|--no-recursive] [--match glob] [--limit n]
```

`export` runs the supplied command and writes its UTF-8 output; without a command it exports `value --deep`. Class files use `dumpclass`. Decompilation accepts `--out`, and debugger snapshots provide structured output.

## 8. Decompilation and Bytecode

```text
decompile class [class] [--engine cfr|procyon] [--out <file>]
decompile method [class] <method> <descriptor> [--engine cfr|procyon] [--out <file>]
bytecode [class] <method> <descriptor> [--out <file>]
```

Method decompilation identifies a method by name and full descriptor. Views support search, line/BCI navigation, breakpoints, and export.

Native and abstract methods have no `Code` attribute. The method information panel reports `NATIVE`, `ABSTRACT`, or `BYTECODE` explicitly.

## 9. Debugger

### 9.1 State and Events

```text
debugger status
debugger enable
debugger disable
debugger locations
debugger locations
```

Startup `break-main` and `break-clinit` stops are one-shot entry pauses. Normal breakpoints are reinstalled after a hit and remain active until cleared.

### 9.2 Breakpoints

```text
debugger break <class> <method> <descriptor> <bci>
debugger clear <class> <method> <descriptor> <bci>
debugger break-context <method> <descriptor> <bci> [caller-class [caller-method [caller-descriptor]]]
debugger clear-context <method> <descriptor> <bci> [caller-class [caller-method [caller-descriptor]]]
debugger breakpoints
debugger breakpoints clear-all
```

Explicit class breakpoints apply to every receiver. `break-context` uses the current object as an identity condition for instance methods; from a class context (and for static methods) it applies globally. Caller patterns accept `*` and `?`. The same typed condition is available to library clients as `JvmBreakpointCondition`.

### 9.3 Field Watchpoints

```text
debugger watch read set <class> <field> <descriptor>
debugger watch write set <class> <field> <descriptor>
debugger watch read clear <class> <field> <descriptor>
debugger watch write clear <class> <field> <descriptor>
debugger watches
debugger watches clear-all
```

Watchpoints stop on field reads or writes. Invocation sites can be observed by breaking at the target method entry or at a BCI containing an invocation instruction.

### 9.4 Threads, Frames, and Locals

```text
debugger threads
debugger pause <all-thread-index>
debugger pause-all
debugger frames [paused-index] [max]
debugger stack [paused-index] [max]
debugger locals [paused-index] [depth]
debugger local-context <paused-index> <depth> <slot>
debugger local-set <paused-index> <depth> <local-index> <value>
debugger current [paused-index] [depth] [radius]
debugger sample <all-thread-index> [depth] [radius]
```

`all-thread-index` comes from the complete `debugger threads` list; `paused-index` comes from the paused-thread list. `local-context` stores a local value as a writable current context; `set context` or `local-set` writes through to the paused frame.

Without a `LocalVariableTable`, JVMRTDP attempts to read slots up to `maxLocals`. Dead slots, continuation slots for two-slot values, and values with unknown types are reported with a reason.

`sample` briefly suspends a running thread, captures its location, stack, and locals, and restores its previous state. Use it to locate threads that are running, waiting, or entering new methods.

### 9.5 Stepping and Continue

```text
debugger step [paused-index]
debugger continue [paused-index|all]
```

### 9.6 Analysis Freeze and Snapshots

```text
debugger freeze
debugger freeze refresh
debugger freeze-status
debugger thaw
debugger snapshot <file|-> [json|jsonl] [max-frames] [locals-depth]
```

Freeze records the original thread states, excludes sensitive threads such as agent services and the Attach Listener, and resumes only threads suspended by the active freeze. Snapshots support offline analysis and external tools.

### 9.7 Debugging Limits

- Standard JVMTI does not expose current operand-stack values.
- Native frames have no Java BCI or locals.
- Optimization, missing debug tables, and dead slots may limit local-variable access.
- Suspending UI, GC, class-loading, or lock-related threads may affect target responsiveness.

## 10. JVMTI

Capabilities and runtime environment:

```text
jvmti capabilities
jvmti capability-status
jvmti capability add <name...>
jvmti capability relinquish <name...>
jvmti phase
jvmti time
jvmti timer-info
jvmti current-thread-cpu-time
jvmti processors
jvmti location-format
jvmti property get <name>
jvmti property set <name> <value>
jvmti verbose <other|gc|class|jni> <enable|disable>
```

Classes, methods, and fields:

```text
jvmti class info <class>
jvmti class interfaces <class>
jvmti class loader-classes <class>
jvmti class source-debug <class>
jvmti class constant-pool <class>
jvmti method info <class> <method> <descriptor>
jvmti method bytecodes <class> <method> <descriptor>
jvmti method lines <class> <method> <descriptor>
jvmti field info <class> <field> <descriptor>
```

Events and class transformation:

```text
jvmti events
jvmti events generate <event>
jvmti retransform <class>
jvmti redefine <class> <class-file>
jvmti breakpoint set <class> <method> <descriptor> <location>
jvmti breakpoint clear <class> <method> <descriptor> <location>
jvmti watch access <set|clear> <class> <field> <descriptor>
jvmti watch modification <set|clear> <class> <field> <descriptor>
```

Threads, monitors, and objects:

```text
jvmti threads [prefix] [limit]
jvmti thread info <object>
jvmti thread state <object>
jvmti thread stack <object> [max]
jvmti thread frame-count <object>
jvmti thread cpu-time <object>
jvmti thread owned-monitors <object>
jvmti thread contended-monitor <object>
jvmti thread suspend|resume|interrupt <object>
jvmti thread frame-pop <object> [depth]
jvmti size <object>
jvmti hash <object>
jvmti monitor-usage <object>
jvmti tag <object> [value]
jvmti tagged <tag> [prefix] [limit]
jvmti gc
jvmti properties
```

Dynamic attach occurs in the `LIVE` phase and can add only capabilities still reported as potential. See [JVMTI API Coverage](JVMTI-API-COVERAGE.md) for details.

## 11. Java Deployment and Callbacks

```text
code source <name> <file|dir> [options]
code methods <name> <class> <file> [options]
code jar <name> <jar-file> [options]
code list
code run <deployment-id> <class> <method> <descriptor> <static|this|object-ref> [args...]
code close <deployment-id>
code callback add <deployment-id> <handler-class> <event,event,...> [sync|async]
code callback remove <callback-id>
code callback enable <callback-id>
code callback disable <callback-id>
code callback reset <callback-id>
code callback list
code callback stats
```

Callbacks cover thread, class, method, exception, field, monitor, GC, compilation, and resource events. Registrations can be enabled, disabled, reset, listed, and removed without redeploying the handler. Lists include delivery counters, the last event, and the last failure. Apply narrow conditions and keep high-frequency handlers lightweight.

Deployment options include `--anchor`, `--same-loader`, `--child`, `--scope`, `--classpath`, `--javac`, and Java compiler source/release options.

## 12. Batch and Scripts

```text
batch <commands.txt>
script <file.jrd>
```

A batch file contains one target command per line and stops when a command closes the session. Scripts add variables, conditional branches, labels, and reference management; see the [Scripting Guide](SCRIPTING.md).

## 13. Java Library API

Every context-oriented command in this reference can be executed through an attached library session:

```java
JvmRtdpCommandResult result = session.execute(
        "bytecode com.example.Application main ([Ljava/lang/String;)V");
result.requireSuccess();
System.out.print(result.standardOutput());
```

Use `session.executeAgent()` only for commands implemented directly by the target agent, such as `ping`, `info`, and `native`. Controller-side commands such as `context`, `find`, `decompile`, and `debugger` use `session.execute()` or the corresponding typed API.

The following library accessors avoid textual output:

```text
session.jni()
session.jvmti()
session.operations()
session.context()
session.workspace()
session.debugger()
```

See the [Java Library Guide](LIBRARY.md) for dependencies, lifecycle rules, async calls, and complete examples.

## 14. Common Errors

- `JNI/JVMTI bridge is unavailable`: the JAR and native agent do not match, or the target did not load the agent correctly.
- `capability is not potential`: the capability cannot be acquired in the current phase; load the agent at startup with `-agentpath`.
- `JVMTI_ERROR_NOT_FOUND`: the breakpoint, watchpoint, or member does not exist or was already cleared.
- `NATIVE_METHOD`: the current frame is native and has no Java locals or BCI.
- `OPAQUE_FRAME`: the requested stack operation is not valid for the current frame.
- `INVALID_SLOT` / `TYPE_MISMATCH`: a local slot is invalid, dead, or has an incompatible type.
