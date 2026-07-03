# 番茄小说下载器 Java 版

使用 unidbg 模拟 FQ 小说 API 签名，实现搜索、批量并发下载、EPUB/TXT 导出。

## 快速上手

### 1. 安装 Java（JDK 21）

```sh
# 清华镜像站下载 JDK 21（Linux x64）
wget https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.6_7.tar.gz
tar -xzf OpenJDK21U-jdk_x64_linux_hotspot_21.0.6_7.tar.gz
export PATH="$PWD/jdk-21.0.6+7/bin:$PATH"
java -version
```

Windows/Mac 用户从 [清华镜像站](https://mirrors.tuna.tsinghua.edu.cn/Adoptium/) 下载对应安装包。

### 2. 下载 JAR

```sh
# 使用 akams 加速下载最新 Release
wget https://github.akams.cn/wzmwayne/fanqie_downloader-java_rebuild/releases/latest/download/fq_download.jar
```

### 3. 搜索小说

```sh
java -jar fq_download.jar search 凡人修仙传
```

### 4. 下载小说

```sh
# 下载为 EPUB（含封面、目录）
java -jar fq_download.jar download <book_id>

# 下载为 TXT
java -jar fq_download.jar download <book_id> --output=txt

# 指定章节范围
java -jar fq_download.jar download <book_id> -start=10 -end=50
```

输出文件在 `output/<book_id>/` 目录。

---

## 详细使用教程

### 搜索 `search`

```sh
java -jar fq_download.jar search 关键词
```

返回：Book ID、书名、作者、评分。找到目标后记录 Book ID 用于下载。

### 下载 `download`

基本下载：
```sh
java -jar fq_download.jar download 7417103346953096217
```

| 选项 | 说明 |
|------|------|
| `-start=N` / `--start=N` | 起始章节，从 1 开始 |
| `-end=N` / `--end=N` | 结束章节，默认全部 |
| `--output=txt` | 输出 TXT（默认 EPUB） |
| `--book-name=名称` | 自定义书名 |

示例：
```sh
# 下载全部章节为 EPUB
java -jar fq_download.jar download 7417103346953096217

# 下载第 10-50 章为 TXT
java -jar fq_download.jar download 7417103346953096217 -start=10 -end=50 --output=txt

# 下载第 1-100 章并自定义书名
java -jar fq_download.jar download 7417103346953096217 -end=100 --book-name=凡人
```

### 特殊模式（设备被封/网络不稳定使用）

三者互斥，优先级 `----break-all` > `----get-more` > `----get-all`。

| 模式 | 说明 |
|------|------|
| `----break-all` | 逐章无限重试，原文追加 TXT，Ctrl+C 退出 |
| `----get-more=N` | 批量 N 章 + 失败重试（推荐，默认 50） |
| `----get-all` | 一次性全部 + 失败重试 |

```sh
# 设备被封时使用
java -jar fq_download.jar download 7417103346953096217 ----break-all

# 每次批量 50 章，失败自动重试
java -jar fq_download.jar download 7417103346953096217 ----get-more=50

# 一次性获取全部章节
java -jar fq_download.jar download 7417103346953096217 ----get-all
```

特殊模式共同行为：
- 不缓存、不生成 EPUB
- 直接追加写入 `output/<bookId>/<bookId>_<start>-<end>.txt`
- 失败后无限重试，需 Ctrl+C 终止
- 输出解密后原文（不过滤 HTML 标签）

---

## 编译

系统需安装 **JDK 21** + **Gradle**。

```sh
# 编译并打包
gradle build -x test
gradle jar

# JAR 位置
ls -lh build/libs/fq_download.jar

# 或直接运行
gradle run --args="search 凡人"
gradle run --args="download 7417103346953096217 -start=1 -end=5"
```

Windows 可用 `build.bat`（仅 `gradle jar`）。

---

## 技术实现

### FQ 签名流程

1. `IdleFQ` — 加载 APK 和 `libmetasec_ml.so`，初始化 unidbg 模拟器
2. `generateSignature(url, headers)` — 调用 SO 偏移 `0x168c80` 生成请求签名
3. `FqCrypto.registerKey()` — POST `/reading/crypt/registerkey` → 获取 AES 密钥
4. `decryptAndDecompressContent()` — AES/CBC 解密 + GZIP 解压

### 下载流程

- 50 章为一组，批量请求 `batch_full/v1`
- 2 组并发（串行分组的并发控制）
- 每组失败后自动重试，最终集中重试失败的章节
- 正常模式 EPUB（含封面、目录、CSS）、TXT 模式用 `extractBlkText` 格式化

### 项目结构

```
src/main/java/
├── Main.java             # 入口 + 全部业务逻辑（搜索、下载、EPUB）
├── JsonParser.java       # 自实现递归下降 JSON 解析器
├── JsonValue.java        # JSON 值类型
└── com/anjia/unidbgserver/
    ├── IdleFQ.java       # unidbg SO 签名模拟
    ├── FqCrypto.java     # AES 加解密
    ├── FqVariable.java   # DTO
    └── TempFileUtils.java # 资源文件提取

src/main/resources/com/dragon/read/oversea/gp/
├── apk/                  # 番茄小说 APK（unidbg 加载）
├── lib/                  # SO 签名库
└── other/                # 证书
```

### 核心依赖

| 库 | 用途 |
|----|------|
| unidbg-android | SO 模拟执行（arm64 → x86） |
| fastjson | 部分 JSON 解析 |
| spring-core, commons-io/codec | 工具类 |
| slf4j + logback | 日志 |

### 平台限制

- **仅 Linux x86_64** — unidbg 模拟 arm64 SO
- 依赖番茄小说公开 API，接口变更即失效
- 当前设备已触发风控，但 `batch_full/v1` 批量接口仍可用（部分章节返回空内容）

---

## 项目发展

### 分支说明

| 分支 | 说明 |
|------|------|
| `main` | 核心搜索 + 批量下载 + EPUB 导出 |

### Roadmap

- [ ] 多设备轮换（自动注册新设备绕过风控）
- [ ] `info` 命令（查看书籍详情 + 目录）
- [ ] 代理池自动管理与测速
- [ ] 批量下载稳定性优化（更细粒度的失败重试策略）

### 贡献

Issue / PR 请提交至 [GitHub](https://github.com/wzmwayne/fanqie_downloader-java_rebuild)。

---

## License

MIT
