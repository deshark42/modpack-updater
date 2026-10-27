# Agent Note: 会改动载荷的更新只支付一轮只读预检

Status: implemented

## 问题

会改动载荷的更新在动手之前做两次全量清单预检：本地旧清单在请求目标清单之前，目标清单在验签之后、任何变更之前（[更新器保护](../bug-fix/2026-10-26-protect-running-updater-and-trust-root.md)）。两次预检之间只有抓取目标清单与签名、验签和解析，没有本地写，所以第二趟几乎重复读同一棵树。规划阶段又对每个受影响路径各做两次 `SafePath.resolve`——工作区侧目的地与备份目的地——其中工作区侧同样已被那两趟清单预检覆盖。

真实 Windows/NTFS 实例（16122 条清单路径、8 个分片）上，一趟 `require` 中位约 585 毫秒（[目录级真实路径解析](2026-10-27-resolve-one-directory-per-preflight-path.md)），每次会改动载荷的更新因此在第一次写入之前先花约 1.2 秒，只是把同一棵本地树的元数据读两遍。逐条 `SafePath.resolve` 的探针更贵：工作区侧 478–581 微秒/条、备份侧 224–248 微秒/条（备份根缺失时几乎全是反复让 `toRealPath` 抛 `NoSuchFileException` 的开销），一条路径在一轮只读窗口里因此被检查 2–3 次。

## 决策

[`fs.PathGuard.preflight()`](../../../../src/main/java/io/github/deshark42/modpackupdater/fs/PathGuard.java) 返回一份只读预检记录，可显式传给后续的 `inspectAll(paths, preflight)`：它保存逐段不跟随链接属性（含缺失结果）与已经成功的真实路径比较。`copy()` 为每个并行分片提供一份可变副本，`merge()` 在所有 reader 结束后把这些副本单线程并回。不带预检参数的 `inspectAll`、[`PathGuard.at(root).require`](../../../../src/main/java/io/github/deshark42/modpackupdater/fs/PathGuard.java) 与静态 `require(root, path)` 仍从空记录开始。

[`security.UpdaterProtection`](../../../../src/main/java/io/github/deshark42/modpackupdater/security/UpdaterProtection.java) 增加 `require(manifest, concurrency, preflight)`：分片各自从 `preflight.copy()` 开始，结束后把每一片的结果并回传入的预检；原来的 `require(manifest, concurrency)` 每次新建一份空预检。文件身份比较（`Files.isSameFile`）不复用预检结论，每趟照跑。

[`command.Update`](../../../../src/main/java/io/github/deshark42/modpackupdater/command/Update.java) 在一次会改动载荷的运行里创建一份预检，依次用于本地旧清单与目标清单两趟检查：两次检查之间只有 `store.getBytes`、验签与解析，不写本地树；载荷阶段第一次写入之后不再复用。规划阶段的目的地逐条预检因此只保留备份侧 `SafePath.resolve(backup, path)`：`plan.downloads()` 是目标清单的子集、`plan.deleted()` 是本地清单的子集，源路径已由那两趟清单预检覆盖，而备份目的地不在任何清单里。

复用永远是显式传参，不是实例或静态缓存：预检记录的是某一时刻的发现，写入之后即陈旧，`require(manifest, concurrency)` 也不继承调用方的任何记录，因此跨清单、跨 tag、跨运行都不会自动复用。分片各自的副本与主记录只在所有 reader 结束后单线程合并，与[更新器保护](../bug-fix/2026-10-26-protect-running-updater-and-trust-root.md)的分片生命周期一致。

## 曾考虑的替代方案

**保留两趟各自独立的预检。** 不采用：两趟之间没有任何本地写，重复的是一整轮元数据读取；同一实例上共享后两趟合计中位从 1442 毫秒降到 1209 毫秒（concurrency 8），第二趟余下的几乎全是文件身份比较。

**让 `inspectAll` 把快照挂在 `PathGuard` 实例、静态字段或工作区状态上自动复用。** 不采用：无法界定只读窗口，写入之后的复用会把检查时刻提前到写入前；显式传参让每个复用点可审查，也保留[拒绝链接重定向](../bug-fix/2026-10-25-reject-workspace-link-redirections.md)对跨调用缓存的拒绝。

**把同一份预检复用于下载、应用、暂存、清理或后续 tag。** 不采用：这些阶段会移动、删除或放入文件，跨写入复用会把检查时刻提前到写入之前，与保护目标相反。

**规划阶段只删掉备份侧的逐条预检，保留工作区侧。** 不做：工作区侧已被两趟清单预检覆盖，备份目的地则不在任何清单里，只有它的逐条预检仍不可替代。

**用 `PathGuard.at(backup).inspectAll` 批量检查备份目的地。** 暂不采用：备份根通常尚不存在（首次更新为 `backup/.initial`），而批量入口要求已存在的受信根，需要先创建目录或新增可容忍缺失根的入口；同一批 16122 个目的地用一次批量检查实测 0.64–1.18 秒（含填充备份的副本），明显少于逐条 3.5–4.0 秒（备份根缺失），是后续候选，本轮不改。

## 后果

- 会改动载荷的更新里，两趟清单预检（16122 条路径，concurrency 8）合计中位从 1442 毫秒降到 1209 毫秒：第二趟从 694 毫秒降到 523 毫秒，而第二趟在第二份清单没有新路径时仍需支付的文件身份比较地板是 528 毫秒，即新覆盖的路径几乎免费；concurrency 1 时第二趟从 1791 毫秒降到 947 毫秒（同一地板 893 毫秒）。8 个分片各复制一份预检合计约 4 毫秒。第二趟的剩余成本是 16121 次 `Files.isSameFile`，本记录不复用身份结论。下载与移动前的逐条目的地预检只保留备份侧：1000 条路径省 0.48 秒、3000 条省 1.62 秒、整份 16122 条清单最坏省 9.4–9.6 秒，i18n 709 条省 0.45 秒。剩余备份侧逐条成本是 224–248 微秒/条（备份根缺失）、0.61–0.73 毫秒/条（备份已有文件），见上面「曾考虑的替代方案」中的批量候选。
- 行为收窄：目标清单里与本地旧清单重叠的路径，其路径段检查时刻提前到本轮只读窗口的第一次访问；两趟之间唯一的写入者（本进程）不写。文档已声明的边界不变——预检仍不锁住另一个拥有同一目录写权限的本地进程，[拒绝链接重定向](../bug-fix/2026-10-25-reject-workspace-link-redirections.md)继续持有这条规则。
- `Update` 不再在下载或移动前逐条预检工作区侧的受影响源路径；链接、junction 与目录别名仍由两趟清单预检在任何变更之前拒绝，`UpdateTest.refusesLinkedPayloadDirectoriesBeforeApplyingAnyPath` 继续钉住新增、替换、删除三种计划下的拒绝顺序与请求序列。
- 测试：`PathGuardTest.reusesOneBatchOfMetadataInLaterReadOnlyChecks` 钉住一个预检能被后续只读批次复用、且未被覆盖的链接仍重新发现；`UpdaterProtectionTest.reusesOnePreflightOnlyForThePathsItAlreadyCovered`（Windows）钉住跨两趟清单检查的预检不会跳过第一趟未覆盖的 junction。不带预检参数的调用保持原有边界：`parallelPreflightRechecksJunctionsBetweenManifests` 与 `rechecksPayloadIdentityOnEachManifest` 仍在两次独立 `require` 之间发现被替换的 junction 与硬链接。
