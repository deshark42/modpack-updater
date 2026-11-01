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
│   ├── <tag>/channels.json      # 本地用过的非默认通道名，仅供向导建议
│   └── logs/                    # 每次真正发布的运行日志
├── <tag>/
│   ├── base/                    # 原样提供发布文件
│   ├── base_pack/               # ZIP / 7z 按各包相对父目录展开，与 base 合并
│   ├── overlay/                 # 发布者自己的文件：对逻辑 base 的新增或覆盖
│   └── remove.txt               # 从逻辑 base 中剔除的路径，一行一条
└── modpack-updater.jar
```

- 工作区骨架由 init 铺出：modpack-updater init --tag <tag> 写出 config.json 模板与 <tag>/base/、<tag>/base_pack/、<tag>/overlay/、<tag>/remove.txt；已经存在的文件只补缺失字段或只创建缺失的输入，不改写已有内容。
- config.json 放连接、桶位置与本地签名密钥路径，字段定义见[发布内容格式与连接配置](2026-10-14-release-manifest-and-config.md)：不放 tag 列表，也不放发布状态。订阅者的读地址（read_endpoint）也在这里，见[经 CDN 读取对象存储](../../implemented/architecture/2026-10-25-cdn-read-endpoint.md)。
- `.modpack-updater/known-blobs.jsonl` 是发布侧的 blob 存在性缓存：只追加已确认存在于桶中的块哈希与块表 id，可整份删除，不参与发布内容。用途、格式与失效规则见[降低发布时的 blob 存在性检查成本](2026-10-20-cheap-blob-existence-checks.md)。
- tag 的发布内容由逻辑 base、`<tag>/overlay/` 与 `<tag>/remove.txt` 三者合成：逻辑 base 是 `<tag>/base/` 原样文件与 `<tag>/base_pack/` 内 ZIP / 7z 输出的无冲突并集，包内路径挂到各包的相对父目录。overlay/ 承载发布者自己的新增与覆盖，remove.txt 从逻辑 base 中剔除路径。合并规则与 remove.txt 语法见[发布者 overlay 目录与 base 删除清单](2026-10-14-publisher-overlay.md)。官方包版本仍由发布者选择和替换；输入组合、临时展开、冲突拒绝与清理见[可组合 base 输入](../feature/2026-10-25-composable-base-inputs.md)，本记录继续持有工作区、配置与发布命令的约定。
- `.modpack-updater/<tag>/channels.json` 只保存通过 CLI 成功发布、promote 或按通道更新后用过的非默认 channel 名，去重排序供向导编号选择；latest 内置且仍是默认值，不枚举服务器、不记录 release 基线或当前选择。取消、失败、无指针发布和 `update --id` 不新增记录；历史读写失败只警告，损坏内容不被覆盖。
- tag、release_id 与 channel 都只在本次调用选择，不写入配置。完整参数仍可直接发布：publish --tag <tag> --id <release_id> [--channel <name>]，例如 --id 2.9.0-RC1 --channel beta；真实终端中缺少 tag 或 id 时，publish 启动向导，选择工作区、编号 tag 与 channel 后展示已验证基线和实际载荷的差异，最后输入新 id 即确认发布；预填 id 须重输，缓存有效期取默认值或参数，不再询问。无终端时缺参仍是用法错误，不等待标准输入，避免自动化任务挂起。
- 发布还需要本地私钥，默认由 keygen 写入 `.modpack-updater/keys/`；只把公钥交给订阅者，密钥路径与签名提交顺序见[发布清单签名](../feature/2026-10-25-ed25519-release-signatures.md)。
- publish --tag <tag> --id <release_id> 发布，完整上传清单与签名后只更新所选 channel 指针，默认 latest；加无值开关 `--no-latest` 时只上传完整签名 release，不创建或改写任何指针，测试者通过 `update --id` 获取。`--no-latest` 不能与显式 `--channel` 并用。两种选择只对本次调用生效，不写入配置，也不根据版本号后缀推断，避免测试策略意外影响后续正常发布。
- 向导确认前会读取配置和 S3 基线、合并与分块载荷，压缩包可在专属临时目录展开；不上传、不补写配置、不创建日志、blob 缓存或通道历史，结束时清理本次解包目录（临时父目录可能保留）。`:q`、EOF 或最终 id 留空均取消本次调用；预览的信任边界和复用语义见[交互发布基线预览](../feature/2026-11-01-publish-baseline-preview.md)。真正开始的 publish 在 .modpack-updater/logs/ 下留一份运行日志：按天一个文件，名字带命令、tag 与日期（例如 publish-client-2026-10-14.log），同一天的多次运行追加进同一份。终端只显示 INFO 及以上的里程碑，日志文件还记下 DEBUG 级的逐文件过程，比终端显示的多，但只在终端上原地重画的上传进度条不进日志；日志在开始工作之前就打开，失败的发布也留文件。
- 本次合成出的有效清单仍是发布的全部内容，不根据旧 release 推导发布内容。原先不做发布侧比较的选择由[交互发布基线预览](../feature/2026-11-01-publish-baseline-preview.md)部分取代：向导展示相对所选 channel 的增删改，但完整参数发布仍不读取旧清单。订阅者仍拿自己的清单与本次完整清单比较，见[订阅者工作区与更新流程](2026-10-14-subscriber-workspace.md)。
- 同一 tag 内的 --id 跨 channel 不可重用，已经存在时 publish 直接报错退出：releases/ 只增不改，清单与签名由所有 channel 共享，修正内容必须换一个 release_id。
- promote --tag <tag> --id <release_id> [--channel <name>] 从源站读取已有清单与签名，使用本地可信公钥先验签再解析并核对身份，随后只写目标指针（默认 latest）；不需要私钥或本地发布输入，不改写任何不可变对象、配置、blob 缓存或实例当前版本。无需源 channel，也接受无指针 release；重复执行和指向旧 id 都允许，不提供块完整性审计或并发锁。CLI 记录日志及成功后的目标通道历史；详细用法与边界见 [README](../../../../README.md#将已测试的-release-推进到正式-channel)。
- publish --cache-max-age-days <天数> 只调整 known-blobs.jsonl 的有效期（默认 30 天，至少 1 天），不影响发布内容或桶中的对象。

## 曾考虑的替代方案

**每次发布与上一次 release 清单比较，算出增删。** 不采用：比较能自动发现 base/ 里消失的路径，但要求工作区维护一个明确的"上一次发布"基准；发布侧交出完整清单已经足够——两份清单在订阅者侧相减就能得到同样的结果。这个理由继续约束发布内容模型和完整参数入口；交互入口的可见预览由[交互发布基线预览](../feature/2026-11-01-publish-baseline-preview.md)另行接受，不维护本地“上次发布”状态。

## 后果

- 一次发布依赖工作区里的 config.json、<tag>/ 下组合后的 base 来源、<tag>/overlay/、<tag>/remove.txt、本地发布私钥与工具本体：结果是桶中出现该发布需要的块、块表、metadata/<tag>/releases/<id>.json 及其 .json.sig，只把该 tag 所选 channel 的指针指向它（默认 latest）；`--no-latest` 保留所有原指针（或继续没有指针），仍占用不可重用的 release_id。
- 目录 base 不写本地内容临时文件；压缩包 base 在工作区内部的专属临时目录展开，并保留到上传结束后清理。整份清单仍在上传前算完并签署。上传中途失败可能留下已上传的块、块表或尚未配齐签名的清单，但不会推进任何 channel 指针；已写入清单的 release_id 不能重用。
- 工作区不记录已经发布过哪些 id：命名只能由发布者自己维护，写错 id 就会多出一个 release。
- 完整参数发布不比较上一次 release，也不会显示路径消失的提示；交互向导会显示相对所选 channel 的删除，但不能代表订阅者当前版本的全部变化。误删仍由完整清单传播到订阅者。
- .modpack-updater/logs/ 按天累积、只保留 30 天：同一天的运行追加进同一份文件，每次开始写日志前按修改时间清掉更早的文件，日志不会无限增长。
