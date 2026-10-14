# Agent Note: 发布者 overlay 目录与 base 删除清单

Status: proposed

## 问题

[发布者工作区与发布命令](../../implemented/architecture/2026-10-13-publisher-workspace.md)把 `<tag>/base/` 定为该 tag 发布内容的唯一来源。base/ 承载的是官方整合包解出来的那棵树，而它的更新方式是删掉整个目录、重新解压新版本——这是发布者处理官方更新的既定做法，工具不参与。

这个更新方式与「把自己的内容放进 base/」互相冲突：

- 发布者对官方内容的修改（改配置、加自己的模组、替换文件）写进 base/ 后，下一次官方更新会被整体删除，必须重新做一遍；而且没有任何地方记录哪些文件是发布者加的，重做时只能靠记忆。
- 发布者若不动 base/，就没有地方表达「额外提供这个文件」与「官方包里的这个文件我不要」。

两类意图都不可表达：**新增/覆盖**与**删除**。于是只剩两条路，都有代价：把 base/ 变成自己的完整副本（官方内容与自有内容不再可分，官方 diff 不可审计），或者干脆不发布这些改动（订阅者永远拿不到）。

## 提案

在 `<tag>/` 下增加两个发布侧输入，与 base/ 并列：

```
<workspace>/
├── .modpack-updater/
└── <tag>/
    ├── base/            # 官方包解出的树；随官方更新整体替换，工具不参与
    ├── overlay/         # 发布者自己的文件：对 base/ 的新增或覆盖
    └── remove.txt       # 从 base/ 中剔除的路径，一行一条
```

publish 在扫描阶段把三者合成一份**有效清单**，其余流程（blob 去重、上传、写 release 清单、覆写 latest）不变。有效清单 = base/ 的全部文件 + overlay/ 路径的文件（增加或覆盖），再去掉 remove.txt 命中的 base/ 文件。

overlay/ 与 base/ 共用同一路径空间：`overlay/.minecraft/mods/example-mod.jar` 对应的清单路径是 `.minecraft/mods/example-mod.jar`，与 base/ 下的同名路径指同一个位置。

overlay/ 与 base/ 生命周期无关：官方更新只替换 base/，发布者不必在每次官方更新后重做自己的改动。overlay/ 与 remove.txt 也可以不存在。

删除是**显式意图**，不是推断：publish 仍然不与上一次 release 的清单比较，某个路径不再发布只有两种可能——它已不在 base/ 里，或被 remove.txt 命中。

本提案只改发布侧：`init --tag <tag>` 一并铺出 overlay/ 与带注释头的空 remove.txt（只创建缺失的，不覆盖已有内容），`publish --dry-run` 与发布日志报告 base、overlay 新增/覆盖、remove 命中/未命中的合并结果。release 清单格式不变（version 仍为 1，不记录某个文件来自 base/ 还是 overlay/），存储布局、latest 指针、config.json、`update` 命令与订阅者工作区也都不变；被改动的既有决策只有[发布者工作区与发布命令](../../implemented/architecture/2026-10-13-publisher-workspace.md)里「base/ 是该 tag 发布内容的全部来源」这一条，落地时随代码在同一次改动里更新那条记录。

### 合并规则

remove.txt 只作用于 base/：同路径以 overlay/ 为准，规则命中 overlay/ 里的文件时不生效——删除表达的是「官方包里这些我不要」，发布者自己的内容由 overlay/ 的存废表达，要取消就直接把它从 overlay/ 删掉。

### remove.txt 的匹配与格式

- 匹配语法取 `.gitignore`（PATTERN FORMAT），锚定基准是 remove.txt 所在的 `<tag>/`，也就是 base/ 内容的根；相对它只有两处删减：不支持 `!` 反选，也不支持 `\` 转义。
- 一行一条规则，空行与 `#` 开头的注释行忽略，行首尾空白忽略，重复行去重，规则之间无序。
- `example-mod-*.jar` 命中任意深度的同名文件，`/example-mod-*.jar` 只命中根目录，`.minecraft/mods/**/example-mod-*.jar` 命中该目录下任意深度，`.minecraft/excludeDir/` 剔除该子目录的全部文件。
- 规则还须满足清单路径的校验（`SafePath.require`）：不得为空、不得以 `/` 开头、不得含 `\`、`:` 或 `.`/`..` 段；通配符不受此限——`\` 被禁，gitignore 里 `\#` 这类转义写法不可用。
- 未命中任何 base/ 文件的规则：继续发布，只在日志里报告。它通常意味着官方包自己已经删掉了该文件，是这条规则正常老化；当成错误会让每次官方更新都卡在陈旧清单上。

## 曾考虑的替代方案

本记录没有认真比较过其他方案：overlay/ 与删除清单是表达上述两类意图所需的两个输入，而不是在比较中胜出，因此没有可记录的落选方案，本记录也不声称其他设计更差。

## 验收标准

- 只有 base/ 的工作区发布出的清单与改动前逐字节一致；overlay/ 或 remove.txt 缺失时不报错。
- overlay/ 独有的路径进清单且 hash 取自 overlay/ 的文件；同路径同时在 base/ 与 overlay/ 时取 overlay/ 的 hash。
- remove.txt 命中的 base/ 文件不进清单；`dir/` 剔除该目录下全部文件，`example-mod-*.jar` 剔除任意深度下同名的文件；未命中的规则出现在日志里，不改变退出码。
- remove.txt 的规则命中 overlay/ 里的文件时不生效，该文件仍在清单里。
- 同一工作区的 `publish --dry-run` 报出的合并结果与最终清单，与实际发布后一致。
- `init --tag <tag>` 创建 `<tag>/overlay/` 与 `<tag>/remove.txt`，重复执行不覆盖已有内容。

## 风险

- **remove.txt 直接驱动订阅者删除。** 清单里消失的路径在订阅者侧就是删除（旧文件移入 backup/，见[订阅者工作区](../../implemented/architecture/2026-10-14-subscriber-workspace.md)）。写错或过期的规则会把玩家实例里的文件搬走，影响面是全体订阅者，发布侧不做二次确认。
- **remove.txt 会腐化。** 未命中的规则只报告不报错，长期不清理会积累已无作用的条目。
