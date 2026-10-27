# Agent Note: release 已应用时不再预检旧清单

Status: implemented

## 问题

一次已经处于目标 release 的更新不做任何事：不应用、不移动、不暂存，只确认指针指向最新版本后退出。但 `Update.run` 在这条无操作路径上仍会读取本地旧清单并完整检查它的每条路径，而这项预检的全部意义是让一次**可能改动**载荷路径的运行在改动前失败。用户 2026-10-27 在真实 Windows/NTFS 实例上的日志显示，这段纯成本占了无操作更新的大部分：`client` tag 的 16122 条清单路径，整次运行 3.58 秒，其中读取本地清单 207 毫秒、检查这 16122 条路径 2527 毫秒；`i18n` tag 的 709 条路径，解析 45 毫秒、检查 179 毫秒，整次运行 0.815 秒。

## 决策

[Update](../../../../src/main/java/io/github/deshark42/modpackupdater/command/Update.java) 先读取已应用指针，保留原有的缺失检查：`Layout.releaseFile(...)` 不存在时仍报 `cannot tell which files are already applied`，因为无法得知哪些文件已经应用就不能安全地继续。随后当目标 release 与已应用 release 相同，记录 INFO `tag <tag> is already at release <id>` 并直接返回——这一步发生在读取本地旧清单与 [UpdaterProtection.require](../../../../src/main/java/io/github/deshark42/modpackupdater/security/UpdaterProtection.java) 之前，因此无操作运行既不解析旧清单，也不遍历它的路径。

任何可能改动的运行仍走完整预检：目标 release 与已应用 release 不同（包括 `--id` 指定的回滚）时照旧读取旧清单并全量预检，验签及身份匹配后照旧预检目标清单；并行分片（最多 8 个 worker、每片至少 128 条路径）在该路径上不变。预检因此从「每次更新都执行」收窄为「会改动载荷或状态的更新才执行」，保护语义不变：会变更的运行仍会在请求块表与内容块、暂存、备份和应用之前失败，不能借删除旧条目、相同哈希或本地已有相同内容绕过。

[保护更新器与本地信任根](../bug-fix/2026-10-26-protect-running-updater-and-trust-root.md)继续持有预检对载荷路径的权限边界，[保留状态命名空间](../bug-fix/2026-10-25-reserve-updater-state-paths.md)与[拒绝链接重定向](../bug-fix/2026-10-25-reject-workspace-link-redirections.md)在会变更的路径上原样生效；本记录只把「在提前返回之前检查全部旧清单」这一时序事实改写成「只在实际会变更时检查」。

## 曾考虑的替代方案

**无操作路径保留完整预检。** 不采用：无操作运行没有任何载荷或状态要改，预检保护的变更不存在；在 16122 条路径的真实实例上它占掉无操作更新的大部分时间，是纯成本。

**保留 `Manifest.read`，只跳过路径遍历。** 不采用：解析本身就是成本的一部分——16122 条路径的读取 207 毫秒、709 条路径的解析 45 毫秒——而且无操作运行不会使用解析出的清单：没有计划、没有比较、没有应用，结果只能丢弃。

**只预检一份计划会触及的路径。** 不采用：无操作运行没有计划，目标与已应用 release 相同就没有可比较的差异；按计划裁剪预检也已被[更新器保护记录](../bug-fix/2026-10-26-protect-running-updater-and-trust-root.md)否决，因为删除条目、相同哈希与本地已匹配内容都能绕过下载集合检查。

**按指针与文件 mtime 跨次缓存预检结果。** 不采用：没有便宜且可信的证据证明文件与指针自上次检查后未变；一次假阴性会让真正改动的那次运行跳过防护，而防护的价值正在那次运行。

## 后果

- 无操作运行不再发现不一致的旧清单：无法读取的 JSON、保留的状态路径、声称拥有更新器的路径、链接等。第一次真正会改动的运行仍会在任何变更前因它失败，因此失败从无操作运行推迟到下一次真实更新；操作者的修复方式不变。
- 更新器本体与本地信任根的权限边界没有放宽：会变更的运行仍全量预检旧清单与目标清单，信任配置与密钥位置检查保持不变（[更新器保护](../bug-fix/2026-10-26-protect-running-updater-and-trust-root.md)）。
- `UpdateTest.skipsTheOldManifestPreflightWhenTheLatestReleaseIsAlreadyApplied` 把旧清单篡改为含 256 条填充路径且声称拥有更新器父目录，断言无操作运行只请求 latest、不触碰任何文件、暂存阶段停在 `reading local release pointer`，并记录 `already at release`。`refusesAnOldManifestClaimingTheUpdatersParentBeforeAnyMutation` 只保留 `{deleted, unchanged}` 两个会变更的场景；`readsEveryObjectThroughTheReadEndpointWithoutCredentials` 也断言无操作的阶段列表止于 `reading local release pointer`。`./gradlew test` 全部通过。
- 无操作运行现在只支付抓取 latest 指针与读取已应用指针的成本，不再解析或遍历任何清单。真实实例副本上的 A/B（同一 NTFS 副本、Zulu 21、`update --tag client --tag i18n`，两个 jar 各 5 次且都是无操作）中，墙上时间中位数从 3571 毫秒降到 1373 毫秒（-62%）：`client` 从 2479 毫秒降到 486 毫秒（-80%，其中旧清单读取 186 毫秒与路径检查 1842 毫秒整体消失），`i18n` 从 356 毫秒降到 216 毫秒（-39%）。省下的是与用户日志中同一段的预检工作；副本页缓存较热，检查耗时比用户日志的 2527 毫秒低。
- 剩余的无操作成本不在更新路径上：JVM 启动与退出约 0.7 秒，占候选墙上时间约一半；`latest.json` 的 GET 在 177 至 531 毫秒间波动，属网络侧。两处都要另行处理后才能把无操作更新压到 1 秒以下。
- 本地旧清单本来就没有验签：验签只针对从存储读取的目标清单，本地清单是更新器自己写下的受信状态；跳过它的读取不减少任何签名验证。
