#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────
# fq-downloader Debian 打包脚本
# 用法: 在项目根目录运行 tools/package-deb.sh
#       先执行 gradle jar 确保 build/libs/fq_download.jar 为最新
# ──────────────────────────────────────────────────────────
set -euo pipefail

NAME="fq-downloader"
VER="$(git describe --tags --abbrev=0 2>/dev/null || echo "1.2.0")"
ARCH="amd64"
PKG_DIR="build/${NAME}_${VER}_${ARCH}"
DEB_FILE="build/${NAME}_${VER}_${ARCH}.deb"

echo "==> 清理旧的构建目录"
rm -rf "$PKG_DIR"

echo "==> 创建目录结构"
mkdir -p "$PKG_DIR/DEBIAN"
mkdir -p "$PKG_DIR/usr/bin"
mkdir -p "$PKG_DIR/usr/share/${NAME}"
mkdir -p "$PKG_DIR/usr/share/doc/${NAME}"
mkdir -p "$PKG_DIR/usr/share/man/man1"

echo "==> 复制 JAR"
if [ ! -f "build/libs/fq_download.jar" ]; then
    echo "错误: build/libs/fq_download.jar 不存在，请先执行 gradle jar" >&2
    exit 1
fi
cp "build/libs/fq_download.jar" "$PKG_DIR/usr/share/${NAME}/"
chmod 644 "$PKG_DIR/usr/share/${NAME}/fq_download.jar"

echo "==> 创建启动脚本 /usr/bin/fq"
cat > "$PKG_DIR/usr/bin/fq" << 'LAUNCHER'
#!/bin/sh
# fq-downloader — 番茄小说下载器
# 用法: fq search <关键词>
#       fq download <book_id> [选项...]
exec java -jar /usr/share/fq-downloader/fq_download.jar "$@"
LAUNCHER
chmod 755 "$PKG_DIR/usr/bin/fq"

echo "==> 复制文档"
cp README.md "$PKG_DIR/usr/share/doc/${NAME}/"
chmod 644 "$PKG_DIR/usr/share/doc/${NAME}/README.md"

echo "==> 创建 man 手册"
cat > "$PKG_DIR/usr/share/man/man1/fq.1" << 'MANPAGE'
.TH FQ 1 "2026-07" "fq-downloader" "User Commands"
.SH 名称
fq \- 番茄小说下载器 — 搜索、批量下载、导出 EPUB/TXT
.SH 用法
.B fq
\fIcommand\fR [\fIoptions\fR]
.SH 命令
.TP
.B search \fI关键词\fR
按关键词搜索小说，返回 Book ID 用于下载
.TP
.B download \fIbook_id\fR [\fIoptions\fR]
下载指定小说
.SH download 选项
.TP
.BR -start=\fIN\fR ", " --start=\fIN\fR
起始章节（从 1 开始）
.TP
.BR -end=\fIN\fR ", " --end=\fIN\fR
结束章节，默认全部
.TP
.BR --output=txt
输出 TXT（默认 EPUB）
.TP
.BR --book-name=\fI名称\fR
自定义书名
.SH 特殊模式
.TP
.B ----break-all
逐章无限重试，Ctrl+C 退出
.TP
.B ----get-more=\fIN\fR
批量 N 章 + 失败重试
.TP
.B ----get-all
一次性全部 + 失败重试
.SH 示例
.PP
搜索小说：
.RS
fq search 凡人修仙传
.RE
.PP
下载全部章节为 EPUB：
.RS
fq download 7417103346953096217
.RE
.PP
指定范围输出 TXT：
.RS
fq download 7417103346953096217 -start=10 -end=50 --output=txt
.RE
.SH 依赖
JDK 21 或更高版本
.SH 项目
https://github.com/wzmwayne/fanqie_downloader-java_rebuild
MANPAGE
chmod 644 "$PKG_DIR/usr/share/man/man1/fq.1"

echo "==> 写入 DEBIAN/control"
cat > "$PKG_DIR/DEBIAN/control" << 'CONTROL'
Package: fq-downloader
Version: 1.2.0
Architecture: amd64
Section: utils
Priority: optional
Maintainer: https://github.com/wzmwayne/fanqie_downloader-java_rebuild
Depends: java-21-runtime
Homepage: https://github.com/wzmwayne/fanqie_downloader-java_rebuild
Description: 番茄小说下载器 — 搜索、批量下载、导出 EPUB/TXT
 使用 unidbg 模拟 FQ 小说 API 签名，实现搜索、批量并发下载、
 EPUB（含封面/目录/图片）和 TXT 导出的命令行工具。
 .
 功能:
  * search — 按关键词搜索小说
  * download — 批量并发下载，支持 50 章/组
  * EPUB 3.0 输出（含封面、目录、正文图片）
  * TXT 纯文本输出
  * 特殊模式（----break-all / ----get-more / ----get-all）
    用于设备被封或网络不稳定的场景，失败后无限重试
 .
 平台限制: 仅 Linux x86_64（unidbg 模拟 arm64 SO）
 依赖: JDK 21
CONTROL

echo "==> 写入 DEBIAN/postinst（更新 man 数据库）"
cat > "$PKG_DIR/DEBIAN/postinst" << 'POSTINST'
#!/bin/sh
set -e
if [ -x /usr/bin/mandb ]; then
    mandb -q
fi
# 提示 JDK 依赖
if ! command -v java >/dev/null 2>&1; then
    echo ""
    echo "  警告: 未检测到 Java 运行时。"
    echo "  请安装 JDK 21："
    echo "    Ubuntu: 参见 https://adoptium.net/ 或使用清华镜像"
    echo "    https://mirrors.tuna.tsinghua.edu.cn/Adoptium/"
    echo ""
fi
exit 0
POSTINST
chmod 755 "$PKG_DIR/DEBIAN/postinst"

echo "==> 写入 DEBIAN/prerm"
cat > "$PKG_DIR/DEBIAN/prerm" << 'PRERM'
#!/bin/sh
set -e
exit 0
PRERM
chmod 755 "$PKG_DIR/DEBIAN/prerm"

echo "==> 计算安装大小（KB）"
INSTALLED_SIZE="$(du -sk "$PKG_DIR/usr" | cut -f1)"
echo "Installed-Size: $INSTALLED_SIZE" >> "$PKG_DIR/DEBIAN/control"

echo "==> 生成校验和（md5sums）"
cd "$PKG_DIR"
find usr -type f -exec md5sum {} \; > DEBIAN/md5sums
chmod 644 DEBIAN/md5sums
cd - > /dev/null

echo "==> 构建 deb 包"
dpkg-deb --root-owner-group --build "$PKG_DIR" "$DEB_FILE"

echo ""
echo "=== 完成 ==="
echo "  deb: $DEB_FILE"
ls -lh "$DEB_FILE"

echo ""
echo "安装方法:"
echo "  sudo dpkg -i $DEB_FILE"
echo "  fq search 凡人"
echo "  fq download 7417103346953096217"
echo ""
echo "卸载方法:"
echo "  sudo dpkg -r fq-downloader"
