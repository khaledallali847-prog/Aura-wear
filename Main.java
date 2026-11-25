import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Aura Wear backend: JDK only (no dependencies). Serves the storefront and a small JSON API. */
public class Main {
    // collection -> (document id -> raw JSON object text)
    static final Map<String, Map<String, String>> DB = new ConcurrentHashMap<>();
    static final Set<String> TOKENS = ConcurrentHashMap.newKeySet();
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    static final Pattern PASS = Pattern.compile("\"password\"\\s*:\\s*\"([^\"]*)\"");
    static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "");
    static final String SIZES = "[\"S\",\"M\",\"L\",\"XL\",\"2XL\",\"3XL\"]";
    static final String COLORS = "[{\"name\":\"أسود\",\"hex\":\"#141414\"},{\"name\":\"بيج\",\"hex\":\"#D9C49A\"},{\"name\":\"بني\",\"hex\":\"#7A5A2E\"},{\"name\":\"رمادي\",\"hex\":\"#8A8A8A\"}]";
    static Path dataFile;

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(System.getenv().getOrDefault("DATA_DIR", "data"));
        Files.createDirectories(dir);
        dataFile = dir.resolve("db.txt");
        load();
        if (DB.computeIfAbsent("products", k -> new ConcurrentHashMap<>()).isEmpty()) {
            seed();
            save();
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", Main::handle);
        server.start();
        System.out.println("Aura Wear running on port " + port);
    }

    static void seed() {
        put("products", "p1", prod("قميص كتان", 3500, "#D9C49A", "#7A5A2E", 0));
        put("products", "p2", prod("فستان حرير", 6800, "#C9A9A0", "#5E3E39", 1));
        put("products", "p3", prod("بدلة رجالية", 12500, "#7C8474", "#2A2F24", 2));
        put("products", "p4", prod("عباءة مطرزة", 9000, "#B8A27A", "#4E3D22", 3));
    }

    static String prod(String name, int price, String from, String to, int order) {
        return "{\"name\":\"" + name + "\",\"price\":" + price + ",\"img\":\"\",\"from\":\"" + from
            + "\",\"to\":\"" + to + "\",\"order\":" + order + ",\"colors\":" + COLORS + ",\"sizes\":" + SIZES + "}";
    }

    static void put(String col, String id, String raw) {
        DB.computeIfAbsent(col, k -> new ConcurrentHashMap<>()).put(id, raw);
    }

    static void handle(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                send(ex, 200, "text/html; charset=utf-8", Files.readAllBytes(Paths.get("static", "index.html")));
                return;
            }
            if (!path.startsWith("/api/")) { json(ex, 404, "{}"); return; }
            String body = new String(ex.getRequestBody().readNBytes(8_000_000), StandardCharsets.UTF_8);
            String[] parts = path.substring(5).split("/");

            if (parts.length == 1 && parts[0].equals("login") && method.equals("POST")) { login(ex, body); return; }
            if (parts.length < 1 || parts.length > 2) { json(ex, 404, "{}"); return; }
            String col = parts[0];
            if (!col.equals("products") && !col.equals("orders")) { json(ex, 404, "{}"); return; }
            Map<String, String> store = DB.computeIfAbsent(col, k -> new ConcurrentHashMap<>());
            boolean admin = isAdmin(ex);

            if (method.equals("GET") && parts.length == 1) {
                if (col.equals("orders") && !admin) { json(ex, 401, "{}"); return; }
                json(ex, 200, list(store));
                return;
            }
            if (parts.length == 2) {
                String id = parts[1];
                if (!ID.matcher(id).matches()) { json(ex, 400, "{}"); return; }
                if (method.equals("GET")) {
                    String raw = store.get(id);
                    if (raw == null) json(ex, 404, "{}");
                    else json(ex, 200, withId(id, raw));
                    return;
                }
                if (method.equals("PUT")) {
                    if (col.equals("products") && !admin) { json(ex, 401, "{}"); return; }
                    String raw = body.trim();
                    if (!raw.startsWith("{") || !raw.endsWith("}")) { json(ex, 400, "{}"); return; }
                    store.put(id, raw);
                    save();
                    json(ex, 200, "{\"ok\":true}");
                    return;
                }
            }
            json(ex, 405, "{}");
        } catch (Exception e) {
            try { json(ex, 500, "{\"error\":\"server\"}"); } catch (IOException ignored) { }
        }
    }

    static boolean isAdmin(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") && TOKENS.contains(h.substring(7));
    }

    static void login(HttpExchange ex, String body) throws IOException {
        Matcher m = PASS.matcher(body);
        if (!ADMIN_PASSWORD.isEmpty() && m.find() && m.group(1).equals(ADMIN_PASSWORD)) {
            String token = UUID.randomUUID().toString();
            TOKENS.add(token);
            json(ex, 200, "{\"token\":\"" + token + "\"}");
        } else {
            json(ex, 401, "{}");
        }
    }

    static String list(Map<String, String> store) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String> e : store.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(withId(e.getKey(), e.getValue()));
        }
        return sb.append(']').toString();
    }

    static String withId(String id, String raw) {
        String r = raw.trim();
        if (r.equals("{}")) return "{\"id\":\"" + id + "\"}";
        return "{\"id\":\"" + id + "\"," + r.substring(1);
    }

    static void json(HttpExchange ex, int code, String s) throws IOException {
        send(ex, code, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int code, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static synchronized void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> col : DB.entrySet())
            for (Map.Entry<String, String> doc : col.getValue().entrySet())
                sb.append(col.getKey()).append('\t').append(doc.getKey()).append('\t').append(doc.getValue()).append('\n');
        Path tmp = dataFile.resolveSibling("db.tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
    }

    static void load() throws IOException {
        if (!Files.exists(dataFile)) return;
        for (String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", 3);
            if (p.length == 3) put(p[0], p[1], p[2]);
        }
    }
}
