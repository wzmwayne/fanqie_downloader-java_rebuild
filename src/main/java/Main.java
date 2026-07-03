import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.net.ssl.*;

import com.anjia.unidbgserver.unidbg.IdleFQ;
import com.anjia.unidbgserver.service.FqCrypto;
import com.anjia.unidbgserver.dto.FqVariable;

public class Main {

    static final String SEARCH_URL = "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/";
    static final String DIRECTORY_URL = "https://fanqienovel.com/api/reader/directory/detail";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // FQ signed API
    static final String FQ_BASE_URL = "https://api5-normal-sinfonlineb.fqnovel.com";
    static final String FQ_UA_BASE = "com.dragon.read.oversea.gp/68132 (Linux; U; Android 10; zh_CN; OnePlus11; Build/V291IR;tt-ok/3.12.13.4-tiktok)";
    static String FQ_UA = FQ_UA_BASE;
    static String FQ_COOKIE = "store-region=cn-zj; store-region-src=did; install_id=933935730456617";
    static IdleFQ idleFQ;
    static String decryptKey;

    static String deviceId = "933935730452521";
    static String installId = "933935730456617";
    static String deviceCdid = "17f05006-423a-4172-be4b-7d26a42f2f4a";
    static boolean verbose, debug;
    static Proxy httpProxy;
    static final String PROXY_API = "https://proxy.scdn.io/api/get_proxy.php";
    static final Set<String> usedProxies = new HashSet<>();

    static final SSLSocketFactory TRUST_ALL_SF;
    static final HostnameVerifier TRUST_ALL_HV;
    static {
        try {
            var ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }}, null);
            TRUST_ALL_SF = ctx.getSocketFactory();
            TRUST_ALL_HV = (h, s) -> true;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static final int GROUP_SIZE = 50;
    static final int MAX_RETRIES = 3;
    static final Pattern IMG_PATTERN = Pattern.compile(
        "<img[^>]*\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>", Pattern.CASE_INSENSITIVE
    );

    static final Path CACHE_DIR = Path.of("cache");

    record Chapter(String itemId, String title, int order) {}
    record BookInfo(String bookId, String title, String author, String category, String score, String desc) {}
    record ImageInfo(byte[] data, String mime, String filename) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法:");
            System.err.println("  search <关键词>");
            System.err.println("  info <book_id> [--full]");
            System.err.println("  download <book_id> [选项...]");
            System.err.println("");
            System.err.println("=== download 选项 ===");
            System.err.println("  -start=N, --start=N       起始章节（从1开始）");
            System.err.println("  -end=N, --end=N           结束章节");
            System.err.println("  --output=txt              输出TXT（默认EPUB）");
            System.err.println("  --run=N                   并发线程数（默认5）");
            System.err.println("  --book-name=名称          自定义书名");
            System.err.println("  --redownload=true|false   强制重新下载（删除缓存）");
            System.err.println("  --proxy=host:port         手动指定HTTP代理");
            System.err.println("  -debug                    输出调试日志到 ./log/");
            System.err.println("");
            System.err.println("  ----break-all             放弃代理，逐章获取，无限重试直到Ctrl+C");
            System.err.println("                            原文输出，不缓存，追加写入TXT");
            System.err.println("  ----get-more=N            批量N章获取，失败逐章回退重试");
            System.err.println("                            适合折中方案（比break-all快）");
            System.err.println("  ----get-all               一次性批量获取所有章节，失败逐章回退");
            System.err.println("                            适合章节少或网络好时使用");
            System.err.println("");
            System.err.println("说明: ----get-more 和 ----get-all 优先使用批量接口，失败章节自动转为单章重试");
            System.err.println("      三者互斥，同时指定时优先级: ----break-all > ----get-more > ----get-all");
            System.err.println("      三者均不缓存、不生成EPUB、直接追加写入TXT");
            System.exit(1);
        }

        var cmd = args[0];
        var rest = new ArrayList<>(List.of(args));
        rest.removeFirst();

        switch (cmd) {
            case "search" -> handleSearch(String.join(" ", rest));
            case "info" -> handleInfo(rest);
            case "download" -> handleDownload(rest);
            default -> {
                System.err.println("未知命令: " + cmd);
                System.exit(1);
            }
        }
    }

    // ── Search ──────────────────────────────────────────

    static void handleSearch(String keyword) throws Exception {
        if (keyword.isBlank()) {
            System.err.println("关键词不能为空");
            System.exit(1);
        }
        String url = SEARCH_URL + "?q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&aid=1967";
        var json = fetchJson(url);
        var dataObj = json instanceof JsonValue.JsonObject jo ? jo.map().get("data") : null;
        var retData = dataObj != null ? optArray(dataObj, "ret_data") : new JsonValue.JsonArray(new ArrayList<>());

        var results = retData.list().stream()
                .map(v -> new BookInfo(
                        optStr(v, "book_id"),
                        optStr(v, "title"),
                        optStr(v, "author"),
                        optStr(v, "category"),
                        optStr(v, "score", "N/A"),
                        optStr(v, "abstract")))
                .toList();

        if (results.isEmpty()) {
            System.out.println("未找到相关书籍。");
            return;
        }
        System.out.println("共找到 " + results.size() + " 本小说：\n");
        System.out.printf("%-22s  %-20s  %-16s  %s%n", "Book ID", "书名", "作者", "评分");
        System.out.println("-".repeat(80));
        for (var r : results) {
            System.out.printf("%-22s  %-20s  %-16s  %s%n",
                    r.bookId(), truncate(r.title(), 20), truncate(r.author(), 16), r.score());
        }
    }

    // ── Info ────────────────────────────────────────────

    static void handleInfo(List<String> args) throws Exception {
        if (args.isEmpty()) {
            System.err.println("用法: info <book_id> [--full]");
            System.exit(1);
        }
        var bookId = args.getFirst();
        boolean showFull = args.contains("--full");

        // ── Page info (rich metadata from HTML) ──
        String bookTitle = bookId;
        try {
            var state = parsePageState(bookId);
            if (state instanceof JsonValue.JsonObject so) {
                var p = so.get("page");
                if (p instanceof JsonValue.JsonObject page) {
                    var title = page.str("bookName");
                    if (title != null && !title.isBlank()) bookTitle = title;
                    System.out.println("书名: " + bookTitle);
                    System.out.println("Book ID: " + bookId);

                    var author = page.str("authorName");
                    if (author == null || author.isBlank()) author = page.str("author");
                    if (author != null && !author.isBlank()) System.out.println("作者: " + author);

                    int status = page.integer("status", 0);
                    System.out.println("状态: " + (status == 1 ? "连载中" : status == 2 ? "已完结" : "未知"));

                    int wc = page.integer("wordNumber", 0);
                    if (wc > 0) System.out.println("字数: " + String.format("%.1f", wc / 10000.0) + "万");

                    int rc = page.integer("readCount", 0);
                    if (rc > 0) System.out.println("在读: " + rc);

                    var catV2 = page.str("categoryV2");
                    if (catV2 != null && !catV2.isBlank()) {
                        try {
                            var catArr = new JsonParser(catV2).parse();
                            if (catArr instanceof JsonValue.JsonArray arr) {
                                var cats = new ArrayList<String>();
                                for (var v : arr.list()) {
                                    if (v instanceof JsonValue.JsonObject o) {
                                        var name = o.str("Name");
                                        if (name != null && !name.isBlank()) cats.add(name);
                                    }
                                }
                                if (!cats.isEmpty()) System.out.println("分类: " + String.join(" / ", cats));
                            }
                        } catch (Exception ignored) {}
                    }

                    var thumb = page.str("thumbUrl");
                    if (thumb != null && !thumb.isBlank()) System.out.println("封面: " + thumb);

                    var lastCh = page.str("lastChapterTitle");
                    if (lastCh != null && !lastCh.isBlank()) System.out.println("最近章节: " + lastCh);

                    var desc = page.str("description");
                    if (desc != null && !desc.isBlank()) System.out.println("标语: " + desc);

                    if (showFull) {
                        var abs = page.str("abstract");
                        if (abs != null && !abs.isBlank()) System.out.println("简介:\n  " + abs.replace("\n", "\n  "));
                    }
                }
            }
        } catch (Exception ignored) {
            // fallback: try search API
            var sUrl = SEARCH_URL + "?q=" + URLEncoder.encode(bookId, StandardCharsets.UTF_8) + "&aid=1967";
            try {
                var sJson = fetchJson(sUrl);
                var sData = sJson instanceof JsonValue.JsonObject jo ? jo.map().get("data") : null;
                var sRet = sData != null ? optArray(sData, "ret_data") : new JsonValue.JsonArray(new ArrayList<>());
                var match = sRet.list().stream()
                        .filter(v -> optStr(v, "book_id").equals(bookId))
                        .findFirst();
                if (match.isPresent()) {
                    var b = match.get();
                    System.out.println("书名: " + optStr(b, "title"));
                    System.out.println("作者: " + optStr(b, "author"));
                    System.out.println("分类: " + optStr(b, "category"));
                    System.out.println("评分: " + optStr(b, "score", "N/A"));
                    var desc = optStr(b, "abstract");
                    if (!desc.isBlank()) System.out.println("简介:\n  " + desc.replace("\n", "\n  "));
                } else {
                    System.out.println("Book ID: " + bookId);
                }
            } catch (Exception e2) {
                System.out.println("Book ID: " + bookId);
            }
        }

        // ── Directory ──
        try {
            var chapters = fetchChaptersWithFallback(bookId);
            System.out.println("总章数: " + chapters.size());

            if (!chapters.isEmpty()) {
                String header = showFull ? "\n章节目录（共" + chapters.size() + "章）:" : "\n章节目录（前30章）:";
                System.out.println(header);
                System.out.println("-".repeat(60));
                var display = (!showFull && chapters.size() > 30) ? chapters.subList(0, 30) : chapters;
                for (var c : display) {
                    System.out.printf("  %-4d  %s%n", c.order(), c.title());
                }
                if (!showFull && chapters.size() > 30) {
                    System.out.println("  ... 共 " + chapters.size() + " 章，使用 --full 查看完整目录");
                }
            }
        } catch (Exception e) {
            System.err.println("获取目录失败: " + e.getMessage());
        }
    }

    static List<Chapter> extractChapters(JsonValue data) {
        var list = new ArrayList<Chapter>();
        if (data == null) return list;
        // Try simple keys first (parent project approach)
        if (list.isEmpty()) {
            for (var key : new String[]{"chapterList", "chapter_list", "chapters", "items", "list", "item_list"}) {
                var arr = optArray(data, key);
                if (!arr.list().isEmpty()) {
                    int idx = 1;
                    for (var ch : arr.list()) {
                        var itemId = optStr(ch, "itemId");
                        if (itemId.isBlank()) itemId = optStr(ch, "item_id");
                        if (!itemId.isBlank()) list.add(new Chapter(itemId, optStr(ch, "title"), idx++));
                    }
                    break;
                }
            }
        }
        // Try chapterListWithVolume (array of arrays for volumes)
        if (list.isEmpty()) {
            var cv = data instanceof JsonValue.JsonObject jo ? jo.map().get("chapterListWithVolume") : null;
            if (cv instanceof JsonValue.JsonArray cva) {
                for (var vol : cva.list()) {
                    if (vol instanceof JsonValue.JsonArray va) {
                        for (var ch : va.list()) {
                            var itemId = optStr(ch, "itemId");
                            var title = optStr(ch, "title");
                            var orderStr = optStr(ch, "realChapterOrder");
                            int order = 0;
                            try { order = Integer.parseInt(orderStr); } catch (Exception ignored) {}
                            if (!itemId.isBlank()) list.add(new Chapter(itemId, title, order));
                        }
                    }
                }
            }
        }
        // Try nested data.data.* (some APIs double-wrap)
        if (list.isEmpty()) {
            var inner = data instanceof JsonValue.JsonObject jo ? jo.get("data") : null;
            if (inner instanceof JsonValue.JsonObject) {
                for (var key : new String[]{"list", "chapterList", "chapter_list", "items", "item_list", "chapters"}) {
                    var arr = optArray(inner, key);
                    if (!arr.list().isEmpty()) {
                        int idx = 1;
                        for (var ch : arr.list()) {
                            var itemId = optStr(ch, "itemId");
                            if (itemId.isBlank()) itemId = optStr(ch, "item_id");
                            if (!itemId.isBlank()) list.add(new Chapter(itemId, optStr(ch, "title"), idx++));
                        }
                        break;
                    }
                }
            }
        }
        return list;
    }

    static List<Chapter> fetchChaptersWithFallback(String bookId) {
        // Try directory API with retry
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (attempt > 0) Thread.sleep(1000L * attempt);
                var dJson = fetchJson(DIRECTORY_URL + "?bookId=" + bookId);
                var dData = dJson instanceof JsonValue.JsonObject djo ? djo.map().get("data") : null;
                var chapters = extractChapters(dData);
                if (!chapters.isEmpty()) return chapters;
            } catch (Exception ignored) {}
        }
        // Fallback: extract chapters from page HTML __INITIAL_STATE__
        try {
            var state = parsePageState(bookId);
            if (state instanceof JsonValue.JsonObject so) {
                var page = so.get("page");
                if (page != null) {
                    var chapters = extractChapters(page);
                    if (!chapters.isEmpty()) return chapters;
                }
            }
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    // ── Download ────────────────────────────────────────

    static void handleDownload(List<String> args) throws Exception {
        if (args.isEmpty()) {
            System.err.println("用法: download <book_id> [-start=N] [-end=N] [--output=txt] [--run=N] [--redownload=true|false] [--proxy=host:port]");
            System.exit(1);
        }
        var bookId = args.getFirst();
        int start = 1, end = 0, batchSize = 2;
        boolean outputTxt = false;
        String cliBookName = null;

        boolean redownload = false, breakAll = false;
        int getMore = 0;
        boolean getAll = false;
        for (int i = 1; i < args.size(); i++) {
            var a = args.get(i);
            if (a.startsWith("--start=") || a.startsWith("-start=")) {
                start = Integer.parseInt(a.substring(a.indexOf("start=") + 6));
            } else if (a.startsWith("--end=") || a.startsWith("-end=")) {
                end = Integer.parseInt(a.substring(a.indexOf("end=") + 4));
            } else if (a.equals("--output=txt")) outputTxt = true;
            else if (a.startsWith("--run=")) {
                batchSize = Integer.parseInt(a.substring(6));
                if (batchSize < 1) batchSize = 1;
            }
            else if (a.startsWith("--book-name=")) cliBookName = a.substring(12);
            else if (a.startsWith("--redownload=")) {
                redownload = a.substring(13).equals("true");
            }
            else if (a.startsWith("--proxy=")) {
                var p = a.substring(8).split(":");
                httpProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(p[0], Integer.parseInt(p[1])));
            }
            else if (a.equals("-debug")) { debug = true; verbose = true; }
            else if (a.equals("----break-all")) breakAll = true;
            else if (a.startsWith("----get-more=")) getMore = Integer.parseInt(a.substring(13));
            else if (a.equals("----get-all")) getAll = true;
        }

        if (debug) {
            var logDir = Path.of("log");
            Files.createDirectories(logDir);
            var ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            var logFile = logDir.resolve(ts + "_debug_running.log");
            var fos = new java.io.FileOutputStream(logFile.toFile());
            var origOut = System.out;
            var tee = new OutputStream() {
                public void write(int b) throws IOException { origOut.write(b); fos.write(b); }
                public void write(byte[] b, int off, int len) throws IOException { origOut.write(b, off, len); fos.write(b, off, len); }
                public void flush() throws IOException { origOut.flush(); fos.flush(); }
                public void close() throws IOException { fos.close(); }
            };
            System.setOut(new PrintStream(tee, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(tee, true, StandardCharsets.UTF_8));
            System.out.println("Debug日志: " + logFile.toAbsolutePath());
        }

        var cacheDir = CACHE_DIR.resolve(bookId);
        var outDir = Path.of("output", bookId);
        Files.createDirectories(outDir);

        // ----- Redownload -----
        if (redownload) {
            System.out.println("重新下载（--redownload=true），删除缓存...");
            deleteDirectory(cacheDir);
        }

        // ── 1. Fetch book name + cover first (before directory) ──
        System.out.println("获取书籍信息...");
        String bookName = bookId;
        if (cliBookName != null) {
            bookName = cliBookName;
        } else {
            bookName = fetchBookNameFromPage(bookId);
            if (bookName.isBlank()) bookName = bookId;
        }
        ImageInfo coverImage = null;
        try {
            var coverUrl = fetchCoverUrl(bookId);
            if (coverUrl != null) coverImage = downloadImage(coverUrl);
        } catch (Exception ignored) {}
        if (coverImage == null) {
            try {
                var sUrl = SEARCH_URL + "?q=" + URLEncoder.encode(bookId, StandardCharsets.UTF_8) + "&aid=1967";
                var sJson = fetchJson(sUrl);
                var sData = sJson instanceof JsonValue.JsonObject sjo ? sjo.map().get("data") : null;
                var sRet = sData != null ? optArray(sData, "ret_data") : new JsonValue.JsonArray(new ArrayList<>());
                var match = sRet.list().stream()
                        .filter(v -> optStr(v, "book_id").equals(bookId)).findFirst();
                if (match.isPresent()) {
                    var thumb = optStr(match.get(), "thumb");
                    if (!thumb.isBlank()) coverImage = downloadImage(thumb);
                }
            } catch (Exception ignored) {}
        }

        // Early cache: save book meta + cover (no chapters yet)
        Files.createDirectories(cacheDir);
        if (coverImage != null) {
            var cf = coverImage.filename().replace("images/", "");
            Files.write(cacheDir.resolve("cover"), coverImage.data());
        }
        // Write partial meta.json with book name only
        var partialMeta = "{\n  \"bookName\": \"" + jsonEscape(bookName) + "\"";
        if (coverImage != null) {
            partialMeta += ",\n  \"cover\": \"" + jsonEscape(coverImage.filename()) + "\""
                         + ",\n  \"coverMime\": \"" + jsonEscape(coverImage.mime()) + "\"";
        }
        partialMeta += ",\n  \"chapters\": []\n}\n";
        Files.writeString(cacheDir.resolve("meta.json"), partialMeta, StandardCharsets.UTF_8);
        if (bookName.equals(bookId)) {
            System.out.println("提示: 无法自动获取书名，可用 --book-name=名称 手动指定");
        }

        // ── 2. Fetch directory ──
        System.out.println("获取目录...");
        var chapters = fetchChaptersWithFallback(bookId);
        if (chapters.isEmpty()) {
            System.err.println("获取目录失败（已缓存书名和封面）");
            System.exit(1);
        }

        // ── 3. Calculate range ──
        int total = chapters.size();
        int startIdx = Math.max(1, start);
        int endIdx = end <= 0 ? total : end;
        if (startIdx > total) {
            System.err.println("起始章节 " + startIdx + " 超出范围（共 " + total + " 章）");
            System.exit(1);
        }
        if (endIdx > total) {
            System.out.println("结束章节 " + endIdx + " 超出范围，已自动截断至 " + total);
            endIdx = total;
        }
        if (startIdx > endIdx) {
            System.err.println("起始章节 " + startIdx + " 大于结束章节 " + endIdx);
            System.exit(1);
        }
        var selected = chapters.subList(startIdx - 1, endIdx);
        int totalChapters = selected.size();
        System.out.println("下载第 " + startIdx + " ~ " + endIdx + " 章（共 " + totalChapters + " 章）");

        // ── Break-All mode: no proxy, no device change, single chapter fetch, no cache, txt only, append ──
        if (breakAll) {
            System.out.println("Break-All 模式: 逐章获取，不换设备，不缓存，追加写入TXT");
            System.out.println("按 Ctrl+C 退出");
            // 初始化签名引擎（不换设备）
            System.setErr(new PrintStream(new OutputStream() {public void write(int b){}}));
            idleFQ = new IdleFQ(false);
            System.setErr(System.err);
            decryptKey = fetchDecryptKey();
            httpProxy = null;
            outputTxt = true;
            var outPath = outDir.resolve(bookId + "_" + startIdx + "-" + endIdx + ".txt");
            System.out.println("输出文件: " + outPath.toAbsolutePath());

            for (int i = 0; i < selected.size(); i++) {
                final int idx = i;
                final var ch = selected.get(i);
                String itemId = ch.itemId();
                String title = ch.title();
                int retryCount = 0;
                boolean success = false;

                while (!success) {
                    retryCount++;
                    try {
                        var bc = fetchBatchContent(itemId, bookId);
                        String raw = bc != null ? bc.get(itemId) : null;
                        if (raw == null || raw.isBlank()) {
                            System.out.println("第 " + (startIdx + idx) + " 章 " + title + " 无内容，第 " + retryCount + " 次重试...");
                            Thread.sleep(3000);
                            continue;
                        }
                        synchronized (System.out) {
                            try (var fw = new java.io.FileWriter(outPath.toFile(), java.nio.charset.StandardCharsets.UTF_8, true)) {
                                fw.write("\n第 " + (startIdx + idx) + " 章 " + title + "\n" + raw + "\n");
                            }
                        }
                        success = true;
                        System.out.println("第 " + (startIdx + idx) + " 章 " + title + " 完成" + (retryCount > 1 ? "（第 " + retryCount + " 次重试）" : ""));
                    } catch (Exception e) {
                        System.out.println("第 " + (startIdx + idx) + " 章 " + title + " 失败: " + e + "，第 " + retryCount + " 次重试...");
                        Thread.sleep(3000);
                    }
                }
            }
            System.out.println("Break-All 全部完成 → " + outPath.toAbsolutePath());
            return;
        }

        // ── Get-More mode: batch N chapters, retry batch on failure ──
        if (getMore > 0) {
            System.out.println("Get-More 模式: 批量 " + getMore + " 章，失败批量重试");
            System.setErr(new PrintStream(new OutputStream() {public void write(int b){}}));
            idleFQ = new IdleFQ(false);
            System.setErr(System.err);
            decryptKey = fetchDecryptKey();
            httpProxy = null;
            var chContents = new ConcurrentHashMap<Integer, String>();
            for (int g = 0; g < totalChapters; g += getMore) {
                int gEnd = Math.min(g + getMore, totalChapters);
                var batch = selected.subList(g, gEnd);
                List<Integer> failed = new ArrayList<>();
                for (int j = g; j < gEnd; j++) failed.add(j);
                while (!failed.isEmpty()) {
                    var ids = failed.stream().map(i -> selected.get(i).itemId()).collect(java.util.stream.Collectors.joining(","));
                    Thread.sleep(2000);
                    try {
                        var bc = fetchBatchContent(ids, bookId);
                        var iter = failed.iterator();
                        while (iter.hasNext()) {
                            int j = iter.next();
                            String raw = bc != null ? bc.get(selected.get(j).itemId()) : null;
                            if (raw == null || raw.isBlank()) continue;
                            String processed = outputTxt ? extractBlkText(raw) : blkToP(raw);
                            chContents.put(j, processed);
                            iter.remove();
                        }
                    } catch (Exception e) {
                        System.out.println("  批次 " + ((g/getMore)+1) + " 批量失败: " + e);
                    }
                }
                System.out.println("  批次 " + ((g/getMore)+1) + "/" + ((totalChapters+getMore-1)/getMore) + " 完成");
            }
            var ext = outputTxt ? ".txt" : ".epub";
            var outputPath = outDir.resolve(bookId + "_" + startIdx + "-" + endIdx + ext);
            generateOutput(outputPath.toString(), bookId, bookName, selected, totalChapters, outputTxt,
                           chContents, new ConcurrentHashMap<>(), coverImage);
            System.out.println("已保存: " + outputPath.toAbsolutePath());
            return;
        }

        // ── Get-All mode: batch all chapters at once, retry batch on failure ──
        if (getAll) {
            System.out.println("Get-All 模式: 一次性批量获取全部章节，失败批量重试");
            System.setErr(new PrintStream(new OutputStream() {public void write(int b){}}));
            idleFQ = new IdleFQ(false);
            System.setErr(System.err);
            decryptKey = fetchDecryptKey();
            httpProxy = null;
            var chContents = new ConcurrentHashMap<Integer, String>();
            List<Integer> failed = new ArrayList<>();
            for (int j = 0; j < totalChapters; j++) failed.add(j);
            while (!failed.isEmpty()) {
                var allIds = failed.stream().map(i -> selected.get(i).itemId()).collect(java.util.stream.Collectors.joining(","));
                Thread.sleep(2000);
                try {
                    var bc = fetchBatchContent(allIds, bookId);
                    var iter = failed.iterator();
                    while (iter.hasNext()) {
                        int j = iter.next();
                        String raw = bc != null ? bc.get(selected.get(j).itemId()) : null;
                        if (raw == null || raw.isBlank()) continue;
                        String processed = outputTxt ? extractBlkText(raw) : blkToP(raw);
                        chContents.put(j, processed);
                        iter.remove();
                    }
                } catch (Exception e) {
                    System.out.println("  批量失败: " + e + "，" + failed.size() + " 章待重试");
                }
            }
            var ext = outputTxt ? ".txt" : ".epub";
            var outputPath = outDir.resolve(bookId + "_" + startIdx + "-" + endIdx + ext);
            generateOutput(outputPath.toString(), bookId, bookName, selected, totalChapters, outputTxt,
                           chContents, new ConcurrentHashMap<>(), coverImage);
            System.out.println("已保存: " + outputPath.toAbsolutePath());
            return;
        }

        var ext = outputTxt ? ".txt" : ".epub";
        var outputPath = outDir.resolve(bookId + "_" + startIdx + "-" + endIdx + ext);

        // ── 4. Update meta.json with chapter list (no content yet) ──
        updateMetaCache(cacheDir, bookName, selected, startIdx, endIdx, coverImage);

        // ── 5. Check existing cache ──
        var chDir = cacheDir.resolve("chapters");
        var imgDir = cacheDir.resolve("images");
        int chaptersInCache = 0;
        if (Files.exists(chDir)) {
            try (var files = Files.list(chDir)) {
                chaptersInCache = (int) files.filter(p -> p.getFileName().toString().matches("\\d+")).count();
            }
        }
        // Count how many of the REQUESTED chapters are actually cached
        int requestedCached = 0;
        if (Files.exists(chDir)) {
            for (int i = 0; i < totalChapters; i++) {
                if (Files.exists(chDir.resolve(String.valueOf(i)))) {
                    requestedCached++;
                }
            }
        }
        if (requestedCached >= totalChapters && !redownload) {
            System.out.println("检测到缓存（已下载 " + requestedCached + "/" + totalChapters + " 章），直接生成输出...");
            generateFromCache(outputPath.toString(), bookId, outputTxt, bookName, selected, totalChapters);
            System.out.println("已保存: " + outputPath.toAbsolutePath());
            return;
        }
        if (requestedCached > 0) {
            System.out.println("使用已有缓存（已下载 " + requestedCached + "/" + totalChapters + " 章），继续下载剩余章节...");
        }

        // ── 6. Download with per-group caching ──
        System.out.println("并发组数: " + batchSize);

        var chapterContents = new ConcurrentHashMap<Integer, String>();
        var imageCache = new ConcurrentHashMap<String, ImageInfo>();
        var successCount = new AtomicInteger(0);
        var failCount = new AtomicInteger(0);

        // Load already-cached chapters into memory
        if (chaptersInCache > 0 && Files.exists(chDir)) {
            try (var files = Files.list(chDir)) {
                files.filter(p -> p.getFileName().toString().matches("\\d+")).forEach(p -> {
                    try {
                        var idx = Integer.parseInt(p.getFileName().toString());
                        chapterContents.put(idx, Files.readString(p, StandardCharsets.UTF_8));
                    } catch (Exception ignored) {}
                });
            }
        }
        // Load already-cached images
        if (Files.exists(imgDir)) {
            try (var files = Files.list(imgDir)) {
                files.forEach(p -> {
                    try {
                        var data = Files.readAllBytes(p);
                        var mimeAndExt = sniffMime(data);
                        if (!mimeAndExt[0].equals("application/octet-stream")) {
                            var fname = "images/" + p.getFileName().toString();
                            imageCache.put(fname, new ImageInfo(data, mimeAndExt[0], fname));
                        }
                    } catch (Exception ignored) {}
                });
            }
        }

        long startTime = System.currentTimeMillis();
        Files.createDirectories(chDir);
        Files.createDirectories(imgDir);
        final boolean isTxtOutput = outputTxt;
        final Path fCacheDir = cacheDir;

        // Initialize FQ signed API pipeline
        try {
            System.out.println("初始化签名引擎...");
            System.setErr(new PrintStream(new OutputStream() {public void write(int b){}}));
            idleFQ = new IdleFQ(false);
            System.setErr(System.err);
            System.out.println("获取解密密钥...");
            decryptKey = fetchDecryptKey();
            System.out.println("密钥已就绪");
        } catch (Exception e) {
            System.err.println("签名引擎初始化失败: " + e);
            if (idleFQ != null) try { idleFQ.destroy(); } catch (Exception ignored) {}
            System.exit(1);
        }

        var failedMap = new ConcurrentHashMap<Integer, String>();

        int numGroups = (totalChapters + GROUP_SIZE - 1) / GROUP_SIZE;
        var pool = Executors.newFixedThreadPool(batchSize);
        var futures = new ArrayList<Future<?>>();
        String fqBookId = bookId;
        for (int g = 0; g < numGroups; g++) {
            final int groupIdx = g;
            final String gBookId = fqBookId;
            final var fFailedMap = failedMap;
            int groupStart = g * GROUP_SIZE;
            int groupEnd = Math.min(groupStart + GROUP_SIZE, totalChapters);
            var groupChapters = new ArrayList<>(selected.subList(groupStart, groupEnd));
            futures.add(pool.submit(() -> {
                downloadGroup(groupIdx, groupChapters, groupStart, startIdx, isTxtOutput,
                              imageCache, chapterContents, successCount, failCount,
                              totalChapters, startTime, fCacheDir, gBookId, fFailedMap);
            }));
        }

        for (var f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        pool.shutdown();

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        var cached = (int) chapterContents.values().stream().filter(s -> !s.isEmpty()).count();
        var failedSize = failedMap.size();
        System.out.println("\n下载完成: " + cached + " 章已缓存, 本次新增 " + successCount.get() + " 成功, " + failCount.get() + " 失败, 用时 " + elapsed + "s");

        // ── 7. Retry failed chapters ──
        if (!failedMap.isEmpty()) {
            if (!debug) verbose = true;
            System.out.println("\n" + failedSize + " 章下载失败，开始集中重试（按 Enter 跳过等待/重置延時，Ctrl+C 停止）...");
            int delay = 1;
            int retryCycle = 0;
            int prevFailedCount = failedMap.size();
            while (!failedMap.isEmpty() && retryCycle < 30) {
                retryCycle++;
                // Detect stall: no progress in 3 consecutive cycles
                if (retryCycle > 3 && failedMap.size() >= prevFailedCount) {
                    System.out.println("  连续 " + retryCycle + " 次重试无进展，当前已失败 " + failedMap.size() + " 章");
                    if (retryCycle >= 20) {
                        System.out.println("  多次重试无进展，跳过剩余 " + failedMap.size() + " 章");
                        break;
                    }
                }
                prevFailedCount = failedMap.size();
                // Wait with Enter-skip support
                boolean skipped = waitWithSkip(delay);
                if (Thread.currentThread().isInterrupted()) break;
                if (skipped) { delay = 1; System.out.println("  重置延时"); }

                // Reinit device every cycle
                try {
                    System.out.println("--- 重试周期开始，重新注册设备 ---");
                    reinitDevice();
                } catch (Exception e) {
                    System.out.println("  引擎初始化失败: " + e);
                    if (idleFQ == null) {
                        delay = Math.min(delay * 2, 30);
                        System.out.println("  签名引擎不可用，" + delay + "s 后重试...");
                        continue;
                    }
                    System.out.println("  使用现有引擎和密钥继续重试...");
                }

                // Batch retry all remaining chapters (use proxy if not already set)
                if (httpProxy == null) {
                    System.out.println("  [代理] 批量重试前未绑定代理，尝试获取...");
                    pickAndBindProxy();
                }
                var remaining = new ArrayList<>(failedMap.entrySet());
                var idList = remaining.stream().map(Map.Entry::getValue).toArray(String[]::new);
                String retryItemIds = String.join(",", idList);
                System.out.println("  批量重试 " + remaining.size() + " 章 (itemIds=" + retryItemIds.substring(0, Math.min(80, retryItemIds.length())) + "...)");
                boolean batchRetrySucceeded = false;
                try {
                    var bc = fetchBatchContent(retryItemIds, bookId);
                    if (bc != null && !bc.isEmpty()) {
                        batchRetrySucceeded = true;
                        var iter = failedMap.entrySet().iterator();
                        while (iter.hasNext()) {
                            var entry = iter.next();
                            int idx = entry.getKey();
                            String itemId = entry.getValue();
                            String content = bc.get(itemId);
                            if (content != null && !content.isBlank()) {
                                if (outputTxt) content = extractBlkText(content);
                                else { content = blkToP(content); content = processImages(content, imageCache); }
                                chapterContents.put(idx, content);
                                Files.writeString(cacheDir.resolve("chapters").resolve(String.valueOf(idx)), content, StandardCharsets.UTF_8);
                                iter.remove();
                                System.out.println("  章节 " + (idx + 1) + " 重试成功");
                            }
                        }
                        if (!failedMap.isEmpty()) {
                            System.out.println("  批量重试部分成功，仍有 " + failedMap.size() + " 章无内容");
                        } else {
                            delay = 1;
                        }
                    } else {
                        System.out.println("  批量重试返回空");
                    }
                } catch (Exception e) {
                    System.out.println("  批量重试失败: " + e);
                }

                // Fallback: single chapter download for remaining failures
                if (!failedMap.isEmpty()) {
                    System.out.println("  尝试单章下载剩余 " + failedMap.size() + " 章...");
                    int consecutiveFails = 0;
                    var singleIter = failedMap.entrySet().iterator();
                    while (singleIter.hasNext()) {
                        // If 3 consecutive failures, device is likely banned - skip rest
                        if (consecutiveFails >= 3) {
                            System.out.println("  连续失败 3 次，设备可能被封，跳过剩余单章重试");
                            break;
                        }
                        var entry = singleIter.next();
                        int idx = entry.getKey();
                        String itemId = entry.getValue();
                        try {
                            String content = fetchSingleChapter(itemId, bookId);
                            if (content != null && !content.isBlank()) {
                                if (outputTxt) content = extractBlkText(content);
                                else { content = blkToP(content); content = processImages(content, imageCache); }
                                chapterContents.put(idx, content);
                                Files.writeString(cacheDir.resolve("chapters").resolve(String.valueOf(idx)), content, StandardCharsets.UTF_8);
                                singleIter.remove();
                                consecutiveFails = 0;
                                System.out.println("  章节 " + (idx + 1) + " 单章重试成功");
                            } else {
                                consecutiveFails++;
                            }
                        } catch (Exception e) {
                            consecutiveFails++;
                            System.out.println("  章节 " + (idx + 1) + " 单章重试失败: " + e);
                        }
                        // Small delay between single chapter requests
                        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                    if (failedMap.isEmpty()) {
                        delay = 1;
                    }
                }

                if (!failedMap.isEmpty()) {
                    delay = Math.min(delay * 2, 600);
                    System.out.println("  仍有 " + failedMap.size() + " 章失败，" + delay + "s 后重试...");
                }
            }
            if (!debug) verbose = false;
        }

        // ── 8. Generate output from cache ──
        generateFromCache(outputPath.toString(), bookId, outputTxt, bookName, selected, totalChapters);
        System.out.println("已保存: " + outputPath.toAbsolutePath());

        // clean temp files
        var tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        try (var files = Files.list(tmpDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("fanqie_"))
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
        if (idleFQ != null) try { idleFQ.destroy(); } catch (Exception ignored) {}
    }

    static void downloadGroup(int groupIdx, List<Chapter> chapters, int offset, int startChapter,
                               boolean outputTxt, ConcurrentHashMap<String, ImageInfo> imageCache,
                               ConcurrentHashMap<Integer, String> chapterContents,
                               AtomicInteger successCount, AtomicInteger failCount,
                               int totalChapters, long startTime,
                               Path cacheDir, String bookId,
                               ConcurrentHashMap<Integer, String> failedMap) {
        int a = startChapter + offset;
        int b = startChapter + offset + chapters.size() - 1;
        synchronized (System.out) {
            System.out.println("第 " + (groupIdx + 1) + " 组 (第 " + a + "-" + b + " 章): 开始下载");
        }

        String itemIds = String.join(",", chapters.stream().map(Chapter::itemId).toArray(String[]::new));

        // Phase 1: 3 retries with current device
        Map<String, String> batchContent = null;
        Exception lastException = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                synchronized (System.out) { System.out.println("  批量获取重试 (" + (attempt + 1) + "/3)"); }
                try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            try {
                batchContent = fetchBatchContent(itemIds, bookId);
                if (batchContent != null && !batchContent.isEmpty()) break;
            } catch (Exception e) { lastException = e; batchContent = null; }
        }

        // Phase 2: reinit device + 5 more retries
        if (batchContent == null || batchContent.isEmpty()) {
            String reason = lastException != null ? lastException.toString() : "批量返回空但HTTP 200";
            synchronized (System.out) { System.out.println("  批量获取无数据: " + reason + "，重新初始化设备..."); }
            try {
                reinitDevice();
                for (int attempt = 0; attempt < 5; attempt++) {
                    if (attempt > 0) Thread.sleep(1000L);
                    try {
                        batchContent = fetchBatchContent(itemIds, bookId);
                        if (batchContent != null && !batchContent.isEmpty()) break;
                    } catch (Exception e) { lastException = e; batchContent = null; }
                }
            } catch (Exception e) {
                lastException = e;
                batchContent = null;
            }
        }

        // Phase 3: Fallback to single chapter download if batch fails
        if (batchContent == null || batchContent.isEmpty()) {
            synchronized (System.out) {
                System.out.println("  批量获取失败，尝试单章下载...");
            }
            batchContent = new HashMap<>();
            int consecutiveFails = 0;
            for (int i = 0; i < chapters.size(); i++) {
                var ch = chapters.get(i);
                if (consecutiveFails >= 3) {
                    synchronized (System.out) {
                        System.out.println("  连续失败 3 次，设备可能被封，跳过本组剩余单章");
                    }
                    break;
                }
                try {
                    String content = fetchSingleChapter(ch.itemId(), bookId);
                    if (content != null && !content.isBlank()) {
                        batchContent.put(ch.itemId(), content);
                        consecutiveFails = 0;
                        synchronized (System.out) {
                            System.out.println("  单章下载成功: " + ch.title());
                        }
                    } else {
                        consecutiveFails++;
                    }
                } catch (Exception e) {
                    consecutiveFails++;
                    System.out.println("  单章下载失败: " + ch.title() + " - " + e);
                }
                // Small delay between single chapter requests to avoid rate limiting
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        // If still failed, skip these chapters for now
        if (batchContent == null || batchContent.isEmpty()) {
            synchronized (System.out) {
                System.out.println("  所有下载方式均失败，跳过本组" + (lastException != null ? ": " + lastException.getMessage() : ""));
            }
            for (int i = 0; i < chapters.size(); i++) {
                int idx = offset + i;
                if (chapterContents.containsKey(idx) && !chapterContents.get(idx).isEmpty()) continue;
                chapterContents.put(idx, "");
                failCount.incrementAndGet();
                failedMap.put(idx, chapters.get(i).itemId());
            }
            return;
        }

        for (int i = 0; i < chapters.size(); i++) {
            int idx = offset + i;
            if (chapterContents.containsKey(idx) && !chapterContents.get(idx).isEmpty()) {
                continue;
            }
            var ch = chapters.get(i);
            String content = batchContent.get(ch.itemId());
            if (content != null && !content.isBlank()) {
                if (outputTxt) {
                    content = extractBlkText(content);
                } else {
                    content = blkToP(content);
                    content = processImages(content, imageCache);
                }
                chapterContents.put(idx, content);
                successCount.incrementAndGet();
            } else {
                chapterContents.put(idx, "");
                failCount.incrementAndGet();
                failedMap.put(idx, ch.itemId());
            }
            if (content != null && !content.isEmpty()) {
                try {
                    Files.writeString(cacheDir.resolve("chapters").resolve(String.valueOf(idx)),
                                      content, StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }
        }
        // Save group images to cache
        var imgDir = cacheDir.resolve("images");
        for (var entry : imageCache.entrySet()) {
            if ("__cover__".equals(entry.getKey())) continue;
            var info = entry.getValue();
            var fname = info.filename().replace("images/", "");
            var imgFile = imgDir.resolve(fname);
            if (!Files.exists(imgFile)) {
                try { Files.write(imgFile, info.data()); } catch (Exception ignored) {}
            }
        }
        synchronized (System.out) {
            System.out.println("第 " + (groupIdx + 1) + " 组 (第 " + a + "-" + b + " 章): 结束下载");
        }
    }

    // ── FQ signed API methods ─────────────────────────────

    static String fetchDecryptKey() throws Exception {
        if (verbose) System.out.println("  [密钥] 构建注册请求...");
        FqVariable var = new FqVariable();
        var.setServerDeviceId(deviceId);
        var.setInstallId(installId);
        var.setCdid(deviceCdid);
        if (verbose) System.out.println("  [密钥] 使用设备 device_id=" + var.getServerDeviceId());
        FqCrypto crypto = new FqCrypto(FqCrypto.REG_KEY);
        String encContent = crypto.newRegisterKeyContent(var.getServerDeviceId(), "0");
        if (verbose) System.out.println("  [密钥] encrypted_content=" + encContent.substring(0, 40) + "...");

        String url = FQ_BASE_URL + "/reading/crypt/registerkey" + buildFqQS();
        if (verbose) System.out.println("  [密钥] POST registerkey");
        String sig = getFqSig(url);
        byte[] raw = fqHttpPost(url, sig, ("{\"content\":\"" + encContent + "\",\"keyver\":1}").getBytes(StandardCharsets.UTF_8));
        if (raw == null) throw new IOException("registerkey 返回空");

        String resp = tryGunzip(raw);
        if (resp == null) resp = new String(raw, StandardCharsets.UTF_8);

        // Parse response with error code checking (from APK's CryptERR enum)
        try {
            var parsed = new JsonParser(resp).parse();
            if (parsed instanceof JsonValue.JsonObject root) {
                int code = root.integer("code", 0);
                if (code != 0) {
                    String message = root.str("message", "unknown error");
                    // Handle specific error codes from APK's CryptERR enum
                    switch (code) {
                        case 1 -> throw new IOException("SYSTEM: 系统错误 (code=1)");
                        case 2 -> throw new IOException("INVALID_REQ: 无效请求 (code=2)");
                        case 100 -> throw new IOException("FAST_REJECT: 请求被快速拒绝 (code=100)");
                        case 110 -> throw new IOException("ILLEGAL_ACCESS: 非法访问，设备可能被封禁 (code=110)");
                        case 500000 -> throw new IOException("KEY_TOO_OLD: 密钥过期 (code=500000)");
                        case 500001 -> throw new IOException("ESCAPE_KEY: 逃逸密钥 (code=500001)");
                        case 500002 -> throw new IOException("VERIFY_FAIL: 验证失败 (code=500002)");
                        case 500003 -> {
                            if (verbose) System.out.println("  [密钥] DEGRADE_NONEED_CRYPT: 无需加密密钥 (code=500003)");
                            // Return empty key - will need to handle this case
                            return "";
                        }
                        default -> throw new IOException("registerkey error: " + message + " (code=" + code + ")");
                    }
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // Fallback to manual parsing if JsonParser fails
        }

        // Manual parsing fallback
        int p = resp.indexOf("\"key\"");
        if (p < 0) throw new IOException("registerkey 响应无key字段: " + resp.substring(0, Math.min(120, resp.length())));
        int c = resp.indexOf(':', p + 5);
        int sq = resp.indexOf('"', c + 1), eq = resp.indexOf('"', sq + 1);
        if (sq < 0 || eq <= sq) throw new IOException("key格式错误");
        String encryptedKey = resp.substring(sq + 1, eq);
        if (verbose) System.out.println("  [密钥] 加密密钥=" + encryptedKey.substring(0, 40) + "...");
        String realKey = FqCrypto.getRealKey(encryptedKey);
        if (verbose) System.out.println("  [密钥] 解密密钥=" + realKey);
        return realKey.length() > 32 ? realKey.substring(0, 32) : realKey;
    }

    static boolean waitWithSkip(int seconds) {
        if (verbose) System.out.println("  等待 " + seconds + "s（按 Enter 跳过）...");
        for (int i = 0; i < seconds * 5; i++) {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            try {
                while (System.in.available() > 0) {
                    int b = System.in.read();
                    if (b == '\n' || b == '\r') {
                        while (System.in.available() > 0) System.in.read();
                        return true;
                    }
                }
            } catch (IOException e) { return false; }
        }
        return false;
    }

    static void deviceRegister() throws Exception {
        if (verbose) System.out.println("  [注册] 上报设备信息...");
        String androidId = String.format("%016x", (long)(Math.random() * Long.MAX_VALUE));
        String md51 = sha1Hex(androidId).substring(0, 32);
        String md5x = md5(androidId);
        String openudid = (md5x + md5(md5x).substring(0, 8)).toLowerCase();
        String clientudid = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
        long now = System.currentTimeMillis();
        String reqId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String sigHash = String.format("%040x", (long)(Math.random() * Long.MAX_VALUE));
        String ipv6 = String.format("240e:%04x:%04x:%04x:%04x:%04x:%04x:%04x",
            (int)(Math.random()*65536),(int)(Math.random()*65536),(int)(Math.random()*65536),(int)(Math.random()*65536),
            (int)(Math.random()*65536),(int)(Math.random()*65536),(int)(Math.random()*65536),(int)(Math.random()*65536));
        String newCdid = UUID.randomUUID().toString();

        String jsonBody = "{\"magic_tag\":\"ss_app_log\",\"header\":{"
            + "\"display_name\":\"番茄小说\",\"aid\":1967,\"channel\":\"googleplay\""
            + ",\"package\":\"com.dragon.read.oversea.gp\",\"app_version\":\"6.8.1.32\""
            + ",\"version_code\":68132,\"update_version_code\":68132,\"manifest_version_code\":68132"
            + ",\"sdk_version\":\"3.7.0-rc.25-fanqie-xiaoshuo-opt\",\"sdk_target_version\":29"
            + ",\"git_hash\":\"5b6a0d3\",\"sdk_flavor\":\"china\""
            + ",\"os\":\"Android\",\"os_version\":\"12\",\"os_api\":32"
            + ",\"device_model\":\"OnePlus11\",\"device_brand\":\"OnePlus\",\"device_manufacturer\":\"OnePlus\""
            + ",\"cpu_abi\":\"arm64-v8a\",\"release_build\":\"V291IR\""
            + ",\"density_dpi\":640,\"display_density\":640,\"resolution\":\"3200*1440\""
            + ",\"language\":\"zh\",\"timezone\":8,\"access\":\"wifi\""
            + ",\"rom\":\"V291IR\",\"rom_version\":\"V291IR+release-keys\""
            + ",\"cdid\":\"" + newCdid + "\",\"sig_hash\":\"" + sigHash + "\""
            + ",\"openudid\":\"" + openudid + "\",\"clientudid\":\"" + clientudid + "\""
            + ",\"android_id\":\"" + androidId + "\""
            + ",\"region\":\"CN\",\"tz_name\":\"Asia/Shanghai\",\"tz_offset\":28800"
            + ",\"device_platform\":\"android\",\"oaid_may_support\":false"
            + ",\"req_id\":\"" + reqId + "\",\"sim_serial_number\":[]"
            + ",\"ipv6_list\":[{\"type\":\"client_anpi\",\"value\":\"" + ipv6 + "\"}]"
            + "},\"_gen_time\":" + now + "}";
        if (verbose) {
            System.out.println("  [注册] POST https://log5-applog.fqnovel.com/service/2/device_register/");
            System.out.println("  [注册] 请求体(" + jsonBody.length() + "b): " + jsonBody.substring(0, Math.min(300, jsonBody.length())));
        }

        String params = "?aid=1967&version_code=68132&channel=googleplay&package=com.dragon.read.oversea.gp"
                     + "&_rticket=" + now + "&use_store_region_cookie=1&okhttp_version=4.2.137.76-fanqie";
        var u = new URL("https://log5-applog.fqnovel.com/service/2/device_register/" + params);
        var conn = (HttpURLConnection) (httpProxy != null ? u.openConnection(httpProxy) : u.openConnection());
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", FQ_UA);
        conn.setRequestProperty("Cookie", FQ_COOKIE);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("log-encode-type", "gzip");
        conn.setRequestProperty("x-ss-req-ticket", String.valueOf(now));
        conn.setRequestProperty("x-vc-bdturing-sdk-version", "3.7.2.cn");
        conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();
        int statusCode = conn.getResponseCode();
        InputStream is = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        byte[] raw = is.readAllBytes();
        String respBody = tryGunzip(raw);
        if (respBody == null) respBody = new String(raw, StandardCharsets.UTF_8);
        conn.disconnect();
        if (verbose) System.out.println("  [注册] 响应: " + statusCode + " " + respBody);
        if (statusCode == 200) {
            try {
                var parsed = new JsonParser(respBody).parse();
                if (parsed instanceof JsonValue.JsonObject jo) {
                    String newDeviceId = deviceId;
                    String newInstallId = installId;
                    var did = jo.map().get("device_id");
                    if (did instanceof JsonValue.JsonNumber jn) newDeviceId = String.valueOf(jn.longValue());
                    var iid = jo.map().get("install_id");
                    if (iid instanceof JsonValue.JsonNumber jn2) newInstallId = String.valueOf(jn2.longValue());
                    if (!newDeviceId.equals(deviceId) || !newInstallId.equals(installId)) {
                        if ("0".equals(newDeviceId) && "0".equals(newInstallId)) {
                            // Server refused — generate random IDs instead of keeping banned ones
                            newDeviceId = String.valueOf(10_000_000_000_000_000L + (long)(Math.random() * 8_999_999_999_999_999L));
                            newInstallId = String.valueOf(Long.parseLong(newDeviceId) + 1 + (int)(Math.random() * 1000));
                            deviceId = newDeviceId;
                            installId = newInstallId;
                            deviceCdid = newCdid;
                            FQ_COOKIE = "store-region=cn-zj; store-region-src=did; install_id=" + installId;
                            if (verbose) System.out.println("  [注册] 服务器拒绝注册，改用随机设备: device_id=" + deviceId + " install_id=" + installId + " cdid=" + deviceCdid);
                        } else {
                            deviceId = newDeviceId;
                            installId = newInstallId;
                            deviceCdid = newCdid;
                            FQ_COOKIE = "store-region=cn-zj; store-region-src=did; install_id=" + installId;
                            if (verbose) System.out.println("  [注册] 服务器返回新设备: device_id=" + deviceId + " install_id=" + installId + " cdid=" + deviceCdid);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    static String md5(String s) {
        try {
            var md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return s; }
    }

    static synchronized void reinitDevice() throws Exception {
        if (verbose) System.out.println("  [设备] 注册新设备...");
        System.out.println("  [代理] 为新设备绑定代理...");
        pickAndBindProxy();
        deviceRegister();
        if (verbose) System.out.println("  [引擎] 销毁旧签名引擎...");
        if (idleFQ != null) {
            try { idleFQ.destroy(); } catch (Exception ignored) {}
        }
        if (verbose) System.out.println("  [引擎] 初始化新签名引擎...");
        System.setErr(new PrintStream(new OutputStream() {public void write(int b){}}));
        idleFQ = new IdleFQ(false);
        System.setErr(System.err);
        if (verbose) System.out.println("  [密钥] 获取解密密钥...");
        try {
            decryptKey = fetchDecryptKey();
            if (verbose) System.out.println("  [密钥] 已就绪: " + decryptKey.substring(0, 8) + "...");
        } catch (Exception e) {
            System.out.println("  [密钥] 获取失败: " + e + "，使用旧密钥: " + (decryptKey != null ? decryptKey.substring(0, 8) + "..." : "null"));
        }
    }

    static Map<String, String> fetchBatchContent(String itemIds, String bookId) throws Exception {
        if (verbose) System.out.println("  [batch] item_ids=" + itemIds);
        Map<String, String> params = buildFqParams();
        params.put("item_ids", itemIds);
        params.put("key_register_ts", "0");
        params.put("book_id", bookId);
        params.put("req_type", "1");
        String url = FQ_BASE_URL + "/reading/reader/batch_full/v1" + buildFqQS(params);
        String sig = getFqSig(url);
        byte[] raw = fqHttpGet(url, sig);
        if (raw == null || raw.length == 0) throw new IOException("batch_full 返回空 (url=" + url + ")");

        String json = tryGunzip(raw);
        if (json == null) json = new String(raw, StandardCharsets.UTF_8);

        // Parse response with error code checking (based on APK analysis)
        Map<String, String> results = new HashMap<>();
        try {
            var parsed = new JsonParser(json).parse();
            if (parsed instanceof JsonValue.JsonObject root) {
                // Check error code first (from APK's ReaderApiERR enum)
                int code = root.integer("code", 0);
                if (code != 0) {
                    String message = root.str("message", "unknown error");
                    // Handle specific error codes from APK analysis
                    switch (code) {
                        case 100 -> throw new IOException("FAST_REJECT: 请求被快速拒绝 (code=100)");
                        case 110 -> throw new IOException("ILLEGAL_ACCESS: 非法访问，设备可能被封禁 (code=110)");
                        case 111 -> throw new IOException("HIT_VERIFY_CODE: 触发验证码 (code=111)");
                        case 101004 -> throw new IOException("BOOK_NOT_EXIST_ERROR: 书籍不存在 (code=101004)");
                        case 101005 -> throw new IOException("CHAPTER_DATA_GET_ERROR: 章节数据获取错误 (code=101005)");
                        case 101009 -> throw new IOException("USER_NO_PERMISSION: 用户无权限 (code=101009)");
                        case 101017 -> throw new IOException("CONTENT_VERIFYING: 内容审核中 (code=101017)");
                        case 101021 -> throw new IOException("BOOK_FULLLY_REMOVE: 书籍已被完全移除 (code=101021)");
                        default -> {
                            if (code >= 100 && code <= 111) {
                                throw new IOException("ReaderApiERR: " + message + " (code=" + code + ")");
                            }
                            // Non-critical error codes (101xxx) - log but continue
                            System.out.println("  [batch] 响应码=" + code + " " + message);
                            // If data is empty, include error so caller knows
                            var d = root.get("data");
                            if (!(d instanceof JsonValue.JsonObject) || ((JsonValue.JsonObject)d).map().isEmpty()) {
                                throw new IOException("响应错误 " + code + ": " + message);
                            }
                        }
                    }
                }

                var data = root.get("data");
                if (data instanceof JsonValue.JsonObject dataObj) {
                    for (var entry : dataObj.map().entrySet()) {
                        var itemId = entry.getKey();
                        if (entry.getValue() instanceof JsonValue.JsonObject itemObj) {
                            // Extract chapter title if available
                            String title = itemObj.str("title", "");
                            if (verbose && !title.isEmpty()) {
                                System.out.println("  [batch] 章节: " + title);
                            }

                            // Check crypt_status (from APK's ItemContent model)
                            // crypt_status: 0=encrypted, 1=NOT encrypted (direct use), 2=key expired (need re-register)
                            int cryptStatus = itemObj.integer("crypt_status", 0);

                            var contentStr = itemObj.str("content");
                            if (contentStr != null && !contentStr.isBlank()) {
                                try {
                                    String decrypted;
                                    switch (cryptStatus) {
                                        case 1 -> {
                                            // NOT encrypted - use content directly
                                            decrypted = contentStr;
                                            if (verbose) System.out.println("  [batch] 章节未加密 (crypt_status=1)");
                                        }
                                        case 2 -> {
                                            // Key expired/missing - need re-register (handled by caller)
                                            decrypted = contentStr;
                                            if (verbose) System.out.println("  [batch] 密钥过期，尝试直接使用 (crypt_status=2)");
                                        }
                                        default -> {
                                            // crypt_status=0 or unknown - decrypt normally
                                            decrypted = FqCrypto.decryptAndDecompressContent(contentStr, decryptKey);
                                        }
                                    }
                                    results.put(itemId, decrypted);
                                } catch (Exception ex) {
                                    System.out.println("  [batch] 解密失败 itemId=" + itemId + " crypt=" + cryptStatus + ": " + ex);
                                    results.put(itemId, "");
                                }
                            }
                        }
                        }
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("JSON解析失败: " + e);
        }
        if (results.isEmpty()) {
            System.out.println("  [batch] 完整响应: " + json.substring(0, Math.min(2000, json.length())));
        }
        return results;
    }

    // Single chapter download fallback (from APK's /reading/reader/newfull/v:version/)
    static String fetchSingleChapter(String itemId, String bookId) throws Exception {
        if (verbose) System.out.println("  [single] item_id=" + itemId);
        Map<String, String> params = buildFqParams();
        params.put("item_id", itemId);
        params.put("key_register_ts", "0");
        params.put("book_id", bookId);
        params.put("req_type", "1");
        String url = FQ_BASE_URL + "/reading/reader/newfull/v1" + buildFqQS(params);
        String sig = getFqSig(url);
        byte[] raw = fqHttpGet(url, sig);
        if (raw == null || raw.length == 0) throw new IOException("newfull 返回空");

        String json = tryGunzip(raw);
        if (json == null) json = new String(raw, StandardCharsets.UTF_8);

        try {
            var parsed = new JsonParser(json).parse();
            if (parsed instanceof JsonValue.JsonObject root) {
                // Check error code
                int code = root.integer("code", 0);
                if (code != 0) {
                    String message = root.str("message", "unknown error");
                    throw new IOException("newfull error: " + message + " (code=" + code + ")");
                }

                var data = root.get("data");
                if (data instanceof JsonValue.JsonObject dataObj) {
                    // Check crypt_status
                    int cryptStatus = dataObj.integer("crypt_status", 0);

                    var contentStr = dataObj.str("content");
                    if (contentStr != null && !contentStr.isBlank()) {
                        switch (cryptStatus) {
                            case 1 -> {
                                // NOT encrypted - use directly
                                return contentStr;
                            }
                            case 2 -> {
                                // Key expired/missing - try direct use
                                return contentStr;
                            }
                            default -> {
                                // crypt_status=0 - decrypt normally
                                return FqCrypto.decryptAndDecompressContent(contentStr, decryptKey);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("JSON解析失败: " + e.getMessage());
        }
        return "";
    }

    static Map<String, String> buildFqParams() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("iid", installId);
        p.put("device_id", deviceId);
        p.put("ac", "wifi"); p.put("channel", "googleplay"); p.put("aid", "1967");
        p.put("app_name", "novelapp"); p.put("version_code", "68132");
        p.put("version_name", "6.8.1.32"); p.put("device_platform", "android");
        p.put("os", "android"); p.put("ssmix", "a"); p.put("device_type", "OnePlus11");
        p.put("device_brand", "OnePlus"); p.put("language", "zh"); p.put("os_api", "32");
        p.put("os_version", "12"); p.put("manifest_version_code", "68132");
        p.put("resolution", "3200*1440"); p.put("dpi", "640");
        p.put("update_version_code", "68132");
        p.put("_rticket", String.valueOf(System.currentTimeMillis()));
        p.put("host_abi", "arm64-v8a"); p.put("dragon_device_type", "phone");
        p.put("pv_player", "68132"); p.put("compliance_status", "0");
        p.put("need_personal_recommend", "1"); p.put("player_so_load", "1");
        p.put("is_android_pad_screen", "0"); p.put("rom_version", "V291IR+release-keys");
        p.put("cdid", deviceCdid);
        return p;
    }

    static String buildFqQS() { return buildFqQS(buildFqParams()); }

    static String buildFqQS(Map<String, String> params) {
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) sb.append("&");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    static Map<String, String> buildFqHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cookie", FQ_COOKIE);
        h.put("User-Agent", FQ_UA);
        h.put("Accept", "application/json; charset=utf-8");
        h.put("Accept-Encoding", "gzip");
        h.put("x-xs-from-web", "0");
        h.put("x-ss-req-ticket", String.valueOf(System.currentTimeMillis()));
        h.put("x-reading-request", System.currentTimeMillis() + "-" + (int)(Math.random() * 2e9));
        h.put("x-vc-bdturing-sdk-version", "3.7.2.cn");
        h.put("lc", "101"); h.put("sdk-version", "2");
        h.put("passport-sdk-version", "50564");
        h.put("x-tt-store-region", "cn-zj");
        h.put("x-tt-store-region-src", "did");
        return h;
    }

    static synchronized String getFqSig(String url) {
        Map<String, String> h = buildFqHeaders();
        StringBuilder sb = new StringBuilder();
        for (var e : h.entrySet())
            sb.append(e.getKey()).append("\r\n").append(e.getValue()).append("\r\n");
        String hs = sb.toString();
        if (hs.endsWith("\r\n")) hs = hs.substring(0, hs.length() - 2);
        return idleFQ.generateSignature(url, hs);
    }

    static HttpURLConnection openFqConn(String url) throws Exception {
        var u = new URL(url);
        return (HttpURLConnection) (httpProxy != null ? u.openConnection(httpProxy) : u.openConnection());
    }

    static List<String> fetchChineseProxies() {
        try {
            var url = PROXY_API + "?protocol=https&count=50&country_code=CN";
            var conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code != 200) return List.of();
            byte[] raw = conn.getInputStream().readAllBytes();
            conn.disconnect();
            String json = new String(raw, StandardCharsets.UTF_8);
            var parsed = new JsonParser(json).parse();
            if (parsed instanceof JsonValue.JsonObject jo && jo.integer("code", 0) == 200) {
                var data = jo.obj("data");
                if (data != null) {
                    var proxies = data.arr("proxies");
                    if (proxies != null && !proxies.isEmpty()) {
                        return proxies.stream().map(v -> ((JsonValue.JsonString)v).value()).toList();
                    }
                }
            }
        } catch (Exception e) {
            if (verbose) System.out.println("  [代理] 获取代理失败: " + e.getMessage());
        }
        return List.of();
    }

    /** 测试代理，成功返回 null 并将延迟(ms)写入 outLatency，失败返回错误原因（跳过证书验证，仅测连通性） */
    static String testProxy(Proxy p, long[] outLatency) {
        try {
            var url = new URL("https://www.baidu.com/");
            var conn = (HttpURLConnection) url.openConnection(p);
            conn.setConnectTimeout(20000); conn.setReadTimeout(15000);
            // 跳过证书验证——仅测隧道连通性，不关心 MITM
            if (conn instanceof HttpsURLConnection ssl) {
                ssl.setSSLSocketFactory(TRUST_ALL_SF);
                ssl.setHostnameVerifier(TRUST_ALL_HV);
            }
            long start = System.nanoTime();
            int code = conn.getResponseCode();
            long latency = (System.nanoTime() - start) / 1_000_000;
            conn.disconnect();
            if (code == 200) {
                outLatency[0] = latency;
                return null;
            }
            return "返回码=" + code;
        } catch (java.net.SocketTimeoutException e) {
            return "超时";
        } catch (java.net.ConnectException e) {
            return "连接被拒绝";
        } catch (javax.net.ssl.SSLException e) {
            return "SSL错误: " + e.getMessage();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static final String[] BUILT_IN_PROXIES = {
        "180.166.128.182:12011",
        "8.138.131.110:9095"
    };

    /** 多线程测试所有候选代理，返回延迟最小的（不可用返回null） */
    static String pickBestProxy(List<String> candidates) {
        int total = candidates.size();
        var bestRef = new String[1];
        var bestLatRef = new long[]{Long.MAX_VALUE};
        var latch = new CountDownLatch(total);
        var executor = Executors.newFixedThreadPool(10);
        var started = new AtomicInteger(0);
        for (var proxyStr : candidates) {
            executor.submit(() -> {
                int idx = started.incrementAndGet();
                String[] parts = proxyStr.split(":");
                var p = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
                var latArr = new long[1];
                String err = testProxy(p, latArr);
                if (err == null) {
                    long latency = latArr[0];
                    System.out.println("  [" + idx + "/" + total + "] " + proxyStr + " 延迟=" + latency + "ms");
                    synchronized (bestRef) {
                        if (latency < bestLatRef[0]) {
                            bestLatRef[0] = latency;
                            bestRef[0] = proxyStr;
                        }
                    }
                } else {
                    System.out.println("  [" + idx + "/" + total + "] " + proxyStr + " " + err);
                }
                latch.countDown();
            });
        }
        executor.shutdown();
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        executor.shutdownNow();
        return bestRef[0];
    }

    /** 获取50个代理（含内置），10线程并行测速，选延迟最小且未用过的绑定到 httpProxy */
    static boolean pickAndBindProxy() {
        for (int attempt = 0; attempt < 10; attempt++) {
            var candidates = new ArrayList<String>();
            // 内置代理优先
            for (var p : BUILT_IN_PROXIES) {
                if (!usedProxies.contains(p)) candidates.add(p);
            }
            // API 代理
            System.out.println("  [代理] 获取50个中国HTTPS代理...");
            var apiProxies = fetchChineseProxies();
            if (apiProxies.isEmpty()) {
                System.out.println("  [代理] API未返回代理");
            } else {
                System.out.println("  [代理] API返回 " + apiProxies.size() + " 个（API可能限制上限）");
                apiProxies.stream().filter(p -> !usedProxies.contains(p)).forEach(candidates::add);
            }
            if (candidates.isEmpty()) {
                System.out.println("  [代理] 所有代理已使用过");
                return false;
            }
            System.out.println("  [代理] " + candidates.size() + " 个候选代理，10线程并行测速...");
            var best = pickBestProxy(candidates);
            if (best != null) {
                usedProxies.add(best);
                String[] parts = best.split(":");
                httpProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
                System.out.println("  [代理] 已绑定 " + best);
                return true;
            }
            System.out.println("  [代理] 本轮 " + candidates.size() + " 个代理全部不可用，重新获取...");
            usedProxies.addAll(candidates);
        }
        System.out.println("  [代理] 连续10次获取均无可用代理");
        return false;
    }

    static byte[] fqHttpGet(String url, String sig) throws Exception {
        if (verbose) {
            System.out.println("  [HTTP] GET " + url);
            System.out.println("  [HTTP] Cookie: " + FQ_COOKIE);
            System.out.println("  [HTTP] UA: " + FQ_UA);
            if (sig != null) {
                for (String line : sig.split("\n")) System.out.println("  [HTTP] " + line);
            }
        }
        HttpURLConnection c = openFqConn(url);
        c.setRequestMethod("GET"); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", FQ_UA); c.setRequestProperty("Cookie", FQ_COOKIE);
        if (sig != null && !sig.isEmpty()) {
            String[] lines = sig.split("\n");
            for (int i = 0; i < lines.length - 1; i += 2)
                c.setRequestProperty(lines[i].trim(), lines[i + 1].trim());
        }
        Map<String, String> hdrs = buildFqHeaders();
        for (var e : hdrs.entrySet()) {
            if (!e.getKey().equals("Cookie") && !e.getKey().equals("User-Agent"))
                c.setRequestProperty(e.getKey(), e.getValue());
        }
        int code = c.getResponseCode();
        System.out.println("  [HTTP] " + url.substring(0, Math.min(120, url.length())) + " → " + code);
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) {
            System.out.println("  [HTTP] 无法获取响应流");
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        byte[] data = bos.toByteArray();
        if (data.length > 0 && (verbose || code >= 400)) {
            String preview = tryGunzip(data);
            if (preview == null) preview = new String(data, StandardCharsets.UTF_8);
            if (debug) {
                System.out.println("  [HTTP] 响应体(" + data.length + "b): " + preview);
            } else {
                System.out.println("  [HTTP] 响应体(" + data.length + "b): " + preview.substring(0, Math.min(500, preview.length())));
            }
        }
        is.close(); return data;
    }

    static byte[] fqHttpPost(String url, String sig, byte[] body) throws Exception {
        if (verbose) {
            System.out.println("  [HTTP] POST " + url);
            System.out.println("  [HTTP] Cookie: " + FQ_COOKIE);
            System.out.println("  [HTTP] UA: " + FQ_UA);
            System.out.println("  [HTTP] 请求体: " + new String(body, StandardCharsets.UTF_8));
            if (sig != null) {
                for (String line : sig.split("\n")) System.out.println("  [HTTP] " + line);
            }
        }
        HttpURLConnection c = openFqConn(url);
        c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (sig != null && !sig.isEmpty()) {
            String[] lines = sig.split("\n");
            for (int i = 0; i < lines.length - 1; i += 2)
                c.setRequestProperty(lines[i].trim(), lines[i + 1].trim());
        }
        Map<String, String> hdrs = buildFqHeaders();
        for (var e : hdrs.entrySet()) {
            if (!e.getKey().equals("Content-Type"))
                c.setRequestProperty(e.getKey(), e.getValue());
        }
        c.getOutputStream().write(body); c.getOutputStream().flush();
        int code = c.getResponseCode();
        System.out.println("  [HTTP] POST " + url.substring(0, Math.min(120, url.length())) + " → " + code);
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        byte[] data = bos.toByteArray();
        if (data.length > 0 && (verbose || code >= 400)) {
            String preview = tryGunzip(data);
            if (preview == null) preview = new String(data, StandardCharsets.UTF_8);
            if (debug) {
                System.out.println("  [HTTP] 响应体(" + data.length + "b): " + preview);
            } else {
                System.out.println("  [HTTP] 响应体(" + data.length + "b): " + preview.substring(0, Math.min(500, preview.length())));
            }
        }
        is.close(); return data;
    }

    static String tryGunzip(byte[] data) {
        if (data == null || data.length < 2) return null;
        if ((data[0] & 0xff) != 0x1f || (data[1] & 0xff) != 0x8b) return null;
        try {
            GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = gis.read(buf)) != -1) bos.write(buf, 0, n);
            gis.close(); return bos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    static int findStrEnd(String s, int start) {
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') return i;
        }
        return s.length();
    }

    static String decodeUnicode(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                i += 5;
            } else {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    static String extractBlkText(String html) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        boolean first = true;
        while (true) {
            int st = html.indexOf("<blk", idx);
            if (st < 0) break;
            int ct = html.indexOf(">", st);
            if (ct < 0) break;
            int en = html.indexOf("</blk>", ct);
            if (en < 0) { sb.append(html.substring(ct + 1)); break; }
            String blk = html.substring(ct + 1, en);
            if (first) { first = false; idx = en + 6; continue; } // skip first <blk> (chapter title)
            sb.append(blk).append("\n");
            idx = en + 6;
        }
        String result = sb.toString().replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&nbsp;", " ");
        result = result.replaceAll("(?i)<br\\s*/?>", "\n");
        result = result.replaceAll("<[^>]+>", "");
        return result.strip();
    }

    static String blkToP(String html) {
        String result = html.replaceAll("(?i)<blk[^>]*>", "<p>");
        result = result.replaceAll("(?i)</blk>", "</p>");
        result = result.replaceAll("(?i)<imge[^>]*/>", "");
        return result;
    }

    // ── Content extraction (TXT path) ───────────────────

    static String fetchBookNameFromPage(String bookId) {
        try {
            var state = parsePageState(bookId);
            if (state instanceof JsonValue.JsonObject so) {
                var page = so.get("page");
                if (page instanceof JsonValue.JsonObject p) {
                    var name = p.str("bookName");
                    if (name != null) return name;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ── Image processing ────────────────────────────────

    static String processImages(String html, ConcurrentHashMap<String, ImageInfo> cache) {
        var matcher = IMG_PATTERN.matcher(html);
        var sb = new StringBuffer();
        while (matcher.find()) {
            String src = matcher.group(1);
            String newSrc = downloadAndCacheImage(src, cache);
            String tag = matcher.group(0);
            String rewritten = tag.replace(src, newSrc);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(rewritten));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    static ImageInfo downloadImage(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", UA)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                byte[] data = resp.body();
                var mimeAndExt = sniffMime(data);
                if (mimeAndExt[0].equals("application/octet-stream")) return null;
                String hash = sha1Hex(url);
                return new ImageInfo(data, mimeAndExt[0], "images/" + hash + mimeAndExt[1]);
            }
        } catch (Exception ignored) {}
        return null;
    }

    static String downloadAndCacheImage(String url, ConcurrentHashMap<String, ImageInfo> cache) {
        var existing = cache.get(url);
        if (existing != null) return existing.filename();

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return url;
        }

        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", UA)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                byte[] data = resp.body();
                var mimeAndExt = sniffMime(data);
                String mime = mimeAndExt[0];
                String ext = mimeAndExt[1];
                if (mime.equals("application/octet-stream")) return url;

                String hash = sha1Hex(url);
                String filename = "images/" + hash + ext;
                var info = new ImageInfo(data, mime, filename);
                var prev = cache.putIfAbsent(url, info);
                return prev != null ? prev.filename() : filename;
            }
        } catch (Exception ignored) {}
        return url;
    }

    static String[] sniffMime(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte)0xFF && bytes[1] == (byte)0xD8 && bytes[2] == (byte)0xFF)
            return new String[]{"image/jpeg", ".jpg"};
        if (bytes.length >= 8 && bytes[0] == (byte)0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47)
            return new String[]{"image/png", ".png"};
        if (bytes.length >= 6 && bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46)
            return new String[]{"image/gif", ".gif"};
        if (bytes.length >= 12 && bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46)
            return new String[]{"image/webp", ".webp"};
        return new String[]{"application/octet-stream", ""};
    }

    // ── EPUB generation ─────────────────────────────────

    static void generateEpub(String outputPath, String bookId, String bookName, List<Chapter> chapters,
                              ConcurrentHashMap<Integer, String> contents,
                              ConcurrentHashMap<String, ImageInfo> imageCache,
                              ImageInfo coverImage,
                              int totalChapters) throws Exception {

        try (var fos = Files.newOutputStream(Path.of(outputPath));
             var zos = new ZipOutputStream(fos)) {
            zos.setLevel(9);

            // 1. mimetype (must be first, STORED, uncompressed)
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.UTF_8);
            var mimetypeEntry = new ZipEntry("mimetype");
            mimetypeEntry.setMethod(ZipEntry.STORED);
            mimetypeEntry.setSize(mimetype.length);
            mimetypeEntry.setCompressedSize(mimetype.length);
            var crc = new CRC32();
            crc.update(mimetype);
            mimetypeEntry.setCrc(crc.getValue());
            zos.putNextEntry(mimetypeEntry);
            zos.write(mimetype);
            zos.closeEntry();

            // 2. META-INF/container.xml
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(buildContainerXml().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 3. stylesheet
            zos.putNextEntry(new ZipEntry("OEBPS/stylesheet.css"));
            zos.write(getCss().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 4. Cover
            String coverImageId = null;
            String coverHref = null;
            if (coverImage != null) {
                coverHref = "cover.xhtml";
                var coverXhtml = buildCoverXhtml(bookName, coverImage.filename());
                zos.putNextEntry(new ZipEntry("OEBPS/" + coverHref));
                zos.write(coverXhtml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                // write cover image
                zos.putNextEntry(new ZipEntry("OEBPS/" + coverImage.filename()));
                zos.write(coverImage.data());
                zos.closeEntry();
                coverImageId = "cover-image";
                // add cover image to imageCache so buildOpfXml includes it
                imageCache.putIfAbsent("__cover__", coverImage);
            }

            // 5. Chapter XHTML files
            var itemIds = new ArrayList<String>();
            for (int i = 0; i < totalChapters; i++) {
                var ch = i < chapters.size() ? chapters.get(i) : null;
                String title = ch != null ? ch.title() : "章节" + (i + 1);
                String body = contents.getOrDefault(i, "");
                var xhtml = buildChapterXhtml(title, body);
                String fid = String.format("chapter_%05d.xhtml", i + 1);
                zos.putNextEntry(new ZipEntry("OEBPS/" + fid));
                zos.write(xhtml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                itemIds.add(fid);
            }

            // 6. TOC XHTML (visible table of contents)
            String tocHref = "toc.xhtml";
            zos.putNextEntry(new ZipEntry("OEBPS/" + tocHref));
            zos.write(buildTocXhtml(bookName, chapters, totalChapters).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 7. Images (skip cover, already written above)
            for (var entry : imageCache.entrySet()) {
                if ("__cover__".equals(entry.getKey())) continue;
                var info = entry.getValue();
                zos.putNextEntry(new ZipEntry("OEBPS/" + info.filename()));
                zos.write(info.data());
                zos.closeEntry();
            }

            // 8. content.opf
            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(buildOpfXml(bookId, bookName, chapters, imageCache, itemIds, coverHref, tocHref, totalChapters)
                     .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 9. toc.ncx
            zos.putNextEntry(new ZipEntry("OEBPS/toc.ncx"));
            zos.write(buildNcxXml(bookId, bookName, chapters, itemIds, totalChapters)
                     .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    static String buildContainerXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
             + "  <rootfiles>\n"
             + "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n"
             + "  </rootfiles>\n"
             + "</container>\n";
    }

    static String buildOpfXml(String bookId, String bookName, List<Chapter> chapters,
                               ConcurrentHashMap<String, ImageInfo> imageCache,
                               List<String> itemIds, String coverHref, String tocHref,
                               int totalChapters) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"BookId\">\n");
        sb.append("  <metadata>\n");
        sb.append("    <dc:identifier id=\"BookId\">").append(xmlEscape(bookId)).append("</dc:identifier>\n");
        sb.append("    <dc:title>").append(xmlEscape(bookName)).append("</dc:title>\n");
        sb.append("    <dc:language>zh</dc:language>\n");
        if (coverHref != null) {
            sb.append("    <meta name=\"cover\" content=\"cover-image\"/>\n");
        }
        sb.append("  </metadata>\n");

        sb.append("  <manifest>\n");
        sb.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
        sb.append("    <item id=\"css\" href=\"stylesheet.css\" media-type=\"text/css\"/>\n");
        if (coverHref != null) {
            sb.append("    <item id=\"cover\" href=\"")
              .append(coverHref)
              .append("\" media-type=\"application/xhtml+xml\"/>\n");
        }
        if (tocHref != null) {
            sb.append("    <item id=\"toc\" href=\"")
              .append(tocHref)
              .append("\" media-type=\"application/xhtml+xml\"/>\n");
        }

        for (int i = 0; i < totalChapters; i++) {
            String fid = itemIds.get(i);
            sb.append("    <item id=\"ch_")
              .append(String.format("%05d", i + 1))
              .append("\" href=\"")
              .append(fid)
              .append("\" media-type=\"application/xhtml+xml\"/>\n");
        }

        int imgIdx = 0;
        for (var entry : imageCache.entrySet()) {
            var info = entry.getValue();
            var id = entry.getKey().equals("__cover__") ? "cover-image"
                     : String.format("img_%04d", imgIdx++);
            sb.append("    <item id=\"")
              .append(id)
              .append("\" href=\"")
              .append(info.filename())
              .append("\" media-type=\"")
              .append(info.mime())
              .append("\"/>\n");
        }

        sb.append("  </manifest>\n");

        sb.append("  <spine toc=\"ncx\">\n");
        if (coverHref != null) {
            sb.append("    <itemref idref=\"cover\"/>\n");
        }
        if (tocHref != null) {
            sb.append("    <itemref idref=\"toc\"/>\n");
        }
        for (int i = 0; i < totalChapters; i++) {
            sb.append("    <itemref idref=\"ch_")
              .append(String.format("%05d", i + 1))
              .append("\"/>\n");
        }
        sb.append("  </spine>\n");
        sb.append("</package>\n");
        return sb.toString();
    }

    static String buildNcxXml(String bookId, String bookName, List<Chapter> chapters,
                               List<String> itemIds, int totalChapters) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE ncx PUBLIC \"-//NISO//DTD ncx 2005-1//EN\" \"http://www.dtd.org/NISO/2005/DTD/ncx-2005-1.dtd\">\n");
        sb.append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n");
        sb.append("  <head>\n");
        sb.append("    <meta name=\"dtb:uid\" content=\"").append(xmlEscape(bookId)).append("\"/>\n");
        sb.append("    <meta name=\"dtb:depth\" content=\"1\"/>\n");
        sb.append("    <meta name=\"dtb:totalPageCount\" content=\"0\"/>\n");
        sb.append("    <meta name=\"dtb:maxPageNumber\" content=\"0\"/>\n");
        sb.append("  </head>\n");
        sb.append("  <docTitle><text>").append(xmlEscape(bookName)).append("</text></docTitle>\n");
        sb.append("  <navMap>\n");

        for (int i = 0; i < totalChapters; i++) {
            var ch = i < chapters.size() ? chapters.get(i) : null;
            String title = ch != null ? ch.title() : "章节" + (i + 1);
            sb.append("    <navPoint id=\"navpoint-").append(i + 1).append("\" playOrder=\"").append(i + 1).append("\">\n");
            sb.append("      <navLabel><text>").append(xmlEscape(title)).append("</text></navLabel>\n");
            sb.append("      <content src=\"").append(itemIds.get(i)).append("\"/>\n");
            sb.append("    </navPoint>\n");
        }

        sb.append("  </navMap>\n");
        sb.append("</ncx>\n");
        return sb.toString();
    }

    static String buildChapterXhtml(String title, String body) {
        String escapedTitle = xmlEscape(title);
        // wrap body paragraphs if it has no block-level tags
        String wrapped = body;
        if (!body.contains("<p") && !body.contains("<div") && !body.contains("<h")) {
            var w = new StringBuilder();
            String[] lines = body.split("\n");
            for (String line : lines) {
                line = line.strip();
                if (!line.isEmpty()) {
                    w.append("<p>").append(line).append("</p>\n");
                }
            }
            wrapped = w.toString();
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
             + "<!DOCTYPE html>\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"zh\" xml:lang=\"zh\">\n"
             + "<head><title>" + escapedTitle + "</title>\n"
             + "<link href=\"stylesheet.css\" rel=\"stylesheet\" type=\"text/css\"/></head>\n"
             + "<body>\n"
             + "<h1>" + escapedTitle + "</h1>\n"
             + "<div class=\"content\">\n"
             + wrapped + "\n"
             + "</div>\n"
             + "</body>\n</html>\n";
    }

    static String buildTocXhtml(String bookName, List<Chapter> chapters, int totalChapters) {
        var ol = new StringBuilder();
        for (int i = 0; i < totalChapters; i++) {
            var ch = i < chapters.size() ? chapters.get(i) : null;
            String title = ch != null ? ch.title() : "章节" + (i + 1);
            String fid = String.format("chapter_%05d.xhtml", i + 1);
            ol.append("<li><a href=\"").append(fid).append("\">")
              .append(xmlEscape(title)).append("</a></li>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
             + "<!DOCTYPE html>\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"zh\" xml:lang=\"zh\">\n"
             + "<head><title>" + xmlEscape(bookName) + " - 目录</title>\n"
             + "<link href=\"stylesheet.css\" rel=\"stylesheet\" type=\"text/css\"/></head>\n"
             + "<body>\n"
             + "<h1>目录</h1>\n"
             + "<div class=\"content\">\n"
             + "<ol>\n" + ol + "</ol>\n"
             + "</div>\n"
             + "</body>\n</html>\n";
    }

    static String buildCoverXhtml(String bookName, String imagePath) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
             + "<!DOCTYPE html>\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"zh\" xml:lang=\"zh\">\n"
             + "<head><title>" + xmlEscape(bookName) + "</title>\n"
             + "<link href=\"stylesheet.css\" rel=\"stylesheet\" type=\"text/css\"/></head>\n"
             + "<body>\n"
             + "<div style=\"text-align:center;padding:2em;\">\n"
             + "<img src=\"" + xmlEscape(imagePath) + "\" alt=\"Cover\" style=\"max-width:100%;height:auto;\"/>\n"
             + "</div>\n"
             + "</body>\n</html>\n";
    }

    static String getCss() {
        return "body { font-family: serif; line-height: 1.8; padding: 1em; color: #000; }\n"
             + "p { text-indent: 2em; margin: 0.3em 0; line-height: 1.8; }\n"
             + "h1 { text-align: center; font-size: 1.4em; margin: 1em 0; }\n"
             + "img { max-width: 100%; height: auto; display: block; margin: 0.5em auto; }\n"
             + ".content { max-width: 35em; margin: 0 auto; }\n";
    }

    // ── HTTP + JSON helpers ─────────────────────────────

    static String fetchHtml(String url) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return resp.body();
    }

    static JsonValue fetchJson(String url) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return new JsonParser(resp.body()).parse();
    }

    static String fetchCoverUrl(String bookId) throws Exception {
        var html = fetchHtml("https://fanqienovel.com/page/" + bookId);
        // 1) Try ld+json first (gives real CDN-signed URL)
        var ldUrl = extractLdJsonCover(html);
        if (ldUrl != null) return ldUrl;
        // 2) Try __NEXT_DATA__ / __INITIAL_STATE__
        var state = parsePageState(bookId);
        if (state instanceof JsonValue.JsonObject jo) {
            var page = jo.get("page");
            if (page instanceof JsonValue.JsonObject p) {
                var url = p.str("thumbUrl");
                if (url != null && !url.isBlank()) return url;
                var thumbUri = p.str("thumbUri");
                if (thumbUri != null && !thumbUri.isBlank()) return thumbUri;
            }
        }
        return null;
    }

    static JsonValue parsePageState(String bookId) throws Exception {
        var html = fetchHtml("https://fanqienovel.com/page/" + bookId);
        // 1) Try __NEXT_DATA__ first (script tag with id="__NEXT_DATA__")
        var nextData = extractScriptJson(html, "__NEXT_DATA__");
        if (nextData != null) return nextData;
        // 2) Fallback to __INITIAL_STATE__
        return extractScriptJson(html, "__INITIAL_STATE__");
    }

    static JsonValue extractScriptJson(String html, String scriptId) {
        var marker = scriptId;
        int start = html.indexOf(marker);
        if (start < 0) return null;
        start = html.indexOf('{', start + marker.length());
        if (start < 0) return null;
        int depth = 0;
        int end = start;
        boolean inStr = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) { end = i + 1; break; }
            }
        }
        if (end <= start) return null;
        try {
            return new JsonParser(html.substring(start, end)).parse();
        } catch (Exception e) {
            return null;
        }
    }

    static String extractLdJsonCover(String html) {
        var m = Pattern.compile(
            "<script[^>]*type=\"application/ld\\+json\"[^>]*>([\\s\\S]*?)</script>",
            Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            try {
                var parsed = new JsonParser(m.group(1)).parse();
                if (parsed instanceof JsonValue.JsonObject jo) {
                    for (var key : List.of("image", "images")) {
                        var v = jo.map().get(key);
                        if (v instanceof JsonValue.JsonArray arr) {
                            for (var item : arr.list()) {
                                if (item instanceof JsonValue.JsonString s) {
                                    var url = s.value().trim();
                                    if (!url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://")))
                                        return url;
                                }
                            }
                        } else if (v instanceof JsonValue.JsonString s) {
                            var url = s.value().trim();
                            if (!url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://")))
                                return url;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    static String optStr(JsonValue val, String key) {
        if (val instanceof JsonValue.JsonObject obj) {
            var v = obj.map().get(key);
            if (v instanceof JsonValue.JsonString s) return s.value();
        }
        return "";
    }

    static String optStr(JsonValue val, String key, String def) {
        var s = optStr(val, key);
        return s.isEmpty() ? def : s;
    }

    static JsonValue.JsonArray optArray(JsonValue val, String key) {
        if (val instanceof JsonValue.JsonObject obj) {
            var v = obj.map().get(key);
            if (v instanceof JsonValue.JsonArray a) return a;
        }
        return new JsonValue.JsonArray(new ArrayList<>());
    }

    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    // ── Utility ─────────────────────────────────────────

    static String xmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    static String sha1Hex(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    static void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder())
                     .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static void updateMetaCache(Path cacheDir, String bookName,
                                 List<Chapter> chapters,
                                 int startIdx, int endIdx,
                                 ImageInfo coverImage) throws IOException {
        var meta = "{\n"
            + "  \"bookName\": \"" + jsonEscape(bookName) + "\",\n"
            + "  \"startIdx\": " + startIdx + ",\n"
            + "  \"endIdx\": " + endIdx + ",\n"
            + "  \"totalChapters\": " + chapters.size() + ",\n"
            + "  \"chapters\": [\n";
        for (int i = 0; i < chapters.size(); i++) {
            var ch = chapters.get(i);
            meta += "    {\"itemId\":\"" + jsonEscape(ch.itemId())
                 + "\",\"title\":\"" + jsonEscape(ch.title())
                 + "\",\"order\":" + ch.order() + "}";
            if (i < chapters.size() - 1) meta += ",";
            meta += "\n";
        }
        meta += "  ]\n";
        if (coverImage != null) {
            meta += "  ,\"cover\":\"" + jsonEscape(coverImage.filename()) + "\"\n"
                  + "  ,\"coverMime\":\"" + jsonEscape(coverImage.mime()) + "\"\n";
        }
        meta += "}\n";
        Files.writeString(cacheDir.resolve("meta.json"), meta, StandardCharsets.UTF_8);
    }

    static void saveDownloadCache(String bookId, String bookName,
                                   List<Chapter> chapters,
                                   int startIdx, int endIdx,
                                   ConcurrentHashMap<Integer, String> chapterContents,
                                   ConcurrentHashMap<String, ImageInfo> imageCache,
                                   ImageInfo coverImage) throws IOException {
        var dir = CACHE_DIR.resolve(bookId);
        deleteDirectory(dir);
        Files.createDirectories(dir);

        // meta.json
        var meta = "{\n"
            + "  \"bookName\": \"" + jsonEscape(bookName) + "\",\n"
            + "  \"startIdx\": " + startIdx + ",\n"
            + "  \"endIdx\": " + endIdx + ",\n"
            + "  \"totalChapters\": " + chapters.size() + ",\n"
            + "  \"chapters\": [\n";
        for (int i = 0; i < chapters.size(); i++) {
            var ch = chapters.get(i);
            meta += "    {\"itemId\":\"" + jsonEscape(ch.itemId())
                 + "\",\"title\":\"" + jsonEscape(ch.title())
                 + "\",\"order\":" + ch.order() + "}";
            if (i < chapters.size() - 1) meta += ",";
            meta += "\n";
        }
        meta += "  ]\n";
        if (coverImage != null) {
            meta += "  ,\"cover\":\"" + jsonEscape(coverImage.filename()) + "\"\n"
                  + "  ,\"coverMime\":\"" + jsonEscape(coverImage.mime()) + "\"\n";
        }
        meta += "}\n";
        Files.writeString(dir.resolve("meta.json"), meta, StandardCharsets.UTF_8);

        // chapters
        var chDir = dir.resolve("chapters");
        Files.createDirectories(chDir);
        for (int i = 0; i < chapters.size(); i++) {
            var content = chapterContents.get(i);
            if (content != null && !content.isEmpty()) {
                Files.writeString(chDir.resolve(String.valueOf(i)), content, StandardCharsets.UTF_8);
            }
        }

        // images
        var imgDir = dir.resolve("images");
        Files.createDirectories(imgDir);
        for (var entry : imageCache.entrySet()) {
            var info = entry.getValue();
            var fname = info.filename().replace("images/", "");
            Files.write(imgDir.resolve(fname), info.data());
        }

        // cover
        if (coverImage != null) {
            var fname = coverImage.filename().replace("images/", "");
            Files.write(dir.resolve("cover"), coverImage.data());
        }

        System.out.println("缓存已保存: " + dir.toAbsolutePath());
    }

    static List<Chapter> loadChaptersFromCache(String bookId) throws IOException {
        var dir = CACHE_DIR.resolve(bookId);
        var metaStr = Files.readString(dir.resolve("meta.json"), StandardCharsets.UTF_8);
        var parsed = new JsonParser(metaStr).parse();
        if (!(parsed instanceof JsonValue.JsonObject jo)) throw new IOException("meta.json格式错误");
        var chapters = new ArrayList<Chapter>();
        for (var v : jo.arr("chapters")) {
            if (v instanceof JsonValue.JsonObject ch) {
                chapters.add(new Chapter(
                    ch.str("itemId", ""),
                    ch.str("title", ""),
                    ch.integer("order", 0)
                ));
            }
        }
        return chapters;
    }

    static void generateFromCache(String outputPath, String bookId, boolean outputTxt,
                                   String bookName, List<Chapter> selected,
                                   int totalChapters) throws Exception {
        var dir = CACHE_DIR.resolve(bookId);
        var metaStr = Files.readString(dir.resolve("meta.json"), StandardCharsets.UTF_8);
        var parsed = new JsonParser(metaStr).parse();
        if (!(parsed instanceof JsonValue.JsonObject jo)) throw new IOException("meta.json格式错误");

        // Load chapter contents
        var chapterContents = new ConcurrentHashMap<Integer, String>();
        var chDir = dir.resolve("chapters");
        if (Files.exists(chDir)) {
            try (var files = Files.list(chDir)) {
                files.forEach(p -> {
                    var name = p.getFileName().toString();
                    try {
                        var idx = Integer.parseInt(name);
                        var content = Files.readString(p, StandardCharsets.UTF_8);
                        chapterContents.put(idx, content);
                    } catch (Exception ignored) {}
                });
            }
        }

        // Load images
        var imageCache = new ConcurrentHashMap<String, ImageInfo>();
        var imgDir = dir.resolve("images");
        if (Files.exists(imgDir)) {
            try (var files = Files.list(imgDir)) {
                files.forEach(p -> {
                    try {
                        var data = Files.readAllBytes(p);
                        var mimeAndExt = sniffMime(data);
                        if (!mimeAndExt[0].equals("application/octet-stream")) {
                            var filename = "images/" + p.getFileName().toString();
                            imageCache.put(filename, new ImageInfo(data, mimeAndExt[0], filename));
                        }
                    } catch (Exception ignored) {}
                });
            }
        }

        // Load cover
        ImageInfo coverImage = null;
        var coverFile = dir.resolve("cover");
        if (Files.exists(coverFile)) {
            var data = Files.readAllBytes(coverFile);
            var mimeAndExt = sniffMime(data);
            if (!mimeAndExt[0].equals("application/octet-stream")) {
                var filename = "images/cover" + mimeAndExt[1];
                coverImage = new ImageInfo(data, mimeAndExt[0], filename);
                imageCache.put("__cover__", coverImage);
            }
        }

        // Generate output
        generateOutput(outputPath, bookId, bookName, selected, totalChapters, outputTxt,
                       chapterContents, imageCache, coverImage);
    }

    static void generateOutput(String outputPath, String bookId, String bookName,
                                List<Chapter> selected, int totalChapters, boolean outputTxt,
                                ConcurrentHashMap<Integer, String> chapterContents,
                                ConcurrentHashMap<String, ImageInfo> imageCache,
                                ImageInfo coverImage) throws Exception {
        if (outputTxt) {
            var sb = new StringBuilder();
            for (int i = 0; i < totalChapters; i++) {
                var content = chapterContents.get(i);
                if (content != null && !content.isBlank()) {
                    sb.append(selected.get(i).title()).append("\n\n");
                    sb.append(content).append("\n\n\n");
                } else {
                    sb.append(selected.get(i).title()).append("\n\n[获取失败]\n\n\n");
                }
            }
            Files.writeString(Path.of(outputPath), sb.toString(), StandardCharsets.UTF_8);
        } else {
            System.out.println("\n生成 EPUB...");
            generateEpub(outputPath, bookId, bookName, selected, chapterContents, imageCache, coverImage, totalChapters);
        }
    }

}
