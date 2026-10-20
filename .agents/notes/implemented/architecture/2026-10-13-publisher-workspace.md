# Agent Note: 发布者工作区与发布命令

Status: implemented

## 问题

云端只负责存放发布内容（见[对象存储布局](2026-10-13-object-storage-layout.md)），发布者本地还需要一份约定：要发布的那棵实例目录放在哪里、S3 连接信息放在哪里、一次发布怎样在一次调用里说清发布哪条 tag 流、这份发布叫什么。没有这份约定，发布者每次都要手工挑文件、手工记住上一次发过什么。

## 决策

发布者在 <workspace>/ 下工作，布局为：

```
<workspace>/
├── .modpack-updater/
│   ├── config.json              # S3 协议所需的配置
│   ├── known-blobs.jsonl        # 已确认存在于桶中的 blob 哈希，JSON Lines，一行一条
│   └── logs/                    # 每次真正发布的运行日志
├── <tag>/
│   ├── base/                    # 官方包解出的树，保留目录结构
│   ├── overlay/                 # 发布者自己的文件：对 base/ 的新增或覆盖
│   └── remove.txt               # 从 base/ 中剔除的路径，一行一条
└── modpack-updater-<version>.jar
```

- 工作区骨架由 init 铺出：modpack-updater init --tag <tag> 写出 config.json 模板与 <tag>/base/、<tag>/overlay/、<tag>/remove.txt；已经存在的文件只补缺失字段或只创建缺失的输入，不改写已有内容。
- config.json 只放 S3 协议需要的配置，字段定义见[发布内容格式与连接配置](2026-10-14-release-manifest-and-config.md)：不放 tag 列表，也不放发布状态。
- .modpack-updater/known-blobs.jsonl 是发布侧的本地缓存：只追加已确认存在的 blob 哈希，JSON Lines 记录（第一行是头部对象，之后每行一条），可整份删除；桶的位置（endpoint、bucket、prefix）变化、内容损坏或过期时整份重建。它只决定发布时是否向桶确认某个 blob，不参与发布内容，见[降低发布时的 blob 存在性检查成本](../../proposed/architecture/2026-10-20-cheap-blob-existence-checks.md)。
- 该 tag 的发布内容由 <tag>/base/、<tag>/overlay/ 与 <tag>/remove.txt 三者合成，见[发布者 overlay 目录与 base 删除清单](2026-10-14-publisher-overlay.md)：base/ 承载官方包解出的树（保留目录结构），overlay/ 承载发布者自己的新增与覆盖，remove.txt 从 base/ 中剔除路径。base/ 的更新由发布者手工负责，工具不参与。
- 本记录原先的「<tag>/base/ 是发布内容的全部来源」与「init 只铺 base/」两条事实已被[发布者 overlay 目录与 base 删除清单](2026-10-14-publisher-overlay.md)部分取代；其余决定不变。
- tag 与 release_id 都由命令行指定，工作区不记录它们：publish --tag <tag> --id <release_id>，例如 --id 2.9.0-RC1。
- publish --tag <tag> --id <release_id> 发布；加 --dry-run 时只计算将要发生的事（含 base/、overlay/ 与 remove.txt 的合并），不读 config.json、不联网、不写入任何对象。
- 每次真正发布都在 .modpack-updater/logs/ 下留一份运行日志，名字带时间、命令与 tag（例如 2026-10-14T05-55-23Z-publish-client.log）。日志记的是逐文件的过程，比终端默认显示的多，但只在终端上原地重画的上传进度条不进日志；只有发布成功时才写，--dry-run 与被拒绝的发布都不留文件。
- 发布不做与上一次 release 的比较：base/ 里没被 remove.txt 剔除的文件与 overlay/ 里的文件进本次清单，其余不进。因此"路径消失"不会在发布侧被推断出来，只会在订阅者拿自己的清单与本次清单对比时显形；订阅者如何处理消失的路径见[订阅者工作区与更新流程](2026-10-14-subscriber-workspace.md)。
- 同一个 --id 已经存在时 publish 直接报错退出：releases/ 只增不改，修正内容必须换一个 release_id。

## 曾考虑的替代方案

**每次发布与上一次 release 清单比较，算出增删。** 不采用：比较能自动发现 base/ 里消失的路径，但要求工作区维护一个明确的"上一次发布"基准；发布侧交出完整清单已经足够——两份清单在订阅者侧相减就能得到同样的结果。

## 后果

- 一次发布只依赖工作区里的 config.json、<tag>/base/、<tag>/overlay/、<tag>/remove.txt 与工具本体：结果是桶中出现 blobs/ 里尚不存在的内容与 metadata/<tag>/releases/<id>.json，并把该 tag 的 latest.json 指向它。
- base/ 里未被 remove.txt 命中的文件、以及 overlay/ 里的文件出现在本次清单里并指向按内容哈希寻址的 blob；被 remove.txt 命中的 base/ 路径与两个输入里都没有的路径不出现在清单里。
- --dry-run 不写入桶，也不读 config.json、不联网，可以在还没有凭据的机器上先看一遍将要发生的事。
- 发布只在第一次见到某个 blob 时向桶确认它，之后由 known-blobs.jsonl 直接跳过；缓存丢失或丢弃只会让这些确认重新发生一次，不改变发布结果。
- release_id 由人手输入，容易拼写不一致或重发同一个 id；重发同一个 id 会被拒绝，而 releases/<id>.json 只增不改意味着发布者只能换 id 来修正内容。
- 发布侧不比较上一次 release，发布者不会得到"某个路径消失了"的提示；这类变化只在订阅者侧显形，一旦订阅者按删除处理，误删很难回收。
- base/ 与 overlay/ 完全由发布者手工维护，工具不校验 base/ 是否真的来自官方包、也不校验 overlay/ 里的文件是否该覆盖 base/：多放、少放、误把用户数据放进去都会被原样发布出去；remove.txt 写错则把删除指令一并发出（见[发布者 overlay 目录与 base 删除清单](2026-10-14-publisher-overlay.md)）。
- .modpack-updater/logs/ 只增不减：发布越频繁，工作区里留下的日志越多，目前没有清理策略。
