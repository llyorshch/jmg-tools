import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal HTTP + JSON support (no reflection, so it works unchanged in native images). */
final class Net {
    private Net() {}

    private static HttpClient client;

    static synchronized HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
        }
        return client;
    }

    static String get(String url) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "tzu-timezone-utilities/1.0 (+https://github.com/llyorshch/jmg-tools)")
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 204) return "[]";
            if (res.statusCode() / 100 != 2) throw new IOException("HTTP " + res.statusCode() + " from " + url);
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static Path cacheDir() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("tzu");
    }

    /** GET with an on-disk cache; stale entries are used when the network is unavailable. */
    static String cachedGet(String url, String cacheName, Duration ttl, boolean offline) throws IOException {
        Path f = cacheDir().resolve(cacheName);
        boolean fresh = Files.isRegularFile(f)
                && Files.getLastModifiedTime(f).toInstant().isAfter(Instant.now().minus(ttl));
        if (fresh || offline && Files.isRegularFile(f)) return Files.readString(f, StandardCharsets.UTF_8);
        if (offline) throw new IOException("not cached (offline): " + cacheName);
        try {
            String body = get(url);
            Files.createDirectories(f.getParent());
            Files.writeString(f, body, StandardCharsets.UTF_8);
            return body;
        } catch (IOException e) {
            if (Files.isRegularFile(f)) return Files.readString(f, StandardCharsets.UTF_8);
            throw e;
        }
    }

    // ------------------------------------------------------------------ JSON
    /** Parses JSON into Map / List / String / Double / Boolean / null. */
    static Object json(String s) {
        return new Parser(s).value();
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                m.put(k, value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("bad JSON at " + i);
            return Double.parseDouble(s.substring(start, i));
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) throw new IllegalArgumentException("bad JSON literal at " + i);
            i += word.length();
            return v;
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected '" + c + "' at " + i);
            i++;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) {
        if (o instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object x : l) if (x instanceof Map) out.add((Map<String, Object>) x);
            return out;
        }
        return List.of();
    }

    static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v instanceof Double d && d == Math.floor(d) ? String.valueOf(d.longValue()) : v.toString();
    }
}
