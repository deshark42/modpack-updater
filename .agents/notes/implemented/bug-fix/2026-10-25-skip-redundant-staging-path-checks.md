# Agent Note: 解包暂存树不再逐条目重查路径

Status: implemented

## 问题

base_pack/ 的解包在发布扫描阶段占掉整次发布最长的静默时间。2026-10-25 的一次 Windows 测试发布中，`scanned i18n/base + i18n/base_pack: 709 files, 8.6 MiB` 一行覆盖了 44.2 秒，而其中 7z 解码本身只要约 0.3 秒（同一个 1.9 MB、1340 条目 = 709 文件 + 631 目录的包）。慢的是逐条目文件系统元数据：`BaseArchive` 对每个条目先做路径校验，再在写入前后重复调用 `PathGuard.require`，单条目 4 次（目录 5 次），整个包 5991 次；合并扫描又给暂存树里每个文件、目录与暂存根各补一次，共 1341 次。每次 `PathGuard.require` 都解析工作区根的真实路径，再对路径的每个已存在段做一次不跟随链接的属性读取和一次真实路径解析，成本随条目数与路径深度线性增长；发布机若是 Windows（用户日志中的路径分隔符表明是），一次元数据访问比本地 ext4 贵得多，于是约 7300 次 `PathGuard.require` 把 1.6 秒的本地扫描放大到 44 秒。暂存树却是本轮发布自己新建、且只有解包器写过的空目录：其中不存在需要发现的旧链接，逐条目重查只是重复确认。

## 决策

`BaseArchive` 只在打开压缩包时校验一次边界：压缩包输入、`.modpack-updater/tmp/` 临时目录和新建的专属暂存根各经 `PathGuard.require`，`close` 清理时仍逐目录校验暂存树后才删除。暂存根之下的条目不再逐条走 `PathGuard`：条目名继续由现有解析器拒绝绝对路径、盘符、反斜杠、空路径段、`.` / `..` 段，然后 `prepare` 只把 `root.resolve(relative).normalize()` 与暂存根做字符串包含检查，越界即报错；写入只创建父目录，用 `CREATE_NEW` + `NOFOLLOW_LINKS` 打开目标，并保留内容长度与 CRC 校验。

Windows 上还逐段做无 I/O 的名称检查：拒绝设备名（包含扩展名、COM / LPT 编号及 Windows 识别的上标编号）与结尾点、空格，避免 `NUL.txt` 写入成功却不生成文件，或平台改写名称。只有相对路径含 `~` 的目录在创建后额外比较一次真实路径与暂存根的真实路径加相对后缀，发现 8.3 短名映射到此前条目时失败；普通含 `~` 的真实目录仍允许。文件名若别名命中已存在文件，`CREATE_NEW` 使其失败。这些规则只适用于 Windows 发布机；其他平台不新增 Windows 名称限制。

`BaseSource` 的输入树扫描因此分成两种：`base/`、`base_pack/` 这类用户提供的目录继续逐条目 `PathGuard.require`（根、目录、文件），而解包暂存树只遍历登记，不再逐条校验；`file()` 在读取发布内容时仍逐次校验交给分块的实际源文件。一次发布复用 `PathGuard.at(workspace)` 捕获的不可变根，清理也使用单次捕获的根；检查仍逐次重新读取子路径属性，不缓存子路径的安全结论。

解包开始与完成各记一行 INFO：开始前 `extracting <相对压缩包路径>`，合并扫描后 `extracted <路径>: <N> files, <大小> in <耗时>`。合并扫描也计入这段耗时，因为冲突登记、逻辑路径映射和逐个文件的目录遍历都发生在解包与登记之间，单独给解码计时会把这部分藏起来。INFO 让控制台和 transcript 都能看到这段原来只有结束时间的阶段。

[可组合的目录与压缩包 base 输入](../feature/2026-10-25-composable-base-inputs.md) 继续持有输入组合、映射与冲突语义，[拒绝工作区内部的文件系统重定向](2026-10-25-reject-workspace-link-redirections.md) 继续持有 `PathGuard` 的通用边界；本记录只把其中的「发布树遍历逐项检查」收窄到用户输入，并给那份记录的完整解包成本补上日志事实。

## 曾考虑的替代方案

**只优化 PathGuard，保留解包与扫描的全部逐条目校验。** 不采用：捕获受信根、只解析最深现存节点可以降低单次成本，却仍重复读取每个暂存条目的全部祖先属性。专属暂存树的普通条目没有旧链接需要发现，因此去掉重复检查；通用 `PathGuard` 优化同时保留，用于用户输入、源文件访问与清理。

**完全依赖 CREATE_NEW、NOFOLLOW_LINKS 和内容校验。** 不采用：Windows 的 `NUL` / `NUL.txt` 会接收并丢弃写入，输入字节数与 CRC 仍匹配，后续目录扫描却漏掉条目，可能发布并签名一份不完整的清单。结尾点与短名别名也可能改写条目身份；无 I/O 名称检查加少量候选目录真实路径检查保留明确失败的行为。

**只加解包计时日志，保留现有校验。** 不采用：日志只能解释 44 秒，不能消除它；在扫描日志已经写明文件数与大小的前提下，再加一行同样慢的耗时说明，下一次发布还要重付。

**跨次缓存解包结果，或在分块时按需从压缩流解压。** 不采用：前者需要判断包内容与解包结果的对应关系及陈旧清理，后者与去重规划在上传时随机重读源文件的方式冲突，两者都是独立决策；[可组合的目录与压缩包 base 输入](../feature/2026-10-25-composable-base-inputs.md) 把每次发布完整解包作为当前取舍保留。

## 后果

- 每个包少 5991 次 `PathGuard.require`（709 × 4 + 631 × 5），暂存树遍历再少 1341 次（含暂存根）。本地在 Linux ext4 上用探针直接调用 `BaseSource.open` 实测同一个 7z（一次 Windows 测试发布留下的包，不是仓库内 fixture）从 1.3–1.6 秒降到 0.39–0.66 秒（含三次运行的 JVM 预热差异）；Windows 上单次元数据访问更贵，绝对收益更大。剩余成本主要是 1340 次文件/目录创建与 709 次写入，与条目数同阶但不再乘以逐段的真实路径解析。
- 路径逃逸与链接边界没有放宽：压缩包输入、临时目录、暂存根仍经 `PathGuard`，因此把 `.modpack-updater/tmp` 换成符号链接仍以 `filesystem link or alias` 失败（`BaseArchiveTest.rejectsRedirectedWorkspaceTemporaryStorage`）；用户输入目录中的嵌套链接仍被拒绝（`BaseSourceTest.rejectsLinksInsideEitherInput`）；条目名语法校验、`CREATE_NEW` 和 `NOFOLLOW_LINKS` 保持不变。`PathGuard` 仍不承诺抵御其他本地进程并发替换路径，这次改动没有改变该承诺。
- 普通条目不增加真实路径查询；Windows 的设备名和结尾点/空格在写入前明确失败，8.3 目录别名也不能静默合并。`BaseArchiveTest` 对 ZIP / 7z 覆盖特殊名拒绝与失败清理、近似设备名与普通波浪号目录可用、非 Windows 平台保留合法名称；短名别名用例仅在测试卷启用 8.3 名称时执行。`PublishTest` 验证危险 Windows 名称在任何存储请求之前失败，不写清单、签名、缓存或 latest，并清理此前已解包的其他包。
- 解包阶段现在有两个可见里程碑，`PublishTest.publishesAnArchiveBaseWithOverlayAndRemoveRules` 断言 `extracting` / `extracted` 行含相对压缩包路径与解包文件数；`BaseArchiveTest` 继续持有 ZIP / 7z 安全解包、名称解码、内容校验和清理覆盖。
