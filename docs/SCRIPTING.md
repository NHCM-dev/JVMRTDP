# JVMRTDP Scripting Guide

[English](SCRIPTING.md) | [中文](SCRIPTING_ZH.md)

JVMRTDP supports batch files for sequential commands and `.jrd` scripts for reusable workflows with variables and branches.

## 1. Run a File

```text
batch commands.txt
script workflow.jrd
```

| Mode | Purpose |
| --- | --- |
| `batch` | Execute one regular target command per line |
| `script` | Execute typed variables, calls, branches, exports, and embedded commands |

Both formats use UTF-8. Empty lines and lines beginning with `#` are ignored. Inline comments are not supported.

## 2. Quoting

Use double quotes for arguments that contain spaces. Supported escapes include `\n`, `\r`, `\t`, `\\`, and `\"`.

```text
value message string "hello world"
print @message
```

Instruction names are lowercase. Variable and label names are case-sensitive.

## 3. Workspace References

The workspace stores:

- `$name`: class reference
- `@name`: object or boxed primitive reference

Definitions do not require a prefix:

```text
class service com.example.Service
value count int 3
```

Object handles generally retain strong references in the target JVM. Use `release` when an object is no longer needed. Workspace values remain available for the target session unless replaced, released, or the session closes.

## 4. Instructions

| Instruction | Syntax |
| --- | --- |
| Class | `class <name> <binary-class-name>` |
| Value | `value <name> <type> <literal>` |
| Construct | `construct <name> <class-ref> <descriptor> [args...]` |
| Call | `call <dest> <receiver> <method> <descriptor> [args...]` |
| Get | `get <dest> <receiver> <field>` |
| Set | `set <receiver> <field> <value-ref>` |
| Print | `print <token...>` |
| Export | `export <class|value> <ref> <file>` |
| Export context | `export context <file>` |
| Conditional | `if <ref> goto <label>` |
| Null check | `ifnull <ref> goto <label>` |
| Switch | `switch <ref> <value=label...> [default=label]` |
| Jump | `goto <label>` |
| Command | `command <target-command...>` |
| Release | `release <object-ref...>` |

Define labels with a leading colon:

```text
:success
```

## 5. Values

Supported value types:

```text
null string boolean byte short int long float double char bytes
```

`bytes` uses Base64 text. A `char` value must contain exactly one character.

```text
value empty null
value enabled boolean true
value retries int 3
value timeout long 5000
value letter char A
value payload bytes SGVsbG8=
```

## 6. Construct and Call

Construct an object:

```text
class list java.util.ArrayList
construct items $list "()V"
```

Call an instance method:

```text
call count @items size "()I"
```

Call a static method with `static:<class-ref>`:

```text
class integer java.lang.Integer
value number int 42
call text static:$integer toString "(I)Ljava/lang/String;" @number
```

Select a declaration from a parent class or interface with `DeclaringClass::member`:

```text
call text @target java.lang.Object::toString "()Ljava/lang/String;"
```

Descriptors are required in script `construct` and `call` instructions. All arguments are workspace object references.

## 7. Fields

Instance fields:

```text
get current @service state
set @service state @next
```

Static fields use `static:<class-ref>`:

```text
get current static:$service INSTANCE
set static:$service ENABLED @enabled
```

Inherited fields may use `DeclaringClass::field`.

## 8. Print and Export

`print` accepts variables and literal tokens:

```text
print "count=" @count
```

Export a class file:

```text
export class $service output/Service.class
```

Export an object's display value:

```text
export value @service output/service.txt
export context output/current.txt
```

For structured debugger output, execute the regular snapshot command:

```text
command debugger snapshot output/debugger.json json
```

## 9. Branches

```text
call valid @service isValid "()Z"
if @valid goto success
goto failed

:success
print "valid"
goto done

:failed
print "invalid"

:done
```

`if` treats null, empty text, `false`, `0`, and `null` text as false. `ifnull` checks only an actual null object.

`switch` compares the printable value exactly:

```text
switch @status READY=ready ERROR=failed default=unknown
```

Labels must be unique and defined. A script run is limited to 100,000 executed instructions.

## 10. Regular Commands

`command` makes CLI features available inside a script:

```text
command debugger status
command debugger freeze
command debugger snapshot output/debugger.json json
command debugger thaw
command jvmti capabilities
```

If a command requests the session to close, the script fails instead of continuing with invalid handles.

## 11. Release Objects

```text
release @items @service
```

`release` removes object variables and closes their remote handles. Class references are cleared when the session closes.

## 12. Complete Example

```text
# Create a list, add two values, and print its size.
class list java.util.ArrayList
construct items $list "()V"

value first string "alpha"
value second string "beta"

call added @items add "(Ljava/lang/Object;)Z" @first
call added @items add "(Ljava/lang/Object;)Z" @second
call count @items size "()I"

print "size=" @count
export value @items output/items.txt
release @items @first @second @added @count
```

## 13. Run Scripts from the Java Library

An attached library session can run the same script command and capture its result:

```java
JvmRtdpCommandResult result = session.execute("script workflow.jrd");
if (!result.successful()) {
    throw new IllegalStateException(result.failureMessage());
}
System.out.print(result.standardOutput());
```

Script `command` instructions run through the same persistent session context. Remote variables remain in the session workspace until replaced, released, or the session closes.

For machine-readable debugger data, use a command instruction or the Java API:

```text
command debugger snapshot output/debugger.json json
```

See the [Java Library Guide](LIBRARY.md) for session ownership and thread-safety rules.

## 14. Error Semantics

- Parsing or execution stops at the first error.
- Errors include the source line number when parsing fails.
- Scripts are not transactional and do not roll back completed target changes.
- Blocking method calls block the current script request until they return.
- Unknown variables, descriptors, labels, fields, or methods fail the current run.

Test scripts that write fields, call application methods, suspend threads, or manipulate classes before using them in a production environment.
