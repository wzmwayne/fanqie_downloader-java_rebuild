# AGENTS.md — 番茄小说下载器 Java 版

## 项目概要

Java 21 零依赖番茄小说下载器原型，仅 4 个源文件，无测试、无 package 声明（默认包）。

- 入口：`src/main/java/Main.java`（主类 `Main`，~1200 行，含 HTTP、解码、EPUB 生成、图片下载、缓存）
- JSON 解析器：`src/main/java/JsonParser.java`（自实现递归下降解析器，sealed interface + record，JsonObject 有 `.str()` / `.integer()` / `.arr()` 等便捷方法）
- 构建：Gradle + `application` 插件（**无 Gradle Wrapper**，需系统安装 `gradle`）

## 命令

**README 中的 `-p javarebuild` 是错误的。** build.gradle 在项目根目录，直接运行：

```sh
# 编译（gradle jar 也会自动编译）
gradle build

# 搜索
gradle run --args="search 关键词"

# 查看书籍信息
gradle run --args="info <book_id>"
gradle run --args="info <book_id> --full"

# 下载（默认 EPUB）
gradle run --args="download <book_id>"
gradle run --args="download <book_id> -start=1 -end=10 --output=txt --run=10 --book-name=名称"

# 使用缓存（第二次运行不重新下载）
gradle run --args="download <book_id>"
# 强制重新下载
gradle run --args="download <book_id> --redownload=true"

# 打包 JAR
gradle jar
java -jar build/libs/fq_download.jar search 关键词

# 快捷构建（编译 + 打包）
build.bat
```

## 架构要点

- **默认包**：两个源文件均无 `package` 声明，直接 `import` 对方类
- **字符解码**：硬编码 372 字映射表 `CHARSET` + 基值 `0xE3E8`，番茄小说更新加密方案会导致乱码
- **EPUB**：EPUB 2.0，用 `ZipOutputStream` 手动打包（无第三方库）。封面从 `/page/{bookId}` HTML 的 `__INITIAL_STATE__.page.thumbUrl` 提取；含可见 TOC 页面（`toc.xhtml`）和 NCX 目录
- **并发**：`Executors.newFixedThreadPool`，默认组大小 10 章/组，`--run=N` 控制并发数（默认 5）
- **缓存**：下载内容缓存到 `cache/<book_id>/`（含 `meta.json`、`chapters/`、`images/`、`cover`）。重复运行直接使用缓存；`--redownload=true` 强制重新下载
- **输出目录**：`output/<book_id>/`（已在 `.gitignore` 中）。`.gitignore` 也包含 `cache/`

## 测试

**无测试目录、无测试文件。** 手动验证即可。

## 已知约束

- 依赖番茄小说公开 API（`novel.snssdk.com`、`fanqienovel.com`），接口变更即失效
- 无重试退避之外的错误恢复机制
- `JsonParser` 是手写解析器，不支持完整 JSON 规范（如科学计数法边界情况）
