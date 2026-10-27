# modpack-updater

Java 17 命令行工具，把 Minecraft 整合包的受追踪文件发布到 S3 兼容对象存储，再按版本差异更新已有实例。文件按内容分块，更新只下载本地没有的块。被替换或删除的文件移入备份目录。

## 发布者首次配置

构建可运行 jar：

```sh
./gradlew shadowJar
```

在发布工作区执行：

```sh
java -jar modpack-updater.jar init --tag client
java -jar modpack-updater.jar keygen
```

`keygen` 不联网、不需要配置对象存储，也不覆盖任何已有密钥。它生成：

- `.modpack-updater/keys/release-private.pem`：Ed25519 私钥，只留在发布者机器上并做好离线备份。
- `.modpack-updater/keys/release-public.pem`：公钥，通过可信渠道交给订阅者。

终端会显示公钥的 SHA-256 指纹，计算对象为 X.509 SubjectPublicKeyInfo DER 字节。POSIX 系统以 `0600` 创建私钥；Windows 使用目录继承的 ACL，请将发布工作区放在仅发布者可访问的位置。不要把整个发布工作区或私钥分发给玩家。

编辑 `.modpack-updater/config.json`，填入对象存储信息。例如：

```json
{
  "endpoint": "https://s3.example.com",
  "bucket": "my-modpack",
  "prefix": "example/",
  "region": "us-east-1",
  "path_style": false,
  "concurrency": 8,
  "signing_private_key": ".modpack-updater/keys/release-private.pem",
  "trusted_public_key": ".modpack-updater/keys/release-public.pem"
}
```

`concurrency` 控制传输与部分本地处理的并发度，缺省为 8。更新的清单路径预检也受此值限制，同时最多使用 8 个 worker，且每个 worker 至少分配 128 条路径；小清单直接串行检查。设置为 1 可串行预检。所有预检 worker 都结束后，才会判断已是相同 release 或进入后续更新。

私有桶还需设置 `access_key`、`secret_key`；公开读取可省略。私钥和公钥路径相对工作区，也可以填写工作区外的绝对路径。位于工作区内的密钥必须在 `.modpack-updater/` 下，防止私钥进入发布内容或可信公钥被普通更新替换。

发布内容有两个可以同时使用的 base 输入目录，`init --tag client` 会创建它们：

- `client/base/`：直接放实例文件，保持相对路径与内容，不解压其中的压缩包。
- `client/base_pack/`：放 ZIP / 7z 压缩包，支持任意包名和多级子目录。发布时递归查找并解压，不需要安装外部解压命令；扩展名不区分大小写，其他普通文件会报错。

例如：

```text
client/
├── base/
│   └── other.txt
├── base_pack/
│   └── one_path/
│       └── test.zip    # 包内有 root.txt 和 second_path/second.txt
├── overlay/
└── remove.txt
```

合并后的发布路径为：

```text
other.txt
one_path/
├── root.txt
└── second_path/
    └── second.txt
```

映射规则是“压缩包在 `base_pack/` 下的相对父目录 + 包内路径”，不增加压缩包文件名这一层；直接放在 `base_pack/` 根目录的包会展开到实例根目录。不会自动去掉包内顶层目录，也不会继续展开包内的 jar、zip 等文件。两个输入目录可以缺少其中一个，也允许为空，但不能两者都不存在。旧的 `client/base.zip` / `client/base.7z` 输入不再支持；发布遇到旧位置的包会提示迁移，请将其移到 `client/base_pack/`。

**所有 base 输入先做冲突校验，再应用 remove 和 overlay。** `base/` 与解包结果、以及不同包之间，只要有同路径文件（即使内容相同），或文件占用了另一个来源所需的目录路径，就在访问 S3 前失败中断；`base/` 的实际空目录和包内空目录也参与文件/目录冲突检测，同名目录可以合并；`base_pack/` 的空容器目录本身不产生发布路径。不会选优先级、覆盖或跳过冲突文件。`client/overlay/` 仍用于额外新增与覆盖，`client/remove.txt` 排除合并后的 base 路径；它们不能用来掩盖 base 来源之间的冲突。

发布时会将压缩包展开到工作区内部的临时目录，直到上传结束；正常成功或失败时都会清理。因此需要容纳解压后内容的额外磁盘空间。加密、损坏或含有不安全路径、链接等不支持条目的压缩包会报错，不会发布部分内容。7z 只支持常规编码；若压缩包使用了 7-Zip 面向可执行文件的 BCJ2 过滤器（打包含 exe/dll 的目录时可能出现），当前版本无法解压，请改用 ZIP 或关闭该过滤器重新打包。压缩包内的文件名必须能用 UTF-8 表示（带 UTF-8 标志，或名字字节可严格按 UTF-8 解码，或带 Info-ZIP Unicode Path 字段且字段名可用 UTF-8 解码）；不带 Unicode Path 字段、名字字节又不能严格按 UTF-8 解码的旧式 ANSI/GBK 命名会被拒绝，避免发布乱码路径，请用 UTF-8 重新打包。所有包的临时解压结果会保留到本次发布结束，不改写原始 `base/` 或 `base_pack/`。

发布一个新版本：

```sh
java -jar modpack-updater.jar publish --tag client --id 1.0.0
```

每个 tag 的版本号不可重复使用。发布自动签署清单，依次上传块、块表、清单、签名，最后更新 `latest.json`。签名上传失败时 latest 保持旧版本；若清单已经写入，重试需使用新的版本号。

## 订阅者首次配置

在目标实例目录执行 `init`，配置同一个桶与前缀，并通过独立可信渠道安装发布者公钥到 `.modpack-updater/keys/release-public.pem`。**订阅者不运行 keygen，也不需要私钥。** 不要从更新服务器自动下载一个公钥并直接信任它。

```sh
java -jar modpack-updater.jar init
java -jar modpack-updater.jar update --tag client
```

所有命令可带 `--workspace <目录>`。配置了 `read_endpoint` 后，订阅者经该 CDN 基址匿名读取，地址直接追加 `prefix` 与对象键，不再插入 bucket；发布始终使用 `endpoint`。CDN 不应重定向请求、改写对象内容或覆盖源站缓存规则。

### 一次更新多个 tag

重复指定 `--tag`，即可在一次运行中依次更新多个 tag，各自读取自己的最新 release：

```sh
java -jar modpack-updater.jar update --tag common --tag client
```

按参数顺序更新，重复的 tag 只处理第一次。任一 tag 失败就立即停止，不再更新后续 tag，命令返回非零退出码；之前已经成功的 tag 不回滚。修复失败原因后可重新执行同一命令，已经处于目标 release 的 tag 会跳过。

这是顺序批量更新，不是跨 tag 事务或合并订阅。各 tag 管理的文件路径应互不冲突，包括一个 tag 的文件不能占用另一个 tag 所需的目录路径；不保证后面的 tag 始终覆盖前面的 tag，也不会协调跨 tag 删除。一次更新多个不同 tag 时不能使用 `--id`；需要固定版本时，仍分别使用单 tag 命令。

## 发布签名与信任边界

`publish` 使用 Java 内置 Ed25519，对清单压缩前的 UTF-8 原始字节签名。清单位于 `metadata/<tag>/releases/<id>.json`，原始 64 字节签名位于相邻的 `<id>.json.sig`。`update` 用本地公钥验签后才解析目标清单，并核对其 tag 与版本号；随后通过清单里的 SHA-256 校验块表和拼装文件。

缺少公钥、签名缺失、验签失败或身份不符都会终止当前 tag 的更新，不为该 tag 下载内容块、不移动其受追踪文件、不推进其版本指针。批量更新也会停止后续 tag，但保留此前已成功的更新。配置缺失的默认字段和运行日志仍可能写入。工具不提供跳过验签选项；S3 SigV4 是存储访问认证，不能代替发布签名。

升级已有部署时，先升级更新器并安装可信公钥，再以新版本号重新发布未签名内容。旧更新器不会因为桶里多了签名文件就获得验签保护。

可以显式选择一个仍由当前可信公钥签署的版本，包括回滚：

```sh
java -jar modpack-updater.jar update --tag client --id 1.0.0
```

签名证明发布授权与内容完整性，不证明模组本身安全，也不提供更新的新鲜度保证：`latest.json` 未签名，存储或 CDN 仍可隐藏新版本、重放合法旧版本或拒绝服务。本实现没有防回退序号。更换公钥需要操作者通过可信渠道手工部署；更换后旧密钥签署的历史版本不能再被接受。

**release 无权改写更新器或本地信任配置，即使签名有效也不例外，且没有关闭此保护的选项。** 整个 `.modpack-updater/` 命名空间（含配置、公钥、私钥与内部状态）不能出现在发布清单中。`update` 还根据实际加载的代码位置保护当前更新器，不依赖 JAR 文件名；重命名或放进子目录仍受保护，直接从 classpath 运行时则保护对应的 classes 目录。清单路径与更新器相同、是它的父路径或子路径，以及已有文件与其具有相同文件身份的硬链接，都会使当前 tag 失败。更新器升级和可信公钥更换必须由操作者通过独立可信渠道完成。

检查覆盖目标清单的所有条目和本地旧清单，包括内容未变的文件与即将删除的路径；拒绝发生在下载内容块、暂存和移动受追踪文件、推进版本指针之前。私钥配置路径即使尚不存在，也必须符合密钥位置规则，但订阅者仍不需要持有或加载私钥。日志和缺省配置字段仍可能由工具自身写入，先前成功的 tag 不会回滚。

访问时还拒绝根目录以下的符号链接、Windows junction 和路径别名。用户主动指定的工作区根可以是链接。这些检查不锁住另一个本地进程并发替换路径的行为，也不是脚本或模组的执行沙箱；正常发布的启动脚本仍可更新。

开发与验证命令见 [AGENTS.md](AGENTS.md)，测试规则见 [docs/testing.md](docs/testing.md)。
