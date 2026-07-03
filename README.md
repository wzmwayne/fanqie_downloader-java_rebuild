# 番茄小说下载器 Java 版

FQ 小说下载器 Java 实现，使用 unidbg 模拟签名、`batch_full/v1` 批量下载。

## 功能

- **搜索** — 按关键词搜索小说
- **信息查询** — 查看书籍详情和章节目录
- **下载**（正常模式） — 批量 API 并发下载，导出 EPUB（含封面）或 TXT，支持缓存断点续传
- **下载**（特殊模式） — 被封设备无限重试，适合风控/限流场景

## 构建

系统需安装 Gradle + JDK 21。

```sh
gradle build      # 编译
gradle jar        # 打包 JAR → build/libs/fq_download.jar
```

## 用法

### search / info

```sh
java -jar build/libs/fq_download.jar search 关键词
java -jar build/libs/fq_download.jar info <book_id>
java -jar build/libs/fq_download.jar info <book_id> --full
```

### download 通用选项

| 参数 | 说明 |
|------|------|
| `-start=N` / `--start=N` | 起始章节（从 1 开始） |
| `-end=N` / `--end=N` | 结束章节 |
| `--output=txt` | 输出 TXT（默认 EPUB） |
| `--run=N` | 并发线程数，默认 2 |
| `--book-name=名称` | 自定义书名 |
| `--redownload=true` | 强制重新下载（删除缓存） |
| `--proxy=host:port` | 手动指定 HTTP 代理 |
| `-debug` | 保存所有 HTTP 请求/响应到 `./log/` |

### 特殊模式（互斥，break-all > get-more > get-all）

| 参数 | 说明 |
|------|------|
| `----break-all` | 逐章无限重试，原文输出，追加 TXT |
| `----get-more=N` | 批量 N 章 + 批量重试 |
| `----get-all` | 一次性全部 + 批量重试 |

特殊模式均不缓存、不生成 EPUB、输出解密后的原文。

### 示例

```sh
# 正常下载 EPUB（并发 2，每组 50 章）
java -jar build/libs/fq_download.jar download 7238970192674425917

# 指定范围 + TXT
java -jar build/libs/fq_download.jar download 7238970192674425917 -start=10 -end=50 --output=txt

# 特殊模式：设备被封时使用
java -jar build/libs/fq_download.jar download 7238970192674425917 ----break-all
java -jar build/libs/fq_download.jar download 7238970192674425917 ----get-more=50
java -jar build/libs/fq_download.jar download 7238970192674425917 ----get-all
```

## 架构

- `Main.java` — 所有逻辑（HTTP、下载、EPUB、缓存、代理、签名集成）
- `IdleFQ.java` — unidbg 模拟 SO 签名（偏移 `0x168c80`）
- `FqCrypto.java` — AES 解密 + registerkey
- `JsonParser.java` — 自实现递归下降 JSON 解析器

### 签名流程

1. `IdleFQ` 加载 APK + SO 到临时文件，初始化 unidbg 模拟器
2. `generateSignature(url, headers)` 调用 SO 生成签名
3. `FqCrypto.newRegisterKeyContent()` → POST registerkey → 解密得 AES 密钥
4. 批量请求带签名，`FqCrypto.decryptAndDecompressContent()` 解密

### 正常下载流程

- `GROUP_SIZE=50` 章/组，每组装入一个 `batch_full/v1` 请求
- 默认 2 组并发（`--run=N` 控制）
- 每组先 3 次批量重试，失败后换设备再 5 次，最后单章回退
- 全部结束后对失败章节集中重试（换设备+代理，最多 30 轮）

## 输出

- 正常模式：`output/<book_id>/<book_id>_<start>-<end>.epub`（或 `.txt`）
- 特殊模式：`output/<book_id>/<book_id>_<start>-<end>.txt`
- 缓存：`cache/<book_id>/`（chapters、images、cover、meta.json）

## 依赖

- **unidbg** — SO 模拟执行
- **fastjson** — 部分场景
- **spring-core、commons-io/collections4/codec** — 工具类
- **slf4j + logback** — 日志

## 平台限制

- unidbg 需要 **Linux x86_64**（SO 是 arm64-v8a，模拟执行）
- 依赖番茄小说公开 API（接口变更即失效）
