# Agent Note: 预检只为每条路径解析一个现存目录

Status: implemented

## 问题

会变更载荷的更新要对两份清单各做一次全量路径预检：本地旧清单在请求目标清单之前，目标清单在验签之后、任何变更之前。预检本身不写任何东西，成本却随路径数和路径深度线性增长。真实 Windows/NTFS 实例上 16122 条路径的一遍预检（`UpdaterProtection.require`，8 个分片）中位耗时 2.85 秒；分解探针显示其中 93–95% 是逐叶的真实路径解析：16121 次普通文件的 `Path.toRealPath()` 约 0.48 毫秒/次，占单线程 7.2–8.4 秒里的绝大部分，而逐段不跟随链接的 `readAttributes` 合计只要约 650 毫秒。两遍预检因此让每次会变更的更新在动手之前先花约 5.7 秒，只是把同一棵本地树的元数据读两遍。

## 决策

[`fs.PathGuard.inspect`](../../../../src/main/java/io/github/deshark42/modpackupdater/fs/PathGuard.java) 在逐段读取不跟随链接的属性、拒绝符号链接、junction 与特殊节点之后，只对**一个现存目录**做真实路径比较，而不是解析每条路径的最深现存节点：

- 被检查路径的最深现存节点是普通文件时，比较它已存在的父目录；
- 最深现存节点是目录时（清单不产生这种叶子，但 `PathGuard` 是通用的），比较该目录自身；
- 根以下没有任何现存节点时跳过，受信根的真实路径就是比较基准。

[`inspectAll`](../../../../src/main/java/io/github/deshark42/modpackupdater/fs/PathGuard.java) 在一次调用内按目录缓存已经成功的比较结果（原来的缓存键是「最深现存节点」，现在是「现存目录」），因此整批预检的真实路径解析次数从路径数降到唯一目录数：真实实例的 16122 条路径只需约 840 次。逐段属性读取、缺失结果缓存与拒绝链接的行为不变；调用边界不变——不带预检参数的 `inspectAll` 仍只用一次调用内的缓存，下一次调用重新检查子路径；跨调用复用只经显式传入的 `PathGuard.Preflight`（[单一只读预检轮次](2026-10-27-share-one-readonly-preflight-per-update.md)）。

祖先目录的别名仍会被发现：`mods` 实际落盘为 `Mods`、目录名带结尾点、或祖先是指向别处的 junction 时，最深现存目录的 `toRealPath()` 与「受信根真实路径 + 相对后缀」不同，仍然失败。[拒绝链接重定向](../bug-fix/2026-10-25-reject-workspace-link-redirections.md)继续持有这条规则，本记录只收窄最后一级的检查粒度；[更新器保护](../bug-fix/2026-10-26-protect-running-updater-and-trust-root.md)继续持有预检的分片、生命周期与文件身份比较。

### 为什么普通文件叶子可以不再解析

`toRealPath()` 对普通文件叶子能多抓到的只有**叶子自身的拼写别名**：NTFS 的 8.3 短名、结尾点或空格、大小写。这类别名只在同一个父目录内改名，不会把访问带到目录之外；而它指向的对象身份仍由前面的逐段不跟随链接属性读取确定——用别名访问 junction 或符号链接时，`readAttributes(..., NOFOLLOW_LINKS)` 返回的仍然是 reparse 属性（`isOther()` / `isSymbolicLink()`），链接类重定向在叶子位置同样被拒。清单路径来自发布者签名，发布者本就能指定任意工作区内路径，别名不扩大其能力；本地旧清单是更新器自己写下的状态，能改写它的本地进程早已拥有工作区写权限。剩下的只是「两种拼写指向同一对象」的窄化：例如发布时同时存在 `A.jar` 与 `a.jar`，在不区分大小写的卷上会落到同一个文件。

## 曾考虑的替代方案

**保留逐叶真实路径解析。** 不采用：它占一遍预检成本的 93–95%，每次会变更的更新要付两遍，约 5 秒纯成本；而它多抓到的只是不使用别名就无法观察到的拼写差异。

**用父目录枚举的名称替代逐叶真实路径比较。** 不采用：目录枚举也能覆盖拼写别名，但必须为 8.3 别名、设备名、NT-native 结尾点/空格与 reparse 状态另做平台证明（[更新器保护](../bug-fix/2026-10-26-protect-running-updater-and-trust-root.md)已把这条列为暂不采用），而且枚举成本随目录条目数增长，相比「每个唯一目录一次系统调用」没有优势。

**按名字形状启发式决定是否解析叶子**（结尾点、空格或形如 `X~1` 的名字才做真实路径解析）。不采用：收益只是继续拒绝极窄的拼写别名，代价是引入依赖卷设置的名称规则，与仓库拒绝平台专属名称证明的取向相反。

**把这个比较结果跨两遍预检缓存**（本地清单与目标清单共用一次快照）。本轮不采用：需要另行证明两次预检之间没有本地写入，而本记录只降低单次预检的成本。随后由[单一只读预检轮次](2026-10-27-share-one-readonly-preflight-per-update.md)接受，并限定在同一只读窗口内显式传递。

## 后果

- 一遍预检（16122 条路径、concurrency 8）中位从 2.85 秒降到 585 毫秒；`inspectAll` 本身单线程中位 858 毫秒（原 7.2–8.4 秒）、8 分片中位 255 毫秒（原 2.6–2.8 秒）。会变更的更新支付两遍，因此这条路径上的路径检查从约 5.7 秒降到约 1.2 秒。数字来自真实实例上的只读探针，不是端到端墙钟；无操作运行本来就不做预检（[release 已应用时不再预检旧清单](2026-10-27-skip-preflight-when-release-is-already-applied.md)）。
- 行为收窄：普通文件叶子的拼写别名（8.3 短名、结尾点/空格、大小写）不再被拒绝，会解析到同一目录内的实际对象。目录型路径、祖先目录别名、符号链接、junction 与设备名仍在任何变更之前失败。
- `PathGuardTest.acceptsALeafFileRespelledInsideItsCheckedDirectory` 在 Windows 上钉住被接受的叶子重拼写；`rejectsNativeDirectoryAliasesBeforeExistingAndMissingSuffixes` 与 `rejectsShortDirectoryAliasesOfLinks` 继续钉住目录别名与 8.3 目录别名下的链接拒绝；其余路径守卫、预检与更新用例不变。`./gradlew test` 全部通过。
- 真实 NTFS 副本上的探针确认：结尾点叶子仍是 12 字节普通文件、`toRealPath` 与原名相同、`isSameFile` 为真；结尾点 junction 的 `readAttributes(NOFOLLOW_LINKS)` 仍为 `isOther()`；新 `PathGuard` 接受 `mods/existing.jar.`，仍拒绝把 `mods.` 作为被检查路径。该卷 8.3 名称已禁用（`dir /x` 无短名），8.3 别名用例只在启用 8.3 的卷上执行。
- 剩余预检成本是逐段属性读取与已存在文件的 `Files.isSameFile`，不再有逐叶真实路径解析。`PathGuard.require` 的单次调用仍解析一个现存目录；`require` / `inspectAll` 的调用边界、分片与并行生命周期由[更新器保护](../bug-fix/2026-10-26-protect-running-updater-and-trust-root.md)持有，本记录不改动它们。
