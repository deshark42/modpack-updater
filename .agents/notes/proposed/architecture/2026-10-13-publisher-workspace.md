# Agent Note: 发布者工作区与发布命令

Status: proposed

## 问题

云端只负责存放发布内容（见[对象存储布局](2026-10-13-object-storage-layout.md)），发布者本地还需要一份约定：要发布的那棵实例目录放在哪里、S3 连接信息放在哪里、一次发布怎样在一次调用里说清发布哪条 tag 流、这份发布叫什么。没有这份约定，发布者每次都要手工挑文件、手工记住上一次发过什么。

## 提案

发布者在 <workspace>/ 下工作，布局为：

    <workspace>/
    ├── .modpack-updater/
    │   └── config.json              # S3 协议所需的配置
    ├── <tag>/
    │   └── base/                    # 要追踪的文件，保留目录结构
    └── modpack-updater-<version>.jar

- config.json 只放 S3 协议需要的配置（连接、凭据等，具体字段待定）：不放 tag 列表，也不放发布状态。
- <tag>/base/ 里的文件就是该 tag 发布内容的全部来源，保留目录结构。它的更新由发布者手工负责，工具不参与。
- tag 与 release_id 都由命令行指定，工作区不记录它们：publish --tag <tag> --id <release_id>，例如 --id 2.9.0-RC1。
- 发布命令有两条：publish --tag <tag> 直接发布；publish --tag <tag> --dry-run 只演示将要发生的事，不写入任何对象。

发布不做与上一次 release 的比较：base/ 里有就进本次清单，没有就不进。因此"路径消失"不会在发布侧被推断出来，只会在订阅者拿自己的清单与本次清单对比时显形；订阅者如何处理消失的路径见[立项提案](2026-10-11-in-place-modpack-updates.md)，目前尚未定。

同一个 --id 已经存在时的行为待定：releases/ 只增不改，最保守的做法是拒绝重发。

## 曾考虑的替代方案

**每次发布与上一次 release 清单比较，算出增删。** 暂时不采用：比较能自动发现 base/ 里消失的路径，但要求有一个明确的"上一次发布"基准；当前想法是"有就是有、没有就是没有"，如果后续有更好的做法可以推翻这一条。

## 验收标准

- 在只有 .modpack-updater/config.json、<tag>/base/ 与工具本体的工作区里执行 publish --tag <tag> --id <id>，桶中出现 blobs/ 里尚不存在的内容、metadata/<tag>/releases/<id>.json，并更新该 tag 的 latest.json。
- base/ 里存在的每个文件都出现在本次清单里并指向按内容哈希寻址的 blob；base/ 里没有的路径不出现在清单里。
- --dry-run 不写入桶，只展示将要发生的事。
- 发布不依赖工作区里除 config.json、<tag>/base/ 与工具本体之外的任何文件。

## 风险

- release_id 由人手输入，容易拼写不一致或重发同一个 id；而 releases/<id>.json 只增不改，重发同 id 的行为必须先明确。
- 发布侧不比较上一次 release，发布者不会得到"某个路径消失了"的提示；这类变化只在订阅者侧显形，一旦订阅者按删除处理，误删很难回收。
- base/ 完全由发布者手工维护，工具不校验它是否真的来自官方包：多放、少放、误把用户数据放进 base/ 都会被原样发布出去。
