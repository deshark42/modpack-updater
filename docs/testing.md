# 测试策略

本文说明本仓库的分层测试方式，以及让绿色测试套件保持有意义的规则。运行命令见根目录 [AGENTS.md](../AGENTS.md)；测试基础设施背后的设计动机写入 `testing` 类 [Agent Note](../.agents/notes/README.md#classification)。

## 层级

- **单元测试**（`./gradlew test --tests 'io.github.deshark42.modpackupdater.model.*'`；`fs.*`、`json.*`、`log.*`、`util.*` 同理）：测试与被测类同包。真实文件系统用 JUnit `@TempDir`，时间通过注入的 `LongSupplier`/`Clock` 控制；不联网、不使用 `FakeS3`。优先覆盖边界值（空、零、最小/最大、越界）、错误路径、确定性（同输入同输出）。
- **集成测试**（`./gradlew test --tests 'io.github.deshark42.modpackupdater.command.*'`、`./gradlew test --tests 'io.github.deshark42.modpackupdater.cli.*'`）：`command/` 在真实文件系统上运行一次完整的发布或更新，对象存储由 `FakeS3` 经真实 HTTP 替代；`cli/` 用捕获的 `PrintStream` 和真实临时目录测参数、退出码与输出。这一层验证组成与副作用，不复制单元层已经证明的分支矩阵。

每层只证明自己拥有的东西：

- 一个行为只有一个所有者测试；上层可以"顺带观察到"它，但不重复断言细节。切块算法与边界性质属于 `ChunkerTest`，`PublishTest` 只用刚好够验证上传与去重接线的多块数据，不重复断言切块的边界性质——这条规则让两层的失败信号都保持清晰。
- 每个用户流程在集成层恰好有一个正路用例和一个失败路径用例；非法输入与状态码的矩阵留在单元层和边界层。
- 普通测试不碰真实对象存储。

## 命名与结构

- 测试类 `<Class>Test`，包内可见，与被测类同包同目录；共享的测试辅助类用 `Test` 前缀，放在测试根包 `io.github.deshark42.modpackupdater`。
- 纯数据载体、纯接口/常量、仅委托的适配器、无逻辑转接（如 `Main`）不强制单独测试类：它们没有自己的行为，由使用者的测试覆盖；有没有测试看行为，不看有没有同名文件。
- 方法名是英文陈述句、第三人称现在时，读起来就是行为规格：`theSameContentCutsTheSameWayEveryTime`、`aMissingWorkspaceIsAFailureNotAUsageError`。不加 `test` 前缀，不用 `should`，不用 `@DisplayName` 或 `@Nested` 做第二层翻译。
- 一个方法一个行为（一次 Act）。同一行为的多个断言——包括"什么都没发生"的反向断言——留在同一方法；无关分支拆开，否则第一个失败会掩盖其余。
- 同一逻辑、输入矩阵 ≥3 组时用 `@ParameterizedTest`（`junit-jupiter` 聚合依赖已含 params），用例名必须区分参数；更少的输入写普通方法。
- 用例顺序无关、可单独运行；不依赖同类中其他用例留下的状态。

## 验证外部世界，而非自我报告

- 只用 JUnit Jupiter 断言（当前依赖已提供）。不引入 AssertJ、Hamcrest、Mockito 或新的测试依赖；需要时单独决策。
- 断言可观察的结果：返回值、退出码、stdout/stderr、磁盘上的文件、bucket 里的对象、请求序列、指针状态。不要只断言命令"说"它成功了——从外部重新读取它改变的世界。
- 失败路径必须断言"什么都没发生"：指针未推进、没有半成品文件、没有多余的 PUT/GET。任何失败注入用例都必须成对断言这两面，这是本仓库价值最高的断言类型。
- 确定性输出断言精确值；只有并发或时间相关的不变量才断言范围（如"至少一半 chunk 复用"），并在断言消息里写容差理由。
- `assertThrows` 必须检查异常的语义（类型加消息片段），只查类型不算测了失败原因。
- 禁止弱断言：只断言不抛异常、用 `assertNotNull` 代替具体值、捕获异常后继续、`assertTrue(true)`。
- 不断言实现细节与版式字面量（空格、缩进、ANSI 颜色）。当格式本身就是契约时（对象键、JSON 字段名、退出码、稳定的命令行行），可以断言，但期望值必须引用生产常量或经生产 API 构造，不手抄字面量。失败信息文本不是契约，除非它是用户依赖的语义片段。

## 优先使用真实实现而非 mock

- 只 fake 昂贵、不确定、或不在本仓库控制内的边界：HTTP 对象存储、时钟、终端输出流。文件系统、chunking、原子写、并行调度保持真实。
- 手写 fake 只能证明桥接层在搬运字节。`FakeS3` 是本仓库唯一共享的对象存储替身，放在测试根包，实现 `AutoCloseable`，绑定 `127.0.0.1:0`；禁止再写第二份内联 fake。
- fake 必须在它替代的边界上保守地严格：未支持的方法或动词必须明确失败，不能静默落进成功路径；`FakeS3` 应当校验 SigV4 签名与 `x-amz-content-sha256` 和正文是否一致，否则签错或传坏的字节会让上层测试假绿。

## 测试数据与共享基础设施

- 临时目录用 `@TempDir` 方法参数注入；不建字段、不用 `Files.createTempDirectory`、不写仓库或用户目录。每个用例一个独立根。
- 需要释放的资源实现 `AutoCloseable` 并 `try-with-resources`：fake 服务、文件流、线程池、`Files.walk`/`Files.list` 返回的流。
- 同一份测试数据或辅助方法在第三个文件出现时，才提取到根测试包，保持无状态、静态方法、不建继承链。三类已经明确的共享基础设施：`TestFiles`（`write`、`bytes`、带 seed 的 `random`）、`TestWorkspace`（写 config、按 tag 布置 `base/`/`overlay/`/`remove.txt`、拼出桶内对象路径）、`LogSink`（捕获 out/err，可按需注入时钟的 `Log`）。
- 测试数据通过生产代码的写入口构造：`Manifest.write`、`Pointer.write`、`ObjectKeys`。生产代码没有写入口的格式（如 `config.json`）集中在一个 `TestWorkspace.configure` 里，字段名由该格式的测试锁住；不要在多个测试里各写一份 JSON 字符串。
- 假依赖只出现在边界层与集成层；单元层不依赖 `FakeS3`。
- 测试数据在内存中生成并固定 seed；不提交二进制测试数据。需要固定字节序列时，用常量或小型文本向量，并注明生成方式。

## 确定性与隔离

- 禁止 `Thread.sleep` 与轮询等待：注入时钟、`CountDownLatch`、确定性计数器；等待必须有界，超时即失败。
- 禁止真实网络、云服务、系统时钟、默认时区与语言环境：用 `127.0.0.1` fake、注入 `Clock`/`LongSupplier`、显式 UTF-8 与 Locale。
- 禁止未播种随机、固定端口、共享可变 static、依赖执行顺序；每个用例持有自己的实例与临时根。
- `assumeX` 只用于行为本身依赖平台的场合（权限、符号链接支持），并且必须打印或注释原因；环境不满足时静默 `return` 视为缺陷——那会让覆盖无声消失。`@Disabled` 必须写原因和跟踪项。
- 测试必须并行安全（实例字段、无共享可变状态），即使当前不并行执行；将来开启并行不应需要改测试。
- 不加重试来掩盖 flaky。只在单独运行时通过的用例是测试缺陷，不是 runner 不稳定。
- 默认套件预算：整套 < 10 秒（当前约 5 秒）；单个单元用例 < 100 毫秒，单个集成用例 < 1 秒。大输入只取刚好覆盖行为的尺寸，不用真实 modpack 规模的数据。

## 测试真实入口路径

- 命令测试调用 `Publish.run` / `Update.run` / `Init.run`，传入真实 workspace；CLI 测试调用 `Cli.run(args, cwd, out, err)` 并断言返回的退出码。不要绕过入口直接驱动内部步骤，否则接线错误测不到。
- `Main.main` 只做 `System.exit(Cli.run(...))` 的转接，不测；测 `Cli.run`。
- 测试里不重新实现生产流程（例如自己拼 manifest 发布），除非测试对象就是"另一个写入者"的行为，且此时要说明。
