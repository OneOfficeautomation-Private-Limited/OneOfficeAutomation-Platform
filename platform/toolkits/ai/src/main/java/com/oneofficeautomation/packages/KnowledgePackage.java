package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Knowledge Q&A over a company's own documents (A5). Build index splits text, Markdown, HTML, CSV, JSON, XML, Word
 * (.docx, .doc, .rtf), PowerPoint and PDF files into passages and keeps them in one index file, with embeddings when a model is given. Search
 * finds the passages that best match a question: by words (BM25) always, and by meaning too when the index has
 * embeddings. Ask answers from those passages only, citing them, and says so when they do not hold the answer. The
 * index is a plain file, so it can be kept with the repository files and sent with the runs that need it.
 */
@OoaPackage(id = "ooa.knowledge", name = "Knowledge Q&A", version = "1.0.0",
        description = "Index your documents and answer questions from them, with the passages each answer comes from.")
public final class KnowledgePackage {
    static final String P = "KNOWLEDGE";
    static final int MAX_CHUNKS = 50_000;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> TEXT = Set.of("txt", "md", "markdown", "csv", "tsv", "json", "xml", "log", "html", "htm", "yaml", "yml");

    // ---------- reading and splitting documents ----------

    record Doc(String source, String text) {}
    record Chunk(String source, int seq, String text, float[] vector) {}

    static String read(Path f) throws IOException {
        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (ext.equals("docx")) return docx(Files.readAllBytes(f));
        if (ext.equals("pdf")) return pdf(f);
        if (Set.of("doc", "rtf", "ppt", "pptx").contains(ext)) return office(f);
        if (!TEXT.contains(ext)) throw new ActionException(P + ".UNSUPPORTED", "Cannot read " + f.getFileName() + " for the index",
            "Use text, Markdown, HTML, CSV, JSON, XML, .docx or PDF files, or pass the text with texts (for example from the PDF or OCR toolkit).");
        String s = Files.readString(f, StandardCharsets.UTF_8);
        return ext.startsWith("htm") || ext.equals("xml") ? untag(s) : s;
    }

    static String untag(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>|</h\\d>|</tr>", "\n")
            .replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
    }

    /** The text of a Word document: its paragraphs from word/document.xml. */
    static String docx(byte[] b) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(b))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; )
                if (e.getName().equals("word/document.xml"))
                    return untag(new String(z.readAllBytes(), StandardCharsets.UTF_8).replaceAll("</w:p>", "\n").replaceAll("<w:tab/>", "\t"));
        }
        throw new ActionException(P + ".UNSUPPORTED", "The file is not a Word document");
    }

    /** Old Word, RTF and PowerPoint files through the Office toolkits when the runner has them. */
    static String office(Path f) {
        try {
            return (String) Class.forName("com.oneofficeautomation.packages.office.DocumentText").getMethod("text", Path.class, boolean.class).invoke(null, f, true);
        } catch (ClassNotFoundException e) {
            throw new ActionException(P + ".UNSUPPORTED", "This runner cannot read " + f.getFileName(), "Pass the text with texts instead.");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new ActionException(P + ".UNREADABLE", "Cannot read " + f.getFileName());
        } catch (ReflectiveOperationException e) { throw new ActionException(P + ".UNREADABLE", "Cannot read " + f.getFileName() + ": " + e); }
    }

    /** PDF text through the PDF toolkit's library when the runner has it. */
    static String pdf(Path f) {
        try {
            Class<?> loader = Class.forName("org.apache.pdfbox.Loader");
            Object doc = loader.getMethod("loadPDF", java.io.File.class).invoke(null, f.toFile());
            try {
                Object stripper = Class.forName("org.apache.pdfbox.text.PDFTextStripper").getDeclaredConstructor().newInstance();
                return (String) stripper.getClass().getMethod("getText", Class.forName("org.apache.pdfbox.pdmodel.PDDocument")).invoke(stripper, doc);
            } finally { doc.getClass().getMethod("close").invoke(doc); }
        } catch (ClassNotFoundException e) {
            throw new ActionException(P + ".UNSUPPORTED", "This runner cannot read PDF files", "Extract the text with the PDF toolkit and pass it with texts.");
        } catch (ReflectiveOperationException e) {
            throw new ActionException(P + ".UNREADABLE", "Cannot read " + f.getFileName() + ": " + (e.getCause() == null ? e : e.getCause()).getMessage(), "Check the PDF is not password protected.");
        }
    }

    /** Passages of about size characters, cut at paragraph, sentence or word ends, overlapping by overlap characters. */
    static List<String> split(String text, int size, int overlap) {
        String t = text.replace("\r\n", "\n").replaceAll("[ \\t\\x0B\\f]+", " ").replaceAll("\n{3,}", "\n\n").strip();
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < t.length()) {
            int end = Math.min(t.length(), i + size);
            if (end < t.length()) {
                int cut = -1;
                for (String sep : new String[]{"\n\n", "\n", ". ", "? ", "! ", "; ", ", ", " "}) {
                    int c = t.lastIndexOf(sep, end);
                    if (c > i + size / 2) { cut = c + sep.length(); break; }
                }
                if (cut > 0) end = cut;
            }
            String part = t.substring(i, end).strip();
            if (!part.isEmpty()) out.add(part);
            if (end >= t.length()) break;
            int next = end - overlap;
            if (next <= i) next = end;
            // start the overlap at a word
            int sp = t.indexOf(' ', next);
            i = sp > 0 && sp < end ? sp + 1 : next;
        }
        return out;
    }

    static List<String> words(String s) {
        List<String> w = new ArrayList<>();
        var m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) w.add(m.group());
        return w;
    }

    // ---------- the index file ----------

    static String encode(float[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return Base64.getEncoder().encodeToString(b.array());
    }

    static float[] decode(String s) {
        ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.remaining() / 4];
        for (int i = 0; i < v.length; i++) v[i] = b.getFloat();
        return v;
    }

    record Index(Map<String, Object> model, List<Chunk> chunks) {}

    static void write(Path file, Index idx) throws IOException {
        List<Object> cs = new ArrayList<>();
        for (Chunk c : idx.chunks()) {
            Map<String, Object> m = new LinkedHashMap<>(Map.of("source", c.source(), "seq", c.seq(), "text", c.text()));
            if (c.vector() != null) m.put("vector", encode(c.vector()));
            cs.add(m);
        }
        Map<String, Object> doc = new LinkedHashMap<>(Map.of("format", "ooa-knowledge", "version", 1));
        if (idx.model() != null) doc.put("embedding_model", idx.model());
        doc.put("chunks", cs);
        byte[] json = JsonLite.write(doc).getBytes(StandardCharsets.UTF_8);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        if (file.toString().endsWith(".gz")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(buf)) { gz.write(json); }
            json = buf.toByteArray();
        }
        Files.write(file, json);
    }

    @SuppressWarnings("unchecked")
    static Index load(Path file) {
        if (!Files.isRegularFile(file)) throw new ActionException(P + ".NO_INDEX", "No knowledge index at " + file, "Make it with Build index first.");
        try (InputStream in = file.toString().endsWith(".gz") ? new GZIPInputStream(Files.newInputStream(file)) : Files.newInputStream(file)) {
            Map<String, Object> d = (Map<String, Object>) JsonLite.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!"ooa-knowledge".equals(d.get("format"))) throw new ActionException(P + ".BAD_INDEX", file + " is not a knowledge index");
            List<Chunk> cs = new ArrayList<>();
            for (Object o : (List<Object>) d.getOrDefault("chunks", List.of())) {
                Map<String, Object> c = (Map<String, Object>) o;
                cs.add(new Chunk(String.valueOf(c.get("source")), ((Number) c.get("seq")).intValue(), String.valueOf(c.get("text")),
                    c.get("vector") instanceof String v ? decode(v) : null));
            }
            return new Index((Map<String, Object>) d.get("embedding_model"), cs);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ActionException a) throw a;
            throw new ActionException(P + ".BAD_INDEX", "Cannot read the index " + file + ": " + e.getMessage(), "Build it again.");
        }
    }

    // ---------- search ----------

    record Hit(Chunk chunk, double score) {}

    /** BM25 over the passages' words, blended with cosine similarity when the query has a vector (70% meaning, 30% words). */
    static List<Hit> rank(List<Chunk> chunks, String query, float[] qv, int topK) {
        List<String> q = new ArrayList<>(new LinkedHashSet<>(words(query)));
        int n = chunks.size();
        if (n == 0) return List.of();
        List<Map<String, Integer>> tf = new ArrayList<>(n);
        int[] len = new int[n];
        Map<String, Integer> df = new HashMap<>();
        long total = 0;
        for (int i = 0; i < n; i++) {
            Map<String, Integer> f = new HashMap<>();
            List<String> w = words(chunks.get(i).text());
            for (String x : w) f.merge(x, 1, Integer::sum);
            for (String x : f.keySet()) df.merge(x, 1, Integer::sum);
            tf.add(f); len[i] = w.size(); total += w.size();
        }
        double avg = Math.max(1, (double) total / n), k1 = 1.2, b = 0.75;
        double[] lex = new double[n], sem = new double[n];
        double maxLex = 0;
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (String term : q) {
                Integer f = tf.get(i).get(term);
                if (f == null) continue;
                double idf = Math.log(1 + (n - df.get(term) + 0.5) / (df.get(term) + 0.5));
                s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * len[i] / avg));
            }
            lex[i] = s; maxLex = Math.max(maxLex, s);
            if (qv != null && chunks.get(i).vector() != null) sem[i] = cosine(qv, chunks.get(i).vector());
        }
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double l = maxLex > 0 ? lex[i] / maxLex : 0;
            double s = qv != null ? 0.7 * Math.max(0, sem[i]) + 0.3 * l : l;
            if (s > 0) hits.add(new Hit(chunks.get(i), s));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(topK, hits.size()));
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double d = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return na == 0 || nb == 0 ? 0 : d / Math.sqrt(na * nb);
    }

    // ---------- actions ----------

    static final String D_KB = "A knowledge base kept in One CommandCenter (AI > Knowledge bases), by name; in Studio without One CommandCenter, knowledge-bases/<name>.json.gz in the work folder.";

    static boolean blank(String s) { return s == null || s.isBlank(); }

    /**
     * Knowledge bases (G71): One CommandCenter keeps each base's index; a run publishes a new one (Build index) and
     * fetches it to search, kept in the work folder by its SHA-256 so it is downloaded again only when it changed.
     */
    @SuppressWarnings("unchecked")
    static java.util.function.Function<Map<String, Object>, Map<String, Object>> kbService(ActionContext ctx) {
        try { return ctx.service("knowledge_bases", java.util.function.Function.class); } catch (OoaException e) { return null; }
    }

    static String safeName(String kb) {
        String n = kb.strip();
        if (!n.matches("[A-Za-z0-9 _.-]{1,80}") || n.contains("..")) throw new ActionException(P + ".BAD_NAME", "The knowledge base name '" + kb + "' is not allowed");
        return n;
    }

    static void publish(ActionContext ctx, String kb, byte[] gz, int passages, int documents) throws IOException {
        var svc = kbService(ctx);
        if (svc == null) {
            Path p = ctx.workDir().resolve("knowledge-bases").resolve(safeName(kb) + ".json.gz");
            Files.createDirectories(p.getParent());
            Files.write(p, gz);
            return;
        }
        svc.apply(Map.of("op", "publish", "name", kb, "content", Base64.getEncoder().encodeToString(gz), "passages", passages, "documents", documents));
    }

    static Path indexPath(ActionContext ctx, String indexFile, String kb) {
        if (!blank(indexFile)) return ctx.resolvePath(indexFile);
        if (blank(kb)) throw new ActionException(P + ".NO_INDEX", "Give an index file or a knowledge base");
        var svc = kbService(ctx);
        if (svc == null) return ctx.workDir().resolve("knowledge-bases").resolve(safeName(kb) + ".json.gz");
        Path cache = ctx.workDir().resolve(".ooa-knowledge").resolve(safeName(kb) + ".json.gz"), shaFile = cache.resolveSibling(cache.getFileName() + ".sha256");
        try {
            String have = Files.exists(cache) && Files.exists(shaFile) ? Files.readString(shaFile).strip() : "";
            Map<String, Object> r = svc.apply(Map.of("op", "fetch", "name", kb, "sha256", have));
            if (!Boolean.TRUE.equals(r.get("not_modified"))) {
                Files.createDirectories(cache.getParent());
                Files.write(cache, Base64.getDecoder().decode(String.valueOf(r.get("content"))));
                Files.writeString(shaFile, String.valueOf(r.get("sha256")));
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot keep the knowledge base: " + e.getMessage(), null, true); }
        return cache;
    }

    /** A web page's text (G71): HTML without its tags, plain text as it is, a PDF read as a file. */
    static String fetch(ActionContext ctx, String url) throws IOException, InterruptedException {
        if (!url.matches("(?i)https?://\\S+")) throw new ActionException(P + ".BAD_URL", "Not a web address: " + url);
        var client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(20)).build();
        var res = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(60)).header("User-Agent", "OneOfficeAutomation-Knowledge").GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() >= 400) throw new ActionException(P + ".FETCH", "HTTP " + res.statusCode());
        String type = res.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (type.contains("pdf")) {
            Path tmp = Files.createTempFile("ooa-kb-", ".pdf");
            try { Files.write(tmp, res.body()); return pdf(tmp); } finally { Files.deleteIfExists(tmp); }
        }
        String text = new String(res.body(), StandardCharsets.UTF_8);
        return type.contains("html") || text.stripLeading().startsWith("<") ? untag(text) : text;
    }

    private static final String D_INDEX = "The index file, e.g. knowledge/policies.json (.json.gz keeps it smaller).";
    private static final String D_EMBED_CONN = "A saved model connection for embeddings, e.g. one for text-embedding-3-small; without one the index is searched by words only.";

    @SuppressWarnings("unchecked")
    @Action(id = "build_index", name = "Build index", category = "Knowledge", timeoutSeconds = 3600, capabilities = {"filesystem", "network", "ai"},
            description = "Read documents (files or folders) and texts, split them into passages and write a knowledge index, with embeddings when a model connection is given.",
            panel = "files, urls, texts, (Keep it in: A file = index_file / A knowledge base in One CommandCenter = knowledge_base), (Search by meaning too: A saved connection = connection / Set up here = provider, model, api_key, base_url) | chunk_size, overlap, mask_pii")
    @Output(name = "passages", type = OoaType.NUMBER, description = "How many passages the index holds.")
    @Output(name = "documents", type = OoaType.NUMBER, description = "How many documents were read.")
    @Output(name = "skipped", type = OoaType.LIST, description = "Files that could not be read, with the reason.")
    public Map<String, Object> buildIndex(ActionContext ctx,
            @Input(name = "files", type = OoaType.LIST, required = false, description = "Files and folders to read (folders with their sub-folders).") List<Object> files,
            @Input(name = "texts", type = OoaType.LIST, required = false, description = "Texts to add, each a dictionary with source and text (for example from the PDF or OCR toolkit).") List<Object> texts,
            @Input(name = "index_file", type = OoaType.FILE, required = false, visibleWhen = "knowledge_base=", description = D_INDEX) String indexFile,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = D_EMBED_CONN) String connection,
            @Input(name = "provider", type = OoaType.STRING, required = false, enumValues = {"openai", "azure_openai", "openai_compatible", "google_vertex", "aws_bedrock"}, description = "Embedding provider, when not using a saved connection.") String provider,
            @Input(name = "model", type = OoaType.STRING, required = false, example = "text-embedding-3-small", description = "Embedding model (the deployment for Azure OpenAI).") String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "chunk_size", type = OoaType.NUMBER, required = false, defaultValue = "1000", min = 200, max = 8000, description = "Passage length in characters.") int size,
            @Input(name = "overlap", type = OoaType.NUMBER, required = false, defaultValue = "150", min = 0, max = 2000, description = "Characters each passage shares with the one before, so no sentence is cut off from its context.") int overlap,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mask emails, phone and card numbers before they are sent for embedding (search by those then works by words only).") boolean mask,
            @Input(name = "knowledge_base", type = OoaType.STRING, required = false, visibleWhen = "index_file=", description = D_KB) String kb,
            @Input(name = "urls", type = OoaType.LIST, required = false, description = "Web pages to read (https:// addresses of intranet or public pages; HTML, text or PDF).") List<Object> urls) {
        if (blank(indexFile) && blank(kb)) throw new ActionException(P + ".NO_INDEX", "Say where the index goes: an index file or a knowledge base");
        List<Doc> docs = new ArrayList<>();
        List<Object> skipped = new ArrayList<>();
        for (Object o : urls == null ? List.of() : urls) {
            String u = Values.text(o).strip();
            try { docs.add(new Doc(u, fetch(ctx, u))); }
            catch (ActionException | IOException e) { skipped.add(Map.of("file", u, "reason", String.valueOf(e.getMessage()))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Stopped"); }
        }
        for (Object o : files == null ? List.of() : files) {
            Path p = ctx.resolvePath(Values.text(o));
            List<Path> list = new ArrayList<>();
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) { s.filter(Files::isRegularFile).sorted().forEach(list::add); }
                catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
            } else if (Files.isRegularFile(p)) list.add(p);
            else throw new ActionException("FILE.NOT_FOUND", "Not found: " + p, "Check the path.");
            for (Path f : list) {
                if (!blank(indexFile) && f.toAbsolutePath().equals(ctx.resolvePath(indexFile).toAbsolutePath())) continue;
                try { docs.add(new Doc(Files.isDirectory(p) ? p.relativize(f).toString().replace('\\', '/') : f.getFileName().toString(), read(f))); }
                catch (ActionException | IOException e) { skipped.add(Map.of("file", f.toString(), "reason", e.getMessage())); }
            }
        }
        for (Object o : texts == null ? List.of() : texts) {
            if (!(o instanceof Map<?, ?> m) || m.get("text") == null) throw new ActionException(P + ".BAD_INPUT", "Each item of texts is a dictionary with source and text");
            docs.add(new Doc(m.get("source") == null ? "text " + (docs.size() + 1) : Values.text(m.get("source")), Values.text(m.get("text"))));
        }
        if (overlap >= size) throw new ActionException(P + ".BAD_INPUT", "overlap must be smaller than chunk_size");
        List<Chunk> chunks = new ArrayList<>();
        for (Doc d : docs) {
            int seq = 0;
            for (String part : split(d.text(), size, overlap)) chunks.add(new Chunk(d.source(), ++seq, part, null));
        }
        if (chunks.isEmpty()) throw new ActionException(P + ".EMPTY", "There was no text to index", skipped.isEmpty() ? "Give files or texts." : "No file could be read: " + skipped);
        if (chunks.size() > MAX_CHUNKS) throw new ActionException(P + ".TOO_LARGE", "The documents make " + chunks.size() + " passages; an index holds at most " + MAX_CHUNKS, "Split them into several indexes, or raise chunk_size.");
        Map<String, Object> modelInfo = null;
        boolean embed = (connection != null && !connection.isBlank()) || (provider != null && !provider.isBlank());
        if (embed) {
            LlmClient.Config c = ModelConnections.config(ctx, connection, provider, model, key, base, 0, 0, mask);
            List<Chunk> with = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i += 64) {
                List<Chunk> batch = chunks.subList(i, Math.min(chunks.size(), i + 64));
                List<float[]> v = LlmClient.embed(ctx, c, batch.stream().map(Chunk::text).toList());
                for (int j = 0; j < batch.size(); j++) with.add(new Chunk(batch.get(j).source(), batch.get(j).seq(), batch.get(j).text(), v.get(j)));
                ctx.checkCancelled();
            }
            chunks = with;
            modelInfo = new LinkedHashMap<>();
            if (connection != null && !connection.isBlank()) modelInfo.put("connection", connection.strip());
            modelInfo.put("provider", c.provider()); modelInfo.put("model", c.model() == null ? "" : c.model());
            if (c.baseUrl() != null && !c.baseUrl().isBlank() && (connection == null || connection.isBlank())) modelInfo.put("base_url", c.baseUrl());
        }
        try {
            Index idx = new Index(modelInfo, chunks);
            if (!blank(indexFile)) write(ctx.resolvePath(indexFile), idx);
            if (!blank(kb)) {
                Path tmp = Files.createTempFile("ooa-kb-", ".json.gz");
                try {
                    write(tmp, idx);
                    publish(ctx, kb.strip(), Files.readAllBytes(tmp), chunks.size(), docs.size());
                } finally { Files.deleteIfExists(tmp); }
            }
        }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write the index: " + e.getMessage()); }
        return Map.of("passages", BigDecimal.valueOf(chunks.size()), "documents", BigDecimal.valueOf(docs.size()), "skipped", skipped);
    }

    /** The query's vector, when the index has embeddings and their model can be reached (its saved connection, or the given one). */
    static float[] queryVector(ActionContext ctx, Index idx, String query, String connection, Object key) {
        if (idx.model() == null) return null;
        String conn = connection != null && !connection.isBlank() ? connection : (String) idx.model().get("connection");
        String provider = (String) idx.model().get("provider");
        if ((conn == null || conn.isBlank()) && key == null && !"openai_compatible".equals(provider)) {
            ctx.warn("The index has embeddings but no saved connection or key to reach their model; searching by words only.");
            return null;
        }
        LlmClient.Config c = ModelConnections.config(ctx, conn, provider, (String) idx.model().get("model"), key, (String) idx.model().get("base_url"), 0, 0, false);
        return LlmClient.embed(ctx, c, List.of(query)).get(0);
    }

    static List<Object> results(List<Hit> hits) {
        List<Object> out = new ArrayList<>();
        int n = 0;
        for (Hit h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", BigDecimal.valueOf(++n)); m.put("source", h.chunk().source()); m.put("passage", BigDecimal.valueOf(h.chunk().seq()));
            m.put("score", BigDecimal.valueOf(h.score()).setScale(4, java.math.RoundingMode.HALF_UP)); m.put("text", h.chunk().text());
            out.add(m);
        }
        return out;
    }

    @Action(id = "search", name = "Search", category = "Knowledge", idempotent = true, timeoutSeconds = 120, capabilities = {"filesystem", "network", "ai"},
            description = "Find the passages of a knowledge index that best answer a question, by words and, when the index has embeddings, by meaning.",
            panel = "(Index: A file = index_file / A knowledge base = knowledge_base), query, top_k | connection, api_key")
    @Output(name = "results", type = OoaType.LIST, description = "Best passages first: n, source, passage, score (0 to 1), text.")
    public Map<String, Object> search(ActionContext ctx,
            @Input(name = "index_file", type = OoaType.FILE, required = false, visibleWhen = "knowledge_base=", description = D_INDEX) String indexFile,
            @Input(name = "query", type = OoaType.STRING, description = "The question or words to look for.") String query,
            @Input(name = "top_k", type = OoaType.NUMBER, required = false, defaultValue = "5", min = 1, max = 50, description = "How many passages to return.") int topK,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = "The embedding connection, when not the one the index was built with.") String connection,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = "The embedding API key, for an index built without a saved connection.") Object key,
            @Input(name = "knowledge_base", type = OoaType.STRING, required = false, visibleWhen = "index_file=", description = D_KB) String kb) {
        Index idx = load(indexPath(ctx, indexFile, kb));
        return Map.of("results", results(rank(idx.chunks(), query, queryVector(ctx, idx, query, connection, key), topK)));
    }

    static final String ANSWER_RULES = "Answer the question using only the numbered passages. Cite the passages you used like [1] or [2][3]. "
        + "If the passages do not contain the answer, say that the documents do not say, and do not guess. Answer in the language of the question.";

    @Action(id = "ask", name = "Ask", category = "Knowledge", timeoutSeconds = 600, capabilities = {"filesystem", "network", "ai"},
            description = "Answer a question from a knowledge index: the best passages go to the model, which answers from them only and cites them.",
            panel = "(Index: A file = index_file / A knowledge base = knowledge_base), question, top_k, (Model to answer with: A saved connection = connection / Set up here = provider, model, api_key, base_url) | embedding_connection, max_tokens, mask_pii, timeout_seconds")
    @Output(name = "answer", type = OoaType.STRING, description = "The answer, citing passages like [1].")
    @Output(name = "sources", type = OoaType.LIST, description = "The passages given to the model: n, source, passage, score, text.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "False when no passage matched, so the model was not asked.")
    @Output(name = "input_tokens", type = OoaType.NUMBER, description = "Tokens in the request.")
    @Output(name = "output_tokens", type = OoaType.NUMBER, description = "Tokens in the answer.")
    public Map<String, Object> ask(ActionContext ctx,
            @Input(name = "index_file", type = OoaType.FILE, required = false, visibleWhen = "knowledge_base=", description = D_INDEX) String indexFile,
            @Input(name = "question", type = OoaType.STRING, description = "The question.") String question,
            @Input(name = "top_k", type = OoaType.NUMBER, required = false, defaultValue = "5", min = 1, max = 20, description = "How many passages the model gets.") int topK,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = GenAiPackage.D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, required = false, description = GenAiPackage.D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "embedding_connection", type = OoaType.STRING, required = false, description = "The embedding connection, when not the one the index was built with.") String embConnection,
            @Input(name = "max_tokens", type = OoaType.NUMBER, required = false, defaultValue = "1024", description = "Maximum answer length in tokens.") int max,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = GenAiPackage.D_MASK) boolean mask,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = GenAiPackage.D_TIMEOUT) int timeout,
            @Input(name = "knowledge_base", type = OoaType.STRING, required = false, visibleWhen = "index_file=", description = D_KB) String kb) {
        Index idx = load(indexPath(ctx, indexFile, kb));
        List<Hit> hits = rank(idx.chunks(), question, queryVector(ctx, idx, question, embConnection, null), topK);
        if (hits.isEmpty())
            return Out.of("answer", "The documents do not say.", "sources", List.of(), "found", false, "input_tokens", BigDecimal.ZERO, "output_tokens", BigDecimal.ZERO);
        StringBuilder passages = new StringBuilder();
        int n = 0;
        for (Hit h : hits) passages.append('[').append(++n).append("] (").append(h.chunk().source()).append(")\n").append(h.chunk().text()).append("\n\n");
        LlmClient.Config c = ModelConnections.config(ctx, connection, provider, model, key, base, 0, max, mask)
            .with(LlmClient.Options.of(null, null, null, null, null, null, null, null, timeout));
        LlmClient.Reply r = LlmClient.chat(ctx, c, List.of(Map.of("role", "system", "content", ANSWER_RULES),
            Map.of("role", "user", "content", "Passages:\n\n" + passages + "Question: " + question)), null, false, "knowledge_ask");
        if (r.text() == null) throw new ActionException("AI.EMPTY", "The model returned no text", "Retry or raise max_tokens.", true);
        return Out.of("answer", r.text(), "sources", results(hits), "found", true,
            "input_tokens", BigDecimal.valueOf(r.inTokens()), "output_tokens", BigDecimal.valueOf(r.outTokens()));
    }
}
