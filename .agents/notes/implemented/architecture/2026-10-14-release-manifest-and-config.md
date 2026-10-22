# Agent Note: 发布内容格式与连接配置

Status: implemented

## 问题

[发布者工作区](2026-10-13-publisher-workspace.md)与[订阅者工作区](2026-10-14-subscriber-workspace.md)都不检查本地文件，而是靠两份 release 清单相减来决定该动哪些路径；两边也都要读 latest.json 才知道有没有新版本。这几份要长期共存、可演进的 JSON 里到底写什么，[对象存储布局](2026-10-13-object-storage-layout.md)只定了位置，字段留白。config.json 由发布者与订阅者共用同一份字段定义，也在这里一次定下。

## 决策

三份文件：release 清单（每个 release 一份，写完不变）、latest（每个 tag 一份，唯一可覆写）、config.json（两端各一份）。发布写入顺序与 blob/、index/ 内部布局见[对象存储布局](2026-10-13-object-storage-layout.md)。

**release 清单** metadata/<tag>/releases/<release_id>.json

```json
{
  "version": 2,
  "tag": "client",
  "release_id": "2.9.0-RC1",
  "published_at": "2026-10-14T01:30:00Z",
  "hash_algorithm": "sha256",
  "files": {
    "config/foo.cfg":          { "hash": "1c0b…", "size": 2048,    "chunks": "5a3e…" },
    "libraries/patches/p.jar": { "hash": "77de…", "size": 8192,    "chunks": "0b91…" },
    "mods/GTNH-2.9.0.jar":     { "hash": "9f2a…", "size": 1234567, "chunks": "c4d7…" }
  }
}
```

- 一次发布就是一份完整清单：files 覆盖该 tag 本次追踪的全部文件，路径相对工作区根，按路径排序。
- files 的值是 {hash, size, chunks}：hash 是整文件 sha256，chunks 是该文件块表的 index_id；哈希算法由 hash_algorithm 声明（当前只有 sha256）；size 让 --dry-run 能报出将下载的文件数与总量。分块与块表的来源见[按内容定义分块传输发布内容](2026-10-22-content-defined-chunked-transfer.md)。
- 清单不记块与块表的路径：路径由 chunks 与块哈希推导，所以调整 blob/、index/ 内部布局不必重写任何清单。
- 只记文件：空目录、权限位、符号链接都不进清单。
- published_at 只作信息，不参与任何判断；tag 与 release_id 是冗余记录，只为清单被单独取出时仍能自证身份。
- version 是格式版本：清单是 2，latest 与订阅者的 current.json 仍带各自的 version 1。

**latest** metadata/<tag>/latest.json

```json
{ "version": 1, "release_id": "2.9.0-RC1" }
```

只回答"这个 tag 现在最新是哪个 release"，其余信息一律去 release 清单取：它要足够小，订阅者每次更新都先读它。

**config.json（发布者与订阅者共用字段定义）**

```json
{
  "endpoint": "https://s3.example.com",
  "region": "us-east-1",
  "bucket": "modpack-updater",
  "prefix": "gtnh/",
  "path_style": true,
  "concurrency": 4,
  "access_key": "…",
  "secret_key": "…"
}
```

- init 写出的模板就是这组键，密钥可以留空，公开桶即整段省掉密钥、工具以匿名方式读取；私有桶才需要。
- 连接信息与密钥只从 config.json 读取，工具不看环境变量：少一条取值路径，少一处需要解释的行为。
- path_style 给 MinIO 与自建端点用，AWS 可省略。
- prefix 可选，默认空。tag 不进 config，由命令参数给出。
- concurrency 是同时在传的块与块表的数量，默认 4，取值 1–64：串行传输等不满带宽。
- region、prefix、path_style、concurrency 都可以整段省掉。工具读到缺字段就把默认值补进文件（保留已有值与不认识的键），于是早先版本写的 config.json 不必手工迁移；补过之后文件不再被改写。

**取值与字符集**

- 路径是工作区相对的 POSIX 路径，用 / 分隔；不得以 / 开头，不得出现空段、. 或 .. 段与反斜杠。
- tag 取 [a-z0-9][a-z0-9._-]*，release_id 取 [A-Za-z0-9][A-Za-z0-9._+-]*。两者都会成为路径段与文件名，字符集之外一律拒绝，否则订阅者拼 <tag>/current.json 时会被路径穿越。
- release_id 在每个 tag 内唯一，不同 tag 之间互不相干。
- 两端遇到不认识的 version 直接报错，不猜着解析。

## 曾考虑的替代方案

**files 用 [{path, hash, size, chunks}, …] 列表。** 不采用：列表天然有序、可流式读，但订阅者每一步都是按路径查（本地清单与目标清单相减），映射省掉一层建索引；排序后的映射已经具备列表的可读性。

**清单里直接记每个文件的块与块表路径。** 不采用：路径由算法、块哈希与 chunks 推导即可，写进清单会把清单与存储布局绑死，日后调整 blob/、index/ 内部布局就要重写全部历史清单。

## 后果

- 内容不变的同一份发布内容（同一份 base/ 与 overlay/）用两个 release_id 各发布一次，两份清单除 release_id 与 published_at 外逐字节一致：序列化确定，不随目录遍历顺序漂移。
- 订阅者只读 latest.json 就能判断本地是否落后；落后时用本地清单与目标清单相减，不读取任何块即可列出新增、删除、修改三类路径，--dry-run 也就能报出将下载的文件数与总字节数而不写盘。
- 遇到不认识的 version、越界的 tag 或 release_id，两端都在动手之前报错退出。
- 公开桶（config.json 里没有密钥）下订阅者能完成更新；私有桶下 config.json 里的密钥足以完成读写。
- 清单整体进内存：十万级文件的整合包会明显吃内存与解析时间；届时换流式解析即可，格式不必变。
- tag、release_id、published_at 是冗余记录，容易被当成判据；实现上不允许任何逻辑依赖它们。
- 路径与标识的字符集若不严格执行，<tag>/current.json 这类拼接会被路径穿越，所以校验要在两端各自的入口做一次。
- 密钥只能写进 config.json，就会随工作区一起被复制、备份、外发；这是为了"只从一处取值"而接受的代价，公开桶不需要密钥，私有桶的使用者要自己看住这个文件。
