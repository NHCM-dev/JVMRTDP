# JVMTI API 覆盖范围

[English](JVMTI-API-COVERAGE.md) | [中文](JVMTI-API-COVERAGE_ZH.md)

JVMRTDP 通过原生代理和 Java 桥接层提供常用 JVMTI 诊断与调试能力。本页按功能组说明公开支持范围，以及受 JVM 阶段或安全模型限制的能力。

## Capability 模型

- 代理启动时会在 `OnLoad` 阶段请求可用能力。
- 动态注入发生在 `LIVE` 阶段，只能获取 JVM 仍报告为 potential 的能力。
- `jvmti capability add <name...>` 使用标准 `AddCapabilities`。
- `jvmti capability relinquish <name...>` 使用标准 `RelinquishCapabilities`。
- 不修改 HotSpot 内部 capability 表，也不承诺绕过 JVM 阶段限制。

需要方法入口、断点、局部变量或重转换等完整能力时，建议使用 `-agentpath` 在 JVM 启动时加载代理。

## 支持的功能组

### 环境与能力

- 当前、potential 和缺失 capability 查询
- JVMTI phase、时间、计时器、处理器数量和位置格式
- 系统属性读取与写入
- `other`、`gc`、`class`、`jni` verbose 标志

### 类、方法与字段

- 已加载类、类签名、状态、修饰符、类加载器和接口
- 类加载器可见类、Source Debug Extension 和常量池
- 方法名称、签名、修饰符、声明类、最大局部变量和参数数量
- 方法字节码、行号表和局部变量表
- 字段名称、签名、修饰符和声明类
- 类重转换与受 HotSpot schema 限制的类重定义

### 线程、栈与监视器

- 线程枚举、信息、状态和线程组
- 栈帧、帧数量、帧位置和线程 CPU 时间
- 局部变量读取
- 线程暂停、恢复和中断
- owned monitor、contended monitor 和 monitor usage
- frame-pop 通知

### 对象、标签与堆

- 对象大小和 identity hash
- 对象 tag 的读取、设置和按 tag 查询
- 强制 GC

### 调试与事件

- 断点设置与清除
- 字段访问和修改监视点
- 单步、方法入口/退出、异常、线程、类、监视器、GC 和 VM 事件
- 事件通知启用、禁用和部分可生成事件
- Java 回调部署与事件分派

完整命令语法见[命令参考](COMMANDS_ZH.md)。

## 事件回调

回调按用途分为：

- 生命周期：VM、线程和类事件
- 执行：方法入口/退出、异常、单步、断点和帧弹出
- 数据：字段访问与修改
- 同步：监视器等待、进入和竞争事件
- 运行时：GC、对象释放、编译、动态代码和资源耗尽

回调处理器必须为静态方法，并使用与事件载荷兼容的描述符。高频事件可能显著影响目标性能，应结合类、方法或线程过滤器使用。

## 标准接口限制

- JVMTI 不提供当前 JVM 操作数栈内容；`maxStack` 只是类文件声明的最大深度。
- 原生帧没有 Java BCI、字节码或局部变量。
- 缺少 `LocalVariableTable` 时只能按槽位和可推断类型尝试读取局部变量。
- HotSpot 类重定义不能任意增加、删除或改变字段与方法结构。
- capability 在 `LIVE` 阶段不再 potential 时，标准 JVMTI 无法强制开启。

## 未公开或受限的操作

以下低层接口不作为常规用户命令公开：

- 直接堆遍历与引用图遍历
- 原始 JNI function table 替换
- 原始内存分配接口
- 原始监视器创建与销毁
- extension function 和 extension event 枚举
- TLS、环境销毁和其他代理生命周期内部操作

这些接口需要额外的生命周期、回调和并发约束。JVMRTDP 优先通过受控的高层命令提供等价诊断结果。

## Java 库 API

已连接的 `JvmRtdpSession` 通过 `session.jvmti()` 提供类型化低层控制，并通过 `session.instrumentation()` 提供高层插桩。库代码无需解析 CLI 输出，即可查询 capability、检查线程与 local、配置字节码/事件断点、提前返回、部署 handler/transformer、使用 ASM 事务化编辑字节码并 retransform/redefine。字节码补丁会重算 frame/max，并迁移受管断点。

```java
try (JvmRtdpClient client = JvmRtdpClient.open();
     JvmRtdpSession session = client.attach(pid)) {
    for (JvmtiCapabilityStatus status : session.jvmti().capabilityStatuses()) {
        System.out.println(status);
    }
}
```

线程、对象、回调和部署句柄应确定性关闭。关闭会话时，会先恢复由调试器分析冻结拥有的线程，再关闭协议连接。详见 [Java 库指南](LIBRARY_ZH.md)。

## 兼容性说明

- 原生桥接以 Java 8 JVMTI ABI 为兼容基线。
- 不同 JDK 发行版可能提供不同的 potential capability。
- JAR 与原生 DLL 应来自同一次构建。
- 替换预加载的 DLL 后必须重启目标 JVM。
