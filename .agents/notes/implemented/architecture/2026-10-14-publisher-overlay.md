# Agent Note: 发布者 overlay 目录与 base 删除清单

Status: implemented

## 问题

在此之前，tag 的发布内容只有 `<tag>/base/` 一个来源。base/ 承载的是官方整合包解出来的那棵树，而它的更新方式是删掉整个目录、重新解压新版本——这是发布者处理官方更新的既定做法，工具不参与。

这个更新方式与「把自己的内容放进 base/」互相冲突：

- 发布者对官方内容的修改（改配置、加自己的模组、替换文件）写进 base/ 后，下一次官方更新会被整体删除，必须重新做一遍；而且没有任何地方记录哪些文件是发布者加的，重做时只能靠记忆。
- 发布者若不动 base/，就没有地方表达「额外提供这个文件」与「官方包里的这个文件我不要」。

两类意图都不可表达：**新增/覆盖**与**删除**。于是只剩两条路，都有代价：把 base/ 变成自己的完整副本（官方内容与自有内容不再可分，官方 diff 不可审计），或者干脆不发布这些改动（订阅者永远拿不到）。

## 决策

本记录中的 base 指由 base/ 与 base_pack/ 无冲突合成的逻辑路径空间，而非仅指物理 base/ 目录。`<tag>/` 下与它们并列的两个发布侧输入是 `overlay/`（发布者自己的文件：对 base/ 的新增或覆盖）与 `remove.txt`（从 base/ 中剔除的路径，一行一条）；它们在发布者工作区里的位置见[发布者工作区与发布命令](2026-10-13-publisher-workspace.md)。

publish 在扫描阶段把三者合成一份**有效清单**，其余流程（块去重、上传、写 release 清单、覆写 latest）不变。有效清单 = base/ 的全部文件 + overlay/ 路径的文件（增加或覆盖），再去掉 remove.txt 命中的 base/ 文件。

overlay/ 与 base/ 共用同一路径空间：`overlay/.minecraft/mods/example-mod.jar` 对应的清单路径是 `.minecraft/mods/example-mod.jar`，与 base/ 下的同名路径指同一个位置。overlay/ 与 remove.txt 自身可以不存在，缺失时按空处理。

overlay/ 与 base 生命周期无关：官方更新只替换 base/ 文件或 base_pack/ 内的压缩包，发布者不必在每次官方更新后重做自己的改动。[可组合 base 输入](../feature/2026-10-25-composable-base-inputs.md)持有来源组合及冲突拒绝，不取代本记录的合并与删除语义；remove 作用于合并后的 base，overlay 再新增或覆盖。来源间的冲突在本记录的规则生效前就失败，不能由 remove 或 overlay 消解。

删除是**显式意图**，不是推断：publish 仍然不与上一次 release 的清单比较，某个路径不再发布只有两种可能——它已不在 base/ 里，或被 remove.txt 命中。

发布日志报告合并结果：扫描 base/ 与 overlay/ 各一行 INFO、remove 命中多少文件（来自多少条规则）一行 INFO、未命中的规则一行 WARN、overlay 有新增或覆盖时 merge 一行 INFO；逐文件的 add/replace 是 DEBUG，只进日志文件、不在终端显示。release 清单不记录某个文件来自 base/ 还是 overlay/；存储布局、latest 指针、config.json、`update` 命令与订阅者工作区都不需要知道来源。

### 合并规则

remove.txt 只作用于 base/：同路径以 overlay/ 为准，规则命中 overlay/ 里的文件时不生效——删除表达的是「官方包里这些我不要」，发布者自己的内容由 overlay/ 的存废表达，要取消就直接把它从 overlay/ 删掉。规则先于 overlay/ 生效，因此「官方包的某个文件我不要，换成我自己那份」由一条命中它的 remove.txt 规则加上同路径的 overlay/ 文件一起表达。

### remove.txt 的匹配与格式

- 匹配语法取 `.gitignore`（PATTERN FORMAT），锚定基准是 remove.txt 所在的 `<tag>/`，也就是 base/ 内容的根；相对它只有两处删减：不支持 `!` 反选，也不支持 `\` 转义。
- 一行一条规则，空行与 `#` 开头的注释行忽略，行首尾空白忽略，重复行去重，规则之间无序。
- `example-mod-*.jar` 命中任意深度的同名文件，`/example-mod-*.jar` 只命中根目录，`.minecraft/mods/**/example-mod-*.jar` 命中该目录下任意深度，`.minecraft/excludeDir/` 剔除该子目录的全部文件。
- 规则直接校验，不经 `SafePath.require`——那道校验为清单路径而设，会把 `a/../b` 规范化成 `b`，等于静默改写规则。拒绝空规则、含 `\` 或 `:` 的规则、以及把 `.` 或 `..` 当作路径段的规则；开头斜杠是锚定符，允许。通配符不受上述限制，但 `\` 被禁意味着 gitignore 里 `\#` 这类转义写法不可用。
- 匹配的判定：规则正文里出现过斜杠就是 anchored，与整条清单路径比对；否则只与该路径的最后一段比对。结尾斜杠把规则限定为目录，并使它剔除该目录下的整棵子树，而不是只剔除名为该目录的那个条目。
- 未命中任何 base/ 文件的规则：继续发布，只在日志里报告。它通常意味着官方包自己已经删掉了该文件，是这条规则正常老化；当成错误会让每次官方更新都卡在陈旧清单上。

## 测试

匹配语义由 src/test/java/io/github/deshark42/modpackupdater/model/RemoveRulesTest.java 锁定：锚定与逐段通配、`**` 跨目录（含零层）、目录规则剔除子树且不命中同名文件、`?` 与字符类、整体匹配，以及拒绝 `../escape.jar`、`mods/../../escape.jar` 与 `C:/escape.jar`。

合并结果由 src/test/java/io/github/deshark42/modpackupdater/command/PublishTest.java 锁定：同路径 overlay/ 胜出、remove.txt 只作用于 base/、坏规则在接触存储之前就报错。src/test/java/io/github/deshark42/modpackupdater/command/InitTest.java 锁定 init 铺出的 base/、base_pack/、overlay/ 与 remove.txt、模板里零条生效规则，以及重复执行只补缺失、不改写已有 remove.txt。

## 曾考虑的替代方案

**用 `SafePath.require` 校验规则。** 不采用：它会把 `a/../b` 规范化成 `b`，匹配器就会按另一条路径去比对；规则按自己的语法直接校验，与清单路径的字符集无关。

## 后果

- 发布者自己的改动不必写进 base/：官方更新整体替换 base/ 后 overlay/ 与 remove.txt 原样保留，重做不再依赖记忆。
- 只有 base/ 的工作区行为不变：清单与改动前逐字节一致；overlay/ 与 remove.txt 缺失时不报错，也不多出日志行。
- overlay/ 独有的路径进清单且 hash 取自 overlay/ 的文件；同路径同时在 base/ 与 overlay/ 时取 overlay/ 的 hash，其余文件仍取自 base/。
- remove.txt 命中的 base/ 文件不进清单；`dir/` 剔除该目录下全部文件，`example-mod-*.jar` 剔除任意深度下同名的文件；未命中的规则出现在日志里，不改变退出码。
- scan/remove/stale/merge 四行日志与最终清单来自同一次合并：清单在合并完全结束之后才写出，不会出现日志与内容各说各话。
- 清单不携带来源：订阅者无法区分一个路径来自官方包还是发布者自己，排查内容问题时少一条线索。
- **remove.txt 直接驱动订阅者删除。** 清单里消失的路径在订阅者侧就是删除（旧文件移入 backup/，见[订阅者工作区与更新流程](2026-10-14-subscriber-workspace.md)）。写错或过期的规则会把玩家实例里的文件搬走，影响面是全体订阅者，发布侧不做二次确认。
- **remove.txt 会腐化。** 未命中的规则只报告不报错，长期不清理会积累已无作用的条目。
- 两份输入都要发布者自己维护：工具不校验 overlay/ 里的文件是否真的该覆盖 base/，也不校验 remove.txt 的规则是否仍然表达意图。
- publish 的终端输出多了 scan/remove/stale/merge 各一行（INFO 或 WARN），逐文件 add/replace 只在日志文件里（DEBUG）：日志更完整；没有只压掉这几行的开关，终端固定显示 INFO 及以上。
