# modpack-updater

Java 17 命令行工具，把 Minecraft 整合包的受追踪文件发布到 S3 兼容对象存储，再按版本差异更新已有实例。文件按内容分块，更新只下载本地没有的块。被替换或删除的文件移入备份目录。

## 发布者首次配置

构建可运行 jar：

```sh
./gradlew shadowJar
```

在发布工作区执行：

```sh
java -jar modpack-updater-0.1.0.jar init --tag client
java -jar modpack-updater-0.1.0.jar keygen
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

私有桶还需设置 `access_key`、`secret_key`；公开读取可省略。私钥和公钥路径相对工作区，也可以填写工作区外的绝对路径。位于工作区内的密钥必须在 `.modpack-updater/` 下，防止私钥进入发布内容或可信公钥被普通更新替换。

把实例文件放进 `client/base/`，或者直接把下载的 ZIP / 7z 压缩包放成 `client/base.zip` / `client/base.7z`，无需手工解压，也不需要安装外部解压命令。额外修改仍放进 `client/overlay/`；`client/remove.txt` 用于排除 base 中的路径。

例如，使用压缩包时的布局为：

```text
client/
├── base.zip        # 或 base.7z，二选一
├── overlay/
└── remove.txt
```

压缩包内的路径直接作为实例相对路径：不会自动去掉顶层目录，也不会继续展开里面的 jar、zip 等文件。overlay 的同路径文件仍覆盖 base，remove 仍只排除 base 中的文件。一个 tag 只能选择一个 base 来源：两个压缩包并存，或压缩包与非空 `base/` 并存都会报错；`init` 创建的空 `base/` 可以保留。

发布时会将压缩包展开到工作区内部的临时目录，直到上传结束；正常成功或失败时都会清理。因此需要容纳解压后内容的额外磁盘空间。加密、损坏或含有不安全路径、链接等不支持条目的压缩包会报错，不会发布部分内容。7z 只支持常规编码；若压缩包使用了 7-Zip 面向可执行文件的 BCJ2 过滤器（打包含 exe/dll 的目录时可能出现），当前版本无法解压，请改用 ZIP 或关闭该过滤器重新打包。压缩包内的文件名必须能用 UTF-8 表示（带 UTF-8 标志，或名字字节可严格按 UTF-8 解码，或带 Info-ZIP Unicode Path 字段且字段名可用 UTF-8 解码）；不带 Unicode Path 字段、名字字节又不能严格按 UTF-8 解码的旧式 ANSI/GBK 命名会被拒绝，避免发布乱码路径，请用 UTF-8 重新打包。压缩包文件名区分大小写，只认 `base.zip` 与 `base.7z`，大小写不符的文件会报错而不是被静默忽略。

发布一个新版本：

```sh
java -jar modpack-updater-0.1.0.jar publish --tag client --id 1.0.0
```

每个 tag 的版本号不可重复使用。发布自动签署清单，依次上传块、块表、清单、签名，最后更新 `latest.json`。签名上传失败时 latest 保持旧版本；若清单已经写入，重试需使用新的版本号。

## 订阅者首次配置

在目标实例目录执行 `init`，配置同一个桶与前缀，并通过独立可信渠道安装发布者公钥到 `.modpack-updater/keys/release-public.pem`。**订阅者不运行 keygen，也不需要私钥。** 不要从更新服务器自动下载一个公钥并直接信任它。

```sh
java -jar modpack-updater-0.1.0.jar init
java -jar modpack-updater-0.1.0.jar update --tag client
```

所有命令可带 `--workspace <目录>`。配置了 `read_endpoint` 后，订阅者经该 CDN 基址匿名读取，地址直接追加 `prefix` 与对象键，不再插入 bucket；发布始终使用 `endpoint`。CDN 不应重定向请求、改写对象内容或覆盖源站缓存规则。

### 一次更新多个 tag

重复指定 `--tag`，即可在一次运行中依次更新多个 tag，各自读取自己的最新 release：

```sh
java -jar modpack-updater-0.1.0.jar update --tag common --tag client
```

按参数顺序更新，重复的 tag 只处理第一次。任一 tag 失败就立即停止，不再更新后续 tag，命令返回非零退出码；之前已经成功的 tag 不回滚。修复失败原因后可重新执行同一命令，已经处于目标 release 的 tag 会跳过。

这是顺序批量更新，不是跨 tag 事务或合并订阅。各 tag 管理的文件路径应互不冲突，包括一个 tag 的文件不能占用另一个 tag 所需的目录路径；不保证后面的 tag 始终覆盖前面的 tag，也不会协调跨 tag 删除。一次更新多个不同 tag 时不能使用 `--id`；需要固定版本时，仍分别使用单 tag 命令。

## 发布签名与信任边界

`publish` 使用 Java 内置 Ed25519，对清单压缩前的 UTF-8 原始字节签名。清单位于 `metadata/<tag>/releases/<id>.json`，原始 64 字节签名位于相邻的 `<id>.json.sig`。`update` 用本地公钥验签后才解析目标清单，并核对其 tag 与版本号；随后通过清单里的 SHA-256 校验块表和拼装文件。

缺少公钥、签名缺失、验签失败或身份不符都会终止当前 tag 的更新，不为该 tag 下载内容块、不移动其受追踪文件、不推进其版本指针。批量更新也会停止后续 tag，但保留此前已成功的更新。配置缺失的默认字段和运行日志仍可能写入。工具不提供跳过验签选项；S3 SigV4 是存储访问认证，不能代替发布签名。

升级已有部署时，先升级更新器并安装可信公钥，再以新版本号重新发布未签名内容。旧更新器不会因为桶里多了签名文件就获得验签保护。

可以显式选择一个仍由当前可信公钥签署的版本，包括回滚：

```sh
java -jar modpack-updater-0.1.0.jar update --tag client --id 1.0.0
```

签名证明发布授权与内容完整性，不证明模组本身安全，也不提供更新的新鲜度保证：`latest.json` 未签名，存储或 CDN 仍可隐藏新版本、重放合法旧版本或拒绝服务。本实现没有防回退序号。更换公钥需要操作者通过可信渠道手工部署；更换后旧密钥签署的历史版本不能再被接受。

工作区内部配置和状态不能出现在发布清单中；访问时还拒绝根目录以下的符号链接、Windows junction 和路径别名。用户主动指定的工作区根可以是链接。这些检查不锁住另一个本地进程并发替换路径的行为。

开发与验证命令见 [AGENTS.md](AGENTS.md)，测试规则见 [docs/testing.md](docs/testing.md)。
