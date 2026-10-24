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
│   ├── known-blobs.jsonl        # 发布侧的 blob 存在性缓存，见下文
│   └── logs/                    # 每次真正发布的运行日志
├── <tag>/
│   ├── base/                    # 官方包解出的树，保留目录结构
│   ├── overlay/                 # 发布者自己的文件：对 base/ 的新增或覆盖
│   └── remove.txt               # 从 base/ 中剔除的路径，一行一条
└── modpack-updater-<version>.jar
```

- 工作区骨架由 init 铺出：modpack-updater init --tag <tag> 写出 config.json 模板与 <tag>/base/、<tag>/overlay/、<tag>/remove.txt；已经存在的文件只补缺失字段或只创建缺失的输入，不改写已有内容。
- config.json 只放 S3 协议需要的配置，字段定义见[发布内容格式与连接配置](2026-10-14-release-manifest-and-config.md)：不放 tag 列表，也不放发布状态。
- `.modpack-updater/known-blobs.jsonl` 是发布侧的 blob 存在性缓存：只追加已确认存在于桶中的块哈希与块表 id，可整份删除，不参与发布内容。用途、格式与失效规则见[降低发布时的 blob 存在性检查成本](2026-10-20-cheap-blob-existence-checks.md)。
- tag 的发布内容由 `<tag>/base/`、`<tag>/overlay/` 与 `<tag>/remove.txt` 三者合成：base/ 承载官方包解出的树（保留目录结构），overlay/ 承载发布者自己的新增与覆盖，remove.txt 从 base/ 中剔除路径。合并规则与 remove.txt 语法见[发布者 overlay 目录与 base 删除清单](2026-10-14-publisher-overlay.md)；base/ 的更新由发布者手工负责，工具不参与。
- tag 与 release_id 都由命令行指定，工作区不记录它们：publish --tag <tag> --id <release_id>，例如 --id 2.9.0-RC1。
- publish --tag <tag> --id <release_id> 发布。
- 每次真正发布都在 .modpack-updater/logs/ 下留一份运行日志，名字带时间、命令与 tag（例如 2026-10-14T05-55-23Z-publish-client.log）。日志记的是逐文件的过程，比终端默认显示的多，但只在终端上原地重画的上传进度条不进日志；只有发布成功时才写，被拒绝的发布不留文件。
- 发布不做与上一次 release 的比较：本次合成出的有效清单就是发布的全部内容，因此"路径消失"不会在发布侧被推断出来，只会在订阅者拿自己的清单与本次清单对比时显形；订阅者如何处理消失的路径见[订阅者工作区与更新流程](2026-10-14-subscriber-workspace.md)。
- 同一个 --id 已经存在时 publish 直接报错退出：releases/ 只增不改，修正内容必须换一个 release_id。
- publish --cache-max-age-days <天数> 只调整 known-blobs.jsonl 的有效期（默认 30 天，至少 1 天），不影响发布内容或桶中的对象。

## 曾考虑的替代方案

**每次发布与上一次 release 清单比较，算出增删。** 不采用：比较能自动发现 base/ 里消失的路径，但要求工作区维护一个明确的"上一次发布"基准；发布侧交出完整清单已经足够——两份清单在订阅者侧相减就能得到同样的结果。

## 后果

- 一次发布只依赖工作区里的 config.json、<tag>/base/、<tag>/overlay/、<tag>/remove.txt 与工具本体：结果是桶中出现该发布需要的块、块表与 metadata/<tag>/releases/<id>.json，并把该 tag 的 latest.json 指向它。
- 发布不写临时文件：整份清单在接触存储之前就已算完，失败时桶与工作区都不留半成品。
- 工作区不记录已经发布过哪些 id：命名只能由发布者自己维护，写错 id 就会多出一个 release。
- 发布侧不比较上一次 release，发布者不会得到"某个路径消失了"的提示；这类变化只在订阅者侧显形，一旦订阅者按删除处理，误删很难回收。
- .modpack-updater/logs/ 只增不减：发布越频繁，工作区里留下的日志越多，目前没有清理策略。
