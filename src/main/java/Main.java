import java.io.IOException;
import java.net.URI;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Main {

    static final String SEARCH_URL = "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/";
    static final String DIRECTORY_URL = "https://fanqienovel.com/api/reader/directory/detail";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static final int CHAR_BASE = 0xE3E8;
    static final char[] CHARSET = {
        'D','在','主','特','家','军','然','表','场','4',
        '要','只','v','和','?','6','别','还','g','现',
        '儿','岁','?','?','此','象','月','3','出','战',
        '工','相','o','男','直','失','世','F','都','平',
        '文','什','V','O','将','真','T','那','当','?',
        '会','立','些','u','是','十','张','学','气','大',
        '爱','两','命','全','后','东','性','通','被','1',
        '它','乐','接','而','感','车','山','公','了','常',
        '以','何','可','话','先','p','i','叫','轻','M',
        '士','w','着','变','尔','快','l','个','说','少',
        '色','里','安','花','远','7','难','师','放','t',
        '报','认','面','道','S','?','克','地','度','I',
        '好','机','U','民','写','把','万','同','水','新',
        '没','书','电','吃','像','斯','5','为','y','白',
        '几','日','教','看','但','第','加','候','作','上',
        '拉','住','有','法','r','事','应','位','利','你',
        '声','身','国','问','马','女','他','Y','比','父',
        'x','A','H','N','s','X','边','美','对','所',
        '金','活','回','意','到','z','从','j','知','又',
        '内','因','点','Q','三','定','8','R','b','正',
        '或','夫','向','德','听','更','?','得','告','并',
        '本','q','过','记','L','让','打','f','人','就',
        '者','去','原','满','体','做','经','K','走','如',
        '孩','c','G','给','使','物','?','最','笑','部',
        '?','员','等','受','k','行','一','条','果','动',
        '光','门','头','见','往','自','解','成','处','天',
        '能','于','名','其','发','总','母','的','死','手',
        '入','路','进','心','来','h','时','力','多','开',
        '已','许','d','至','由','很','界','n','小','与',
        'Z','想','代','么','分','生','口','再','妈','望',
        '次','西','风','种','带','J','?','实','情','才',
        '这','?','E','我','神','格','长','觉','间','年',
        '眼','无','不','亲','关','结','0','友','信','下',
        '却','重','己','老','2','音','字','m','呢','明',
        '之','前','高','P','B','目','太','e','9','起',
        '稜','她','也','W','用','方','子','英','每','理',
        '便','四','数','期','中','C','外','样','a','海',
        '们','任'
    };

    static final int GROUP_SIZE = 10;
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
            System.err.println("  download <book_id> [-start=N] [-end=N] [--output=txt] [--run=N] [--book-name=NAME] [--redownload=true|false]");
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

        var sUrl = SEARCH_URL + "?q=" + URLEncoder.encode(bookId, StandardCharsets.UTF_8) + "&aid=1967";
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

        var dJson = fetchJson(DIRECTORY_URL + "?bookId=" + bookId);
        var dData = dJson instanceof JsonValue.JsonObject djo ? djo.map().get("data") : null;
        var chapters = extractChapters(dData);
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
    }

    static List<Chapter> extractChapters(JsonValue data) {
        var list = new ArrayList<Chapter>();
        if (data == null) return list;
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
        if (list.isEmpty()) {
            for (var key : new String[]{"chapterList", "chapter_list", "chapters", "items", "list"}) {
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
        return list;
    }

    // ── Download ────────────────────────────────────────

    static void handleDownload(List<String> args) throws Exception {
        if (args.isEmpty()) {
            System.err.println("用法: download <book_id> [-start=N] [-end=N] [--output=txt] [--run=N]");
            System.exit(1);
        }
        var bookId = args.getFirst();
        int start = 1, end = 0, batchSize = 5;
        boolean outputTxt = false;
        String cliBookName = null;

        boolean redownload = false;
        boolean redownloadExplicit = false;
        for (int i = 1; i < args.size(); i++) {
            var a = args.get(i);
            if (a.startsWith("-start=")) start = Integer.parseInt(a.substring(7));
            else if (a.startsWith("-end=")) end = Integer.parseInt(a.substring(5));
            else if (a.equals("--output=txt")) outputTxt = true;
            else if (a.startsWith("--run=")) {
                batchSize = Integer.parseInt(a.substring(6));
                if (batchSize < 1) batchSize = 1;
            }
            else if (a.startsWith("--book-name=")) cliBookName = a.substring(12);
            else if (a.startsWith("--redownload=")) {
                redownload = a.substring(13).equals("true");
                redownloadExplicit = true;
            }
        }

        System.out.println("获取目录...");
        var dJson = fetchJson(DIRECTORY_URL + "?bookId=" + bookId);
        var dData = dJson instanceof JsonValue.JsonObject djo ? djo.map().get("data") : null;
        var chapters = extractChapters(dData);
        if (chapters.isEmpty()) {
            System.err.println("获取目录失败");
            System.exit(1);
        }

        // fetch book name: CLI override → directory API → reader page → bookId
        String bookName = bookId;
        if (cliBookName != null) {
            bookName = cliBookName;
        } else {
            var bn = optStr(dData instanceof JsonValue.JsonObject djo2 ? djo2 : null, "bookName");
            if (!bn.isBlank()) {
                bookName = bn;
            } else if (!chapters.isEmpty()) {
                try {
                    bookName = fetchBookName(chapters.getFirst().itemId());
                } catch (Exception ignored) {}
            }
            if (bookName.equals(bookId)) {
                System.out.println("提示: 无法自动获取书名，可用 --book-name=名称 手动指定");
            }
        }

        // fetch cover image: /page/{bookId} HTML → __INITIAL_STATE__.page.thumbUrl
        ImageInfo coverImage = null;
        try {
            var coverUrl = fetchCoverUrl(bookId);
            if (coverUrl != null) {
                coverImage = downloadImage(coverUrl);
            }
        } catch (Exception ignored) {}
        // fallback: search API thumb
        if (coverImage == null) {
            try {
                var sUrl = SEARCH_URL + "?q=" + URLEncoder.encode(bookId, StandardCharsets.UTF_8) + "&aid=1967";
                var sJson = fetchJson(sUrl);
                var sData = sJson instanceof JsonValue.JsonObject sjo ? sjo.map().get("data") : null;
                var sRet = sData != null ? optArray(sData, "ret_data") : new JsonValue.JsonArray(new ArrayList<>());
                var match = sRet.list().stream()
                        .filter(v -> optStr(v, "book_id").equals(bookId))
                        .findFirst();
                if (match.isPresent()) {
                    var thumb = optStr(match.get(), "thumb");
                    if (!thumb.isBlank()) {
                        coverImage = downloadImage(thumb);
                    }
                }
            } catch (Exception ignored) {}
        }

        int endIdx = end <= 0 ? chapters.size() : Math.min(end, chapters.size());
        int startIdx = Math.max(1, start);
        if (startIdx > chapters.size()) {
            System.err.println("起始章节超出范围（共" + chapters.size() + "章）");
            System.exit(1);
        }
        var selected = chapters.subList(startIdx - 1, endIdx);
        int totalChapters = selected.size();
        System.out.println("下载 " + startIdx + " ~ " + endIdx + " 章（共" + totalChapters + "章）");

        var outDir = Path.of("output", bookId);
        Files.createDirectories(outDir);
        var ext = outputTxt ? ".txt" : ".epub";
        var outputPath = outDir.resolve(bookId + "_" + startIdx + "-" + endIdx + ext);

        // ----- Check cache -----
        var cacheDir = CACHE_DIR.resolve(bookId);
        boolean hasCache = Files.exists(cacheDir.resolve("meta.json"));

        if (hasCache && !redownload) {
            System.out.println("检测到缓存，使用 --redownload=true 重新下载，--redownload=false 使用缓存");
            System.out.println("（当前: 使用缓存）");
            generateFromCache(outputPath.toString(), bookId, outputTxt, bookName, selected, totalChapters);
            System.out.println("已保存: " + outputPath.toAbsolutePath());
            return;
        }
        if (hasCache && redownload) {
            System.out.println("重新下载（--redownload=true），删除缓存...");
            deleteDirectory(cacheDir);
        }

        // ----- Download -----
        System.out.println("并发组数: " + batchSize);

        var chapterContents = new ConcurrentHashMap<Integer, String>();
        var imageCache = new ConcurrentHashMap<String, ImageInfo>();
        var successCount = new AtomicInteger(0);
        var failCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();
        final boolean isTxtOutput = outputTxt;

        int numGroups = (totalChapters + GROUP_SIZE - 1) / GROUP_SIZE;
        var pool = Executors.newFixedThreadPool(batchSize);
        var futures = new ArrayList<Future<?>>();
        for (int g = 0; g < numGroups; g++) {
            final int groupIdx = g;
            int groupStart = g * GROUP_SIZE;
            int groupEnd = Math.min(groupStart + GROUP_SIZE, totalChapters);
            var groupChapters = new ArrayList<>(selected.subList(groupStart, groupEnd));
            futures.add(pool.submit(() -> {
                downloadGroup(groupIdx, groupChapters, groupStart, startIdx, isTxtOutput,
                              imageCache, chapterContents, successCount, failCount,
                              totalChapters, startTime);
            }));
        }

        for (var f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        pool.shutdown();

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("\n下载完成: " + successCount.get() + " 成功, " + failCount.get() + " 失败, 用时 " + elapsed + "s");

        // save cache
        saveDownloadCache(bookId, bookName, selected, startIdx, endIdx, chapterContents, imageCache, coverImage);

        // generate output from cache
        generateFromCache(outputPath.toString(), bookId, outputTxt, bookName, selected, totalChapters);
        System.out.println("已保存: " + outputPath.toAbsolutePath());

        // clean temp files
        var tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        try (var files = Files.list(tmpDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("fanqie_"))
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    static void downloadGroup(int groupIdx, List<Chapter> chapters, int offset, int startChapter,
                               boolean outputTxt, ConcurrentHashMap<String, ImageInfo> imageCache,
                               ConcurrentHashMap<Integer, String> chapterContents,
                               AtomicInteger successCount, AtomicInteger failCount,
                               int totalChapters, long startTime) {
        int a = startChapter + offset;
        int b = startChapter + offset + chapters.size() - 1;
        synchronized (System.out) {
            System.out.println("第 " + (groupIdx + 1) + " 组 (第 " + a + "-" + b + " 章): 开始下载");
        }
        for (int i = 0; i < chapters.size(); i++) {
            downloadOneChapter(chapters.get(i), offset + i, outputTxt, imageCache,
                               chapterContents, successCount, failCount);
        }
        synchronized (System.out) {
            System.out.println("第 " + (groupIdx + 1) + " 组 (第 " + a + "-" + b + " 章): 结束下载");
        }
    }

    static void downloadOneChapter(Chapter ch, int idx, boolean outputTxt,
                                     ConcurrentHashMap<String, ImageInfo> imageCache,
                                     ConcurrentHashMap<Integer, String> chapterContents,
                                     AtomicInteger successCount, AtomicInteger failCount) {
        String content = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                if (outputTxt) {
                    content = fetchChapterContentTxt(ch.itemId);
                } else {
                    content = fetchChapterContentEpub(ch.itemId, imageCache);
                }
                if (content != null && !content.isBlank() && !isErrorContent(content)) {
                    break;
                }
                content = null;
            } catch (Exception e) {
                content = null;
            }
            if (attempt < MAX_RETRIES - 1) {
                synchronized (System.out) {
                    System.out.println("\n  重试 " + ch.title() + " (" + (attempt + 1) + "/" + MAX_RETRIES + ")");
                }
                try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        chapterContents.put(idx, content != null ? content : "");
        if (content != null && !content.isEmpty()) {
            successCount.incrementAndGet();
        } else {
            failCount.incrementAndGet();
        }
    }

    // ── Content extraction (TXT path) ───────────────────

    static String fetchBookName(String itemId) throws Exception {
        var html = fetchHtml("https://fanqienovel.com/reader/" + itemId);
        var m = Pattern.compile("\"bookName\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        return m.find() ? m.group(1) : "";
    }

    static String fetchChapterContentTxt(String itemId) throws Exception {
        var html = fetchHtml("https://fanqienovel.com/reader/" + itemId);
        var encoded = extractEncodedText(html);
        if (encoded == null || encoded.isBlank()) return null;
        var decoded = decodeFanqieText(encoded);
        return decoded.replace("上一章", "");
    }

    static String extractEncodedText(String html) {
        var text = html.replaceAll("(?si)<script[^>]*>.*?</script>", "");
        text = text.replaceAll("(?si)<style[^>]*>.*?</style>", "");
        text = text.replaceAll("<br\\s*/?>", "\n");
        text = text.replaceAll("<p[^>]*>", "\n\n");
        text = text.replaceAll("<[^>]+>", "");

        var m = Pattern.compile("更新时间[：:]\\d{4}-\\d{2}-\\d{2}", Pattern.DOTALL).matcher(text);
        int markerEnd = m.find() ? m.end() : 0;

        int encodedStart = findContentStart(text, markerEnd);

        var endM = Pattern.compile("下一章|加书架|目录|下载领红包").matcher(text);
        int end = endM.find() ? endM.start() : text.length();

        if (encodedStart >= end) return text.substring(encodedStart).strip();

        var content = text.substring(encodedStart, end);
        content = decodeEntities(content);
        content = content.replaceAll("^[　\\s」」》》'\"＂\n]*", "");
        return content.strip();
    }

    static int findContentStart(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            int code = text.charAt(i);
            if (code >= CHAR_BASE) {
                return i;
            }
        }
        return from;
    }

    // ── Content extraction (EPUB path) ──────────────────

    static String fetchChapterContentEpub(String itemId,
                                           ConcurrentHashMap<String, ImageInfo> imageCache) throws Exception {
        var html = fetchHtml("https://fanqienovel.com/reader/" + itemId);
        var contentHtml = extractContentHtml(html);
        if (contentHtml == null || contentHtml.isBlank()) return null;

        var decoded = decodeHtmlContent(contentHtml);
        decoded = decoded.replace("上一章", "");
        decoded = processImages(decoded, imageCache);
        return decoded;
    }

    static String extractContentHtml(String html) {
        var text = html.replaceAll("(?si)<script[^>]*>.*?</script>", "");
        text = text.replaceAll("(?si)<style[^>]*>.*?</style>", "");

        // find content container by class
        var divM = Pattern.compile(
                "<div\\s+class\\s*=\\s*[\"']muye-reader-content[^\"']*[\"'][^>]*>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(text);
        int contentStart = divM.find() ? divM.end() : text.indexOf('<', 0);
        if (contentStart < 0) contentStart = 0;

        var endM = Pattern.compile("下一章|加书架|目录|下载领红包|muye-reader-btns").matcher(text);
        int end = endM.find() ? endM.start() : text.length();

        if (contentStart >= end) return text.substring(contentStart).strip();

        var raw = text.substring(contentStart, end);

        // find matching </div> for the outer content div by counting nesting
        int depth = 1, contentEnd = raw.length();
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '<' && i + 1 < raw.length()) {
                if (i + 6 < raw.length() && raw.substring(i, i + 6).equals("</div>")) {
                    if (--depth <= 0) { contentEnd = i + 6; break; }
                    i += 5;
                } else if (i + 4 < raw.length()
                        && raw.charAt(i + 1) == 'd' && raw.charAt(i + 2) == 'i' && raw.charAt(i + 3) == 'v'
                        && raw.charAt(i + 4) != '/') {
                    depth++;
                }
            }
        }

        return raw.substring(0, contentEnd).strip();
    }

    static String decodeHtmlContent(String html) {
        var sb = new StringBuilder();
        boolean inTag = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') {
                inTag = true;
                sb.append(c);
            } else if (c == '>') {
                inTag = false;
                sb.append(c);
            } else if (!inTag) {
                int code = c;
                if (code >= CHAR_BASE && code < CHAR_BASE + CHARSET.length) {
                    sb.append(CHARSET[code - CHAR_BASE]);
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String decodeEntities(String text) {
        text = text.replaceAll("&[lL][tT];", "<").replaceAll("&[gG][tT];", ">");
        text = text.replaceAll("&nbsp;", " ");
        text = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(text)
                .replaceAll(mr -> String.valueOf((char) Integer.parseInt(mr.group(1), 16)));
        text = Pattern.compile("&#(\\d+);").matcher(text)
                .replaceAll(mr -> String.valueOf((char) Integer.parseInt(mr.group(1))));
        text = text.replaceAll("&[aA][mM][pP];", "&");
        return text;
    }

    // ── Error detection ─────────────────────────────────

    static boolean isErrorContent(String content) {
        if (content.length() < 50) return true;
        var lower = content.toLowerCase();
        return lower.contains("验证码") || lower.contains("中间页")
            || lower.contains("captcha") || lower.contains("verify");
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

            // 7. Images
            for (var entry : imageCache.entrySet()) {
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
        // __INITIAL_STATE__.page.thumbUrl
        var m = Pattern.compile("\"thumbUrl\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        if (m.find()) {
            var url = m.group(1);
            // unescape JSON unicode sequences
            url = url.replace("\\u0026", "&").replace("\\u002F", "/");
            return url;
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

    static String decodeFanqieText(String encoded) {
        var sb = new StringBuilder();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            int code = (int) c;
            if (code >= CHAR_BASE && code < CHAR_BASE + CHARSET.length) {
                sb.append(CHARSET[code - CHAR_BASE]);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
