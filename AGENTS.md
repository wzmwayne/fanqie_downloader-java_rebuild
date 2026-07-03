# AGENTS.md — 番茄小说下载器 Java 版

## 构建

**无 Gradle Wrapper**，需系统安装 `gradle`。`build.gradle` 在项目根目录。

**README 中的 `-p javarebuild` 是错误的。** 勿用。直接在根目录运行：

```sh
gradle build                          # 编译
gradle jar                            # 打包 JAR → build/libs/fq_download.jar
gradle run --args="search 关键词"
gradle run --args="info <book_id> [--full]"
gradle run --args="download <book_id> [选项...]"
```

Windows 也可用 `build.bat`（仅 `gradle jar`）。

## 参数说明

### download 通用选项

| 参数 | 说明 |
|------|------|
| `-start=N` / `--start=N` | 起始章节（从1开始） |
| `-end=N` / `--end=N` | 结束章节 |
| `--output=txt` | 输出 TXT 格式（默认 EPUB） |
| `--run=N` | 并发线程数，默认 5 |
| `--book-name=名称` | 自定义书名，替代自动获取 |
| `--redownload=true` | 强制重新下载（删除本地缓存） |
| `--proxy=host:port` | 手动指定 HTTP 代理（如 `--proxy=127.0.0.1:1080`） |
| `-debug` | 输出调试日志，保存所有 HTTP 请求/响应到 `./log/<时间戳>_debug_running.log` |

### 特殊模式（三者互斥，按优先级: break-all > get-more > get-all）

| 参数 | 说明 |
|------|------|
| `----break-all` | **逐章无限重试模式**。放弃代理→逐章获取→失败无限重试→按 Ctrl+C 退出。原文输出，不缓存，追加 TXT。适合设备被封时持续重试。 |
| `----get-more=N` | **批量N章 + 批量重试**。先批量获取 N 章，其中失败的自动转为下一轮批量重试，直到全部成功。比 break-all 快但同样可靠。 |
| `----get-all` | **一次性全部 + 批量重试**。一次批量请求获取所有章节，失败的整批重试。适合章节少或网络好的情况。 |

**特殊模式的共同行为：**
- 不缓存（跳过 `cache/`）
- 不生成 EPUB，直接追加写入 `output/<bookId>/<bookId>_<start>-<end>.txt`
- 输出解密后的原文，不经过 `blkToP` / `extractBlkText` 处理
- 失败后无限重试，需手动 Ctrl+C 终止
- **不回退到单章**——用同样的批量 API 重试失败的章节

### 使用示例

```sh
# 正常下载（EPUB，并发5）
java -jar build/libs/fq_download.jar download 7238970192674425917

# 指定章节范围，输出TXT
java -jar build/libs/fq_download.jar download 7238970192674425917 -start=10 -end=50 --output=txt

# 指定代理
java -jar build/libs/fq_download.jar download 7238970192674425917 --proxy=180.166.128.182:12011

# Break-All模式（设备被封时使用）
java -jar build/libs/fq_download.jar download 7238970192674425917 ----break-all

# Get-More: 每次批量50章，失败自动回退单章重试
java -jar build/libs/fq_download.jar download 7238970192674425917 ----get-more=50

# Get-All: 一次性请求所有章节
java -jar build/libs/fq_download.jar download 7238970192674425917 ----get-all

# 调试模式（保存所有HTTP原始数据）
java -jar build/libs/fq_download.jar download 7238970192674425917 ----break-all -debug
```

## 架构速览

`Main.java`（2218 行）= 一切：HTTP、EPUB 生成、并发下载、缓存、FQ 签名 API 集成。额外 5 个源文件（`JsonParser` 自实现 JSON 解析、`IdleFQ` unidbg SO 模拟、`FqCrypto` AES 加解密、`FqVariable` DTO、`TempFileUtils` 资源提取）。

### FQ 签名流程

1. `IdleFQ` 构造时提取 `src/main/resources/com/dragon/read/oversea/gp/` 下的 APK + SO 到临时文件，初始化 unidbg 模拟器
2. `generateSignature(url, headers)` 调用 SO 偏移 `0x168c80` 生成签名
3. `FqCrypto.newRegisterKeyContent()` → POST `/reading/crypt/registerkey` → 解密得 AES 密钥
4. 批量章节请求带签名，`FqCrypto.decryptAndDecompressContent()` 解密（AES/CBC + 可选 GZIP）

### 资源文件路径

```
src/main/resources/com/dragon/read/oversea/gp/
├── apk/番茄小说_6.8.1.32.apk    # unidbg 加载
├── lib/libmetasec_ml.so         # 签名核心
├── lib/libc++_shared.so
└── other/ms_16777218.bin        # 证书
```

**注意：** 根目录 `apk/` 是独立的解包分析目录（含 apktool 和反编译结果），不是资源 APK。

### 设备管理

`deviceId`/`installId`/`deviceCdid` 硬编码在 `Main.java:53-55`。当前设备（`933935730452521`）已触发风控。`reinitDevice()` 调用 `deviceRegister()` → POST `log5-applog.fqnovel.com`，然后销毁旧 `IdleFQ` 重建。

**`RESEARCH.md` 记录了设备注册机制的研究进展** — 修改设备注册逻辑前必读。

### 并发与缓存

- `Executors.newFixedThreadPool`，默认 10 章/组，`--run=N` 控制并发（默认 5）
- 缓存：`cache/<book_id>/`（meta.json、chapters/、images/、cover），`--redownload=true` 跳过
- 输出：`output/<book_id>/`（均在 `.gitignore` 中）

## 关键依赖

- **unidbg** (`unidbg-android/api/unicorn2/dynarmic`) — SO 模拟执行
- **apk-parser、jna** — APK 解析 + 原生调用
- **fastjson** — 仅部分场景，核心解析用自实现 `JsonParser`
- **spring-core、commons-io/collections4/codec** — 工具类
- **slf4j + logback** — 日志

## 平台限制

- unidbg 需要 **Linux x86_64**（SO 是 arm64-v8a，模拟执行）
- 依赖番茄小说公开 API（接口变更即失效）
- **无测试。** 手动验证。
