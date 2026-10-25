# Agent Note: 发布者工作区与发布命令

Status: implemented

## 问题

云端只负责存放发布内容（见[对象存储布局](2026-10-13-object-storage-layout.md)），发布者本地还需要一份约定：要发布的那棵实例目录放在哪里、S3 连接信息放在哪里、一次发布怎样在一次调用里说清发布哪条 tag 流、这份发布叫什么。没有这份约定，发布者每次都要手工挑文件、手工记住上一次发过什么。

## 决策

发布者在 <workspace>/ 下工作，布局为：

```
<workspace>/
├── .modpack-updater/
│   ├── config.json              # 连接与桶位置所需的配置
│   ├── keys/                    # keygen 生成发布私钥和可分发的公钥
│   ├── known-blobs.jsonl        # 发布侧的 blob 存在性缓存，见下文
│   └── logs/                    # 每次真正发布的运行日志
├── <tag>/
│   ├── base/                    # 官方包解出的树，保留目录结构；也可改用 base.zip 或 base.7z
│   ├── overlay/                 # 发布者自己的文件：对 base/ 的新增或覆盖
│   └── remove.txt               # 从 base/ 中剔除的路径，一行一条
└── modpack-updater-<version>.jar
```

- 工作区骨架由 init 铺出：modpack-updater init --tag <tag> 写出 config.json 模板与 <tag>/base/、<tag>/overlay/、<tag>/remove.txt；已经存在的文件只补缺失字段或只创建缺失的输入，不改写已有内容。
- config.json 放连接、桶位置与本地签名密钥路径，字段定义见[发布内容格式与连接配置](2026-10-14-release-manifest-and-config.md)：不放 tag 列表，也不放发布状态。订阅者的读地址（read_endpoint）也在这里，见[经 CDN 读取对象存储](../../implemented/architecture/2026-10-25-cdn-read-endpoint.md)。
- `.modpack-updater/known-blobs.jsonl` 是发布侧的 blob 存在性缓存：只追加已确认存在于桶中的块哈希与块表 id，可整份删除，不参与发布内容。用途、格式与失效规则见[降低发布时的 blob 存在性检查成本](2026-10-20-cheap-blob-existence-checks.md)。
- tag 的发布内容由 base、`<tag>/overlay/` 与 `<tag>/remove.txt` 三者合成：base 可以是 `<tag>/base/` 目录，也可以是 `<tag>/base.zip` 或 `<tag>/base.7z`，均保留内部目录结构；overlay/ 承载发布者自己的新增与覆盖，remove.txt 从 base 中剔除路径。合并规则与 remove.txt 语法见[发布者 overlay 目录与 base 删除清单](2026-10-14-publisher-overlay.md)。官方包版本仍由发布者选择和替换；压缩包的来源选择、临时展开与清理见[压缩包 base 输入](../feature/2026-10-25-archive-base-input.md)，本记录继续持有工作区、配置与发布命令的约定。
- tag 与 release_id 都由命令行指定，工作区不记录它们：publish --tag <tag> --id <release_id>，例如 --id 2.9.0-RC1。
- 发布还需要本地私钥，默认由 keygen 写入 `.modpack-updater/keys/`；只把公钥交给订阅者，密钥路径与签名提交顺序见[发布清单签名](../feature/2026-10-25-ed25519-release-signatures.md)。
- publish --tag <tag> --id <release_id> 发布。
- 每次 publish 在 .modpack-updater/logs/ 下留一份运行日志：按天一个文件，名字带命令、tag 与日期（例如 publish-client-2026-10-14.log），同一天的多次运行追加进同一份。终端只显示 INFO 及以上的里程碑，日志文件还记下 DEBUG 级的逐文件过程，比终端显示的多，但只在终端上原地重画的上传进度条不进日志；日志在开始工作之前就打开，失败的发布也留文件。
- 发布不做与上一次 release 的比较：本次合成出的有效清单就是发布的全部内容，因此"路径消失"不会在发布侧被推断出来，只会在订阅者拿自己的清单与本次清单对比时显形；订阅者如何处理消失的路径见[订阅者工作区与更新流程](2026-10-14-subscriber-workspace.md)。
- 同一个 --id 已经存在时 publish 直接报错退出：releases/ 只增不改，修正内容必须换一个 release_id。
- publish --cache-max-age-days <天数> 只调整 known-blobs.jsonl 的有效期（默认 30 天，至少 1 天），不影响发布内容或桶中的对象。

## 曾考虑的替代方案

**每次发布与上一次 release 清单比较，算出增删。** 不采用：比较能自动发现 base/ 里消失的路径，但要求工作区维护一个明确的"上一次发布"基准；发布侧交出完整清单已经足够——两份清单在订阅者侧相减就能得到同样的结果。

## 后果

- 一次发布依赖工作区里的 config.json、<tag>/ 下选定的 base 来源、<tag>/overlay/、<tag>/remove.txt、本地发布私钥与工具本体：结果是桶中出现该发布需要的块、块表、metadata/<tag>/releases/<id>.json 及其 .json.sig，并把该 tag 的 latest.json 指向它。
- 目录 base 不写本地内容临时文件；压缩包 base 在工作区内部的专属临时目录展开，并保留到上传结束后清理。整份清单仍在上传前算完并签署。上传中途失败可能留下已上传的块、块表或尚未配齐签名的清单，但不会推进 latest；已写入清单的 release_id 不能重用。
- 工作区不记录已经发布过哪些 id：命名只能由发布者自己维护，写错 id 就会多出一个 release。
- 发布侧不比较上一次 release，发布者不会得到"某个路径消失了"的提示；这类变化只在订阅者侧显形，一旦订阅者按删除处理，误删很难回收。
- .modpack-updater/logs/ 按天累积、只保留 14 天：同一天的运行追加进同一份文件，每次开始写日志前按修改时间清掉更早的文件，日志不会无限增长。
