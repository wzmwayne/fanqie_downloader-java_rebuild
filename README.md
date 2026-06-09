# Tomato Novel Downloader — Java Prototype

纯 Java 21 实现的番茄小说下载器原型，零第三方依赖。

## 功能

- **搜索** — 按关键词搜索小说
- **信息查询** — 查看书籍详情和章节目录
- **下载** — 下载章节导出为 TXT 或 EPUB
- **多线程** — 并发下载，可配置线程数
- **EPUB 封面** — 自动拉取封面图片嵌入 EPUB

## 用法

```sh
# 搜索小说
gradle -p javarebuild run --args="search 凡人修仙传"

# 查看书籍信息 + 章节目录
gradle -p javarebuild run --args="info 7381636680049658120"
gradle -p javarebuild run --args="info 7381636680049658120 --full"

# 下载（默认 EPUB）
gradle -p javarebuild run --args="download 7381636680049658120"
gradle -p javarebuild run --args="download 7381636680049658120 -start=1 -end=10"

# 指定输出格式为 TXT
gradle -p javarebuild run --args="download 7381636680049658120 -start=1 -end=5 --output=txt"

# 控制并发线程数（默认 5）
gradle -p javarebuild run --args="download 7381636680049658120 --run=10"

# 手动指定书名（自动获取失败时）
gradle -p javarebuild run --args="download 7381636680049658120 --book-name=凡人修仙传"
```

## 构建

```sh
# 编译
gradle -p javarebuild build

# 打包 JAR
gradle -p javarebuild jar
java -jar javarebuild/build/libs/tomato-search.jar search 凡人
```

## 输出

文件保存在 `output/<book_id>/` 目录下，命名格式为 `<book_id>_<start>-<end>.epub`（或 `.txt`）。

## 注意事项

- 零外部依赖，仅使用 Java 21 标准库
- 字符解码使用硬编码的替换表（372 个字符），若网站更新加密方案会导致乱码
- EPUB 使用 EPUB 2.0 格式
