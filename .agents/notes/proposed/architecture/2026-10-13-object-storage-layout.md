# Agent Note: 发布内容的对象存储布局

Status: proposed

## 问题

发布者与订阅者需要一个双方都能访问的交换点：发布者写入一次发布，订阅者取回并应用。它要能容纳 GB 级的二进制文件，要让 client、server 等不同发布流各自独立演进、各自保留历史，还要让订阅者用最低成本知道"最新"是哪一个发布。

## 提案

用 S3 协议的对象存储承载发布内容，布局为：

```
<bucket>/<prefix 可选>/
├── blobs/
│   └── <哈希前两位>/<哈希>
└── metadata/
    └── <tag>/
        ├── latest.json
        └── releases/
            └── <release_id>.json
```

- blobs/ 按内容哈希存放文件内容，路径为 blobs/<哈希前两位>/<哈希>，哈希算法（sha256）由清单的 hash_algorithm 声明：完全相同的文件总是得到相同的路径，所有 tag、所有 release 共享这一层，同一份内容只存一份；两位分片只为让列举与本地挂载不至于把全部对象堆在一个前缀下。
- metadata/<tag>/ 让每个 TAG（client、server、其他）成为一条独立演进、互不影响的发布流。
- metadata/<tag>/releases/<release_id>.json 是一次发布的完整清单：列出该发布实例目录的全部路径与对应 blob，字段与 release_id 形态见[发布内容格式与连接配置](2026-10-14-release-manifest-and-config.md)。
- metadata/<tag>/latest.json 指向该 tag 当前的发布，是订阅者的唯一入口：读到它就知道该取哪个 release，不需要 LIST 桶。

- 桶可以是公开读，也可以是私有的：私有桶的订阅者在配置文件里填写 AccessKey 与 SecretKey。

可变性约定：只有 latest.json 可覆写；blobs/ 与 releases/ 下的对象只增不改不删。

发布按固定顺序写入：先写全部 blobs，再写 releases/<release_id>.json，最后覆写 latest.json。latest.json 是提交点：订阅者要么看到旧的 latest 与旧清单，要么看到新的，不会读到半个 release。

这份布局是[立项提案](2026-10-11-in-place-modpack-updates.md)里"发布到云端"的具体形态。

## 曾考虑的替代方案

本提案没有认真比较过其他存储方案：选择 S3 兼容对象存储是为了先做出一个可用版本，而不是它在比较中胜出。因此这里没有可记录的落选方案；git 仓库、静态 HTTP 服务、自建服务端等方向留待后续评估，本提案不声称它们更差。

## 验收标准

- 发布者能把一次发布写进桶：blobs/ 与 metadata/<tag>/releases/<release_id>.json 落位，且 latest.json 指向它。
- 写入顺序固定为 blobs → metadata/<tag>/releases/<release_id>.json → metadata/<tag>/latest.json；中途失败时，桶里不会留下指向不完整 release 的 latest.json。
- 订阅者只读 metadata/<tag>/latest.json 就能确定要取哪个 release，并据此取回该发布引用的全部 blob。
- 同一份文件内容出现在两个 tag 或两个 release 中时，桶里只存在一份 blob。
- blobs/ 与 releases/ 下的对象写入后不被覆写、不被删除；只有 latest.json 会被覆写。

## 风险

- latest.json 是唯一可变点，订阅者可能读到尚未更新的值，表现为晚一次看到新发布；缓存与一致性属于存储服务与部署方，本工具不做约定。
- 只增不改不删意味着存储随发布历史单调增长，内容去重只能抵消重复的部分，没有清理策略，成本随时间上升。
- 存储方案的替换成本不在接口而在数据：桶布局一旦落定并有发布数据，换用其他存储要迁移全部 blob 与元数据。
