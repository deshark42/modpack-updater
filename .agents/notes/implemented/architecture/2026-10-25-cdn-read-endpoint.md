# Agent Note: 经 CDN 读取对象存储

Status: implemented

## 问题

在此之前，订阅者取发布只经过 `S3Store`：地址由 `endpoint`、`bucket` 与 `path_style` 拼成 `<bucket>.<endpoint 主机>/<key>` 或 `<endpoint 主机>/<bucket>/<key>`；config 里填了密钥时，每个请求都按 SigV4 签过，`Host`、`x-amz-content-sha256`、`x-amz-date` 都在被签的头里；写入对象时不带任何缓存相关的头。[对象存储布局](../../implemented/architecture/2026-10-13-object-storage-layout.md) 把缓存与一致性整个交给部署方，并约定公开桶匿名读、私有桶的订阅者填密钥。

一层 CDN 挡在桶前面时，三件事各自成为障碍（这三条是这类部署的常见形态，属于部署假设，不是本工具验证过的事实）：

- **地址拼不出来。** CDN 一般把桶挂在自己的主机名根下（`https://cdn.example.com/<key>`），或挂在某个子路径下；`endpoint` 只接受不带路径的主机，地址构造又总会把 `bucket` 插进主机名或路径，于是没有任何配置能拼出这种 URL。
- **签名过不了边。** 签名针对的是客户端请求的那台主机与那组头；CDN 转发后，源站按它收到的 `Host` 与头重新构造规范请求，签名是否仍然有效取决于 CDN 是否改写 `Host`、是否原样转发签名头。很多 CDN 还拒绝缓存带 `Authorization` 的响应，签名本身就让缓存失去意义。
- **缓存语义是空的。** `latest.json` 是唯一可覆写的对象：不带 `--id` 时，update 的第一件事就是读它；没有 `Cache-Control` 时 CDN 按自己的默认 TTL 缓存它，订阅者会一直看到旧 release。反过来，只增不改的块、块表与 release 清单没有任何缓存指示，CDN 对它们只能用默认策略，最该省的源站流量省不下来。

工具把缓存与一致性整个交给部署方，但部署方手里没有旋钮：工具既拼不出 CDN 的地址，也不在写入时声明哪个对象可变、哪个不可变。部署只能靠每条 CDN 规则去猜对象语义，猜错的表现就是更新卡在旧版本，或者 CDN 形同虚设。目标是在不改变写侧的前提下，把「读走哪条连接」与「每个对象能缓存多久」变成 config 与对象本身的事实。

## 决策

写侧继续直连对象存储，读侧增加一条可选的匿名 GET 连接；发布端为每个对象声明缓存语义。两块可以分开验证（缓存头独立可测，也能救「path-style 代理式 CDN」的部署），但放在一起，因为读连接的价值依赖 `no-store` 被尊重，而它们来自同一个部署形态。

### read_endpoint

config.json 增加可选字段 `read_endpoint`：订阅者读取对象的基础 URL。对象键接在它后面，不插入 `bucket`。

```json
{
  "endpoint": "https://s3.example.com",
  "read_endpoint": "https://cdn.example.com/modpacks",
  "region": "us-east-1",
  "bucket": "modpack-updater",
  "prefix": "gtnh/"
}
```

- 每个读请求的地址是 `<read_endpoint>/<prefix><key>`，例如 `https://cdn.example.com/modpacks/gtnh/metadata/client/latest.json`。`prefix` 在 config 解析时总被规范成空串或带结尾 `/`，`read_endpoint` 去掉结尾 `/` 后再拼接，空 prefix 与非空 prefix 都不会多出或少掉斜杠。
- 桶挂在 CDN 主机根、主机名里带桶、或挂在某个子路径下，三种形态都能表达：`read_endpoint` 指向「对象键之前的那一段」。`bucket` 与 `path_style` 只参与写侧寻址，读侧不看它们。
- 只用于 `update` 的 GET；写仍走 `endpoint`，发布端的存在性 HEAD 也仍直接问源站：CDN 无法权威回答「桶里有没有」，一次缓存命中不能当成对象存在。
- 经 `read_endpoint` 的请求一律匿名：不带 `Authorization`，不带 `x-amz-*`，即使同一份 config 里填了密钥（工作区同时用于发布与更新时）也不签名。签名穿不过 CDN 这一层，签了只会失败。
- 读请求带 `Accept-Encoding: identity`：对象按存储时的 `Content-Encoding` 声明压缩，不再让 CDN 在传输中另压一层。
- 读侧只有 GET。读连接不接受 HEAD 或 PUT，接错线会立刻失败，而不是悄悄打到源站。
- `read_endpoint` 必须指向写侧同一个桶的同一个 `prefix`；工具无法验证两者的对应关系，配错表现为 404 或哈希校验失败。
- 取值校验与 `endpoint` 同形但去掉一条：http/https、有主机、无 query/fragment/userinfo、去掉结尾的 `/`，允许带路径；`endpoint` 的「IP 字面量必须 `path_style`」不适用，因为读侧不插 `bucket`（自托管反向代理或本地 FakeS3 的 `http://127.0.0.1:9000/...` 是合法读地址）。缺失或空字符串表示未配置。
- 未配置时一切照旧：直连对象存储、现有寻址、现有请求头。

### 写入端的缓存语义

publish 在每个 PUT 上带 `Cache-Control`：

- `metadata/<tag>/latest.json`：`no-store`。唯一可覆写的对象，任何一层缓存都不许存。
- `blob/`、`index/`、`metadata/<tag>/releases/<id>.json`：`public, max-age=31536000, immutable`。它们只增不改不删：块与块表按内容哈希寻址；release 清单不可变的前提是 publish 对已存在的 release_id 直接报错（`Publish.java:119-122`）、从不改写已发布的内容，未来若允许改写某个 release，这一条必须同步收紧。
- 缓存语义由对象种类决定，`ObjectKeys.cacheControl` 按键种类给出一个显式的「种类 → 头」映射：未列出的种类不带缓存头，不得默认 `immutable`；新增对象种类时必须显式决定缓存语义。
- 这个头与对象一起进 SigV4 签名：`cache-control` 出现在 PUT 的 `SignedHeaders` 里，源站不会收到一个未签名却生效的缓存指令。

用 `no-store` 而不是 `no-cache`：指针只有几十字节，省下的是 304 的带宽，代价却是让「边存着旧指针」这件事重新有可能；不允许存最省心。

缓存语义写在对象上而不是写进 config：可变或不可变是对象本身的性质，不是部署的选择，写在对象上就只有一处事实来源，部署方不必为每个前缀各配一条规则。CDN 缓不缓存、TTL 多长仍由部署方与 CDN 决定，但它至少是在一个显式的头上做决定。

### 读取路径

`Update` 的全部读取——latest、release 清单、块表、块——从按 `read_endpoint` 构造的读连接发出；本地状态（`current.json`、`releases/` 下的清单、`backup/`）不变。差集、复用、拼装、提交顺序都不动。

实现上把原来 `S3Store` 里「对象键 → URI」与「请求怎么发」分开：`ObjectReader` 是读侧接口（`key`、`getBytes`、`download`）；`S3Connection` 抽象基类承担两者共用的部分——地址拼接、HTTP 客户端与超时、不跟随重定向、gzip 解压、错误映射、原子落盘；`S3Store` 成为写侧子类（`bucket`/`path_style` 寻址、签名、PUT 与 HEAD、缓存头），`S3ReadStore` 成为读侧子类（`read_endpoint` 加对象键、匿名 GET、`Accept-Encoding: identity`，不提供 HEAD 或 PUT）。`Update` 按 `readEndpoint` 是否为 `null` 选实现，写侧代码路径与请求逐字不变。

读连接提供与写连接同一套对象键（`prefix + key`，不含 bucket），供 Update 现有的标签使用。读失败的错误信息报出实际请求的读 URL 与 `read_endpoint`，而不是源站的 bucket 与主机名——经 CDN 时源站可能根本不可达，报源站只会误导。

`getBytes` 对 `Content-Encoding: gzip` 的解压逻辑照旧（清单是 gzip 存放的）；块（`download`）从不压缩存放，读连接一旦在块响应上看到非 `identity` 的 `Content-Encoding`，按读失败报出 URL，不把转码后的字节当作对象字节写进工作区。

### 配置、模板与兼容

- `Config` 的 record 组件增加 `readEndpoint`。这打断了 8 处直接构造（`S3StoreTest` 6 处、`BlobCacheTest` 与 `PublishTest` 各 1 处），由 `TestWorkspace` 的配置辅助统一构造；不保留旧的 8 参构造器，否则测试会绕过新字段。
- `read_endpoint` 加入 `Config.DEFAULTS`，为旧配置补上空串：`init` 模板写出 `"read_endpoint": ""`（紧跟 `endpoint`），`fillMissing` 给没有这一行的旧 config 补上；空串在解析时按未配置处理（沿用 `Fields.optionalString` 的空白回退语义）。
- 校验发生在共享的 `Config.parse`：非法 `read_endpoint` 会让任何构造 Config 的命令在发请求前失败，包括只用写侧的 `publish`。这是「一处校验」的有意选择；`init` 只写模板或补默认值、不构造 Config，因此不会因它失败。
- 订阅端的 config 仍然要求写侧字段（`endpoint`、`bucket` 等），即使它只经 CDN 读。把「订阅端只需要读地址」做成条件必填是更大的改动，本记录不做。
- 未配置时行为逐字不变：地址、方法、头都不变。

### 不做的事

- 私有 CDN 的访问签名（CloudFront signed URL/cookie、Cloudflare Access 等）：不支持，读侧一律匿名。
- 订阅端只需读地址的 config 形态：写侧字段仍必填。
- 读侧的 4xx 负缓存清理、重试与自动回退到源站：工具不做，交给 CDN 与部署。
- 经 CDN 写：写侧永远直连 `endpoint`。
- 把读侧做成第二个 S3 协议客户端：读侧只是匿名 GET，没有 LIST、HEAD、签名或 S3 错误码语义。

## 测试

- 读地址构造（`S3ReadStoreTest`）：主机根、带子路径、带桶子域三种 `read_endpoint` 都直接拼成 `<base>/<prefix><key>`，工具不再插入 bucket；prefix 为空时不出现双斜杠。
- 匿名与请求头（`S3ReadStoreTest`）：读请求是 GET、路径为 `<prefix><key>`、不带 `Authorization` 与 `x-amz-*`、带 `Accept-Encoding: identity`。
- 读失败（`S3ReadStoreTest`）：消息以实际读 URL 开头；块响应带非 `identity` 的 `Content-Encoding` 时按读失败处理，目标文件不存在。
- 配置（`ConfigTest`）：缺失与空串按未配置处理；非法（非 http(s)、无主机、带 query/fragment/userinfo）是 user error；带路径与 IP 字面量主机合法；`fillMissing` 给旧 config 补上 `"read_endpoint": ""`，init 模板包含该键。
- 缓存语义（`ObjectKeysTest`、`PublishTest`）：`blob/`、`index/` 与 release 清单的 PUT 带 `public, max-age=31536000, immutable`，latest 的 PUT 带 `no-store`，其他键不带缓存头；断言值取自生产常量，不手抄字面量。
- 分工（`UpdateTest`）：订阅者 config 的 `endpoint` 指向不可用地址 `http://127.0.0.1:1`、`read_endpoint` 指向 FakeS3 的 `<url>/<bucket>` 且带密钥；一次 update 仍成功（任何读若漏到 `endpoint` 就会连接失败），FakeS3 记录里每次请求都是 GET、路径为 `<bucket>/<prefix><key>`、无 `Authorization` 与 `x-amz-*`；再次 update 只有一次 latest 的 GET。
- 失败面（`UpdateTest`）：读请求被拒（500）时 update 以 UserError 结束，消息含实际读 URL 与状态码，`current.json` 不变、没有半成品文件、没有 PUT，也没有回退到 `endpoint`。
- 未配置：移除 `read_endpoint` 后现有 publish/update 测试的断言不变，只改 `Config` 的构造点。
- CDN 自身的缓存行为无法用现有测试设施断言：FakeS3 只持久化并回放 `Content-Encoding`，另造一个按 `Cache-Control` 决定存取的 CDN 替身会越过 `docs/testing.md` 的「FakeS3 是唯一共享存储替身」约束；这部分留给部署侧核对，见下文「延后」。

## 曾考虑的替代方案

**只在部署文档里写 CDN 规则，不改代码。** 唯一出路是把 `endpoint` 指向一个以 path-style 反代 S3 API 的 CDN：工具必须拼得出地址，而 `endpoint` 不接受路径、键前又总多一个 bucket 段，把桶挂在 CDN 主机根（自定义域名最常见的形态）表达不了；即便代理能让读成功，签名头要原样转发、缓存也不能带 `Authorization`，正确性全压在每条 CDN 规则上，默认策略会悄悄把 latest 缓存住。对象自带的 `Cache-Control` 是同一件事的一次性、随对象走的版本。

**预签名 URL。** 发布端生成短期 URL 放进 latest 或清单。不采用：release 长期、不可变，URL 会过期；URL 写进元数据把发布绑死在一个部署与一组凭证上，还让每个订阅者拿到可复制的凭证；自定义域名的 CDN 对私有桶通常只认自家签名方案（CloudFront signed URL/cookie、R2 token），S3 预签名在 CDN 上并不通。

**按对象拆传输：latest 与清单直连 S3（签名），块与块表经 CDN。** 不采用：它把「订阅者必须能直连源站」原样留在原地，而公开边挡在私有桶前面正是为了让订阅者不需要它；拆开后仍要维护两套连接与两套错误面，缓存头的工作一分不能少。它唯一换来的，是清单与指针不依赖 CDN 的新鲜度——这正是 `no-store` 要解决的问题，为它多付一层复杂度不划算。

**发布也经 CDN 写。** 不采用：S3 兼容存储的自定义域名基本只读，签名的 PUT 过不了边；公开可写的边本身不安全。写侧留在 `endpoint`。

**给 latest 的 GET 加 cache-busting 查询参数，代替 `Cache-Control`。** 不采用：绕不绕得开缓存取决于 CDN 是否把 query 算进缓存键，并不通用；它把新鲜度契约放进 URL 而不是响应头，与「对象语义写在对象上」相反。`no-store` 失效时它才有价值，那时再作为兜底。

**支持各家 CDN 的私有访问签名，让私有桶也能经 CDN 读。** 不采用：CloudFront signed URL/cookie、Cloudflare Access token 等每家一套签名与凭证，工具没有共同抽象；这件事可以之后单独加在读连接上。

**`read_endpoint` 只替换主机名，仍按 `bucket`/`path_style` 拼路径。** 不采用：CDN 的自定义域名通常不镜像 S3 的寻址（桶在主机根或子路径，而不是 `<bucket>.host` 或 `host/<bucket>`），能拼出的地址恰是拼不出 CDN 地址的那种；而且它把写侧的两个寻址开关带进读侧，正是读 URL 想避免的混淆。

## 后果

- 收益：CDN 挡在桶前的部署第一次能表达——读地址可以指向 CDN 的主机根或子路径，公开边（OAI/OAC 回源、桶仍私有）的订阅者不必也不需要源站凭证；不可变对象可以长缓存，源站流量随订阅者数量摊薄；latest 用 `no-store` 保证每次 update 都看到当前指针。
- 代价：`no-store` 让每次 update 都向源站取一次 `latest.json`，这是为指针新鲜付的固定成本。
- CDN 不认 `no-store`、或部署方给 `metadata/` 前缀加了强制 TTL，订阅者会卡在旧 release，客户端无法发现或纠正。缓解：升级后清一次 `metadata/` 缓存，部署侧明确「不要覆盖源站 TTL」；最坏情况下，问题也从「每个部署自己发明规则」变成「别覆盖一个显式的头」。
- 读 URL 匿名可读：拿到 URL 的人都能读 release 与块，访问控制全交给 CDN；私有桶加私有 CDN（需要 CDN 侧签名）不在支持范围。这与公开桶的既有取舍同向：把访问控制交给存储或部署方。
- 改动前写入的不可变对象没有 `Cache-Control`，且内容去重不会重新 PUT 它们，CDN 对它们只能用默认策略；正确性不受影响。latest 每次发布都重写，升级后第一次发布就带上新头。
- 块下载对 `Content-Encoding` 没有处理：CDN 若对 `blob/` 或 `index/` 做内容转码，读侧会明确报错（不会静默写坏文件），但订阅者会卡住；部署侧要禁止全部前缀的转码，而不只是 `metadata/`。
- `read_endpoint` 与写侧必须指向同一个桶的同一个 `prefix`，工具无法验证。配错在启动时发现不了，表现为 404 或哈希校验失败；这是有意接受的代价，自动探测会引入额外读请求与新的失败模式。
- 读侧沿用不跟随重定向的客户端：会重定向的 CDN（URL 规范化、http→https）让请求直接失败，而不是悄悄多一跳；部署侧要保证读地址无重定向。
- 不可变读 URL 永久稳定：CDN 若对 404/403 做负缓存，发布前的误读（例如 `update --id` 指向尚未发布的 release）会把失败钉住一个 TTL；工具不能给错误响应加头，部署侧需确认 CDN 不缓存 4xx。
- 配置里同时存在两种寻址语义（写侧的 `bucket`/`path_style`、读侧的整段 base URL），容易被误以为读侧也会插入 bucket；init 模板与文档（待写，见「延后」）要把「读侧只拼 base URL、前缀与键」写清楚。
- 写入端多一个被签名的头：`Cache-Control` 进入 PUT 的 `SignedHeaders`，改动签名覆盖面的测试因此要跟着更新。

## 延后

- 部署手册（`docs/deployment.md`）尚未落成。CDN 侧的必需条件——读地址不重定向、不改写路径；不覆盖源站 `Cache-Control`（尤其 `metadata/` 下的 `no-store`）；不对任何前缀做内容转码（尤其 `blob/` 与 `index/`）；不在发布前预取尚未发布的不可变 URL；升级后清一次 `metadata/` 的缓存——目前只在上面的「后果」里，读者得自己去找。
- 三条部署侧人工核对同样待写入手册：连续发布 A、B，订阅者 update 到 A 后下一次看到 B；第二个订阅者首次更新同一 release 时每个块与块表在 CDN 上最多回源一次；读地址无重定向、CDN 不缓存 4xx。
- 私有桶 + 公开边缘（OAI/OAC 回源）与私有桶 + 私有边缘（需 CDN 侧签名）两种部署形态的说明也待写；仓库目前只有 `docs/testing.md`，没有 README，若补文档，链接应从 `AGENTS.md` 引入。

## 相关

以下记录与本次交付部分重叠，事实已随交付同步，且双向互链：

- [对象存储布局](../../implemented/architecture/2026-10-13-object-storage-layout.md)：配置了 `read_endpoint` 时，私有桶的订阅者填了密钥也匿名读经 CDN 的对象；写入端开始按对象种类声明缓存语义，不再把可变与不可变完全留给部署方去猜。
- [发布内容格式与连接配置](../../implemented/architecture/2026-10-14-release-manifest-and-config.md)：config 字段表增加 `read_endpoint`；`fillMissing` 给旧 config 补上默认空串，`init` 模板多一行，早先版本写的 config 仍不必手工迁移。
- [发布者工作区与发布命令](../../implemented/architecture/2026-10-13-publisher-workspace.md)：config.json 的描述从「S3 协议所需的配置」放宽为「连接与桶位置所需的配置」，因为订阅端的 `read_endpoint` 是 CDN 地址而不是 S3 协议配置。
- [降低发布时的 blob 存在性检查成本](../../implemented/architecture/2026-10-20-cheap-blob-existence-checks.md)：`read_endpoint` 是读侧字段，不进入 `known-blobs.jsonl` 的指纹（仍只由 endpoint、bucket、prefix 构成），缓存机制本身不变。
- [订阅者工作区与更新流程](../../implemented/architecture/2026-10-14-subscriber-workspace.md)：流程不声明读走哪条连接，只在 config 处加了一条指向本记录的说明；不按取代处理。

以下记录不受影响：[以版本差异就地更新 Modpack](../../implemented/architecture/2026-10-11-in-place-modpack-updates.md)、[发布者 overlay 目录与 base 删除清单](../../implemented/architecture/2026-10-14-publisher-overlay.md)、[按内容定义分块传输发布内容](../../implemented/architecture/2026-10-22-content-defined-chunked-transfer.md)。
