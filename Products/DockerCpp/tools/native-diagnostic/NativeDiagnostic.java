import com.rsplwe.esurfing.*;
import com.rsplwe.esurfing.hook.Session;
import com.rsplwe.esurfing.hook.AndroidMock;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.HookStatus;
import com.github.unidbg.hook.HookContext;
import com.github.unidbg.hook.ReplaceCallback;
import com.github.unidbg.hook.hookzz.HookZz;
import com.rsplwe.esurfing.network.NetClientKt;
import com.rsplwe.esurfing.utils.*;
import okhttp3.*;
import org.apache.log4j.LogManager;
import org.apache.log4j.varia.NullAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 独立进程：仅请求初始化模块；不读取账户、不申请ticket、不登录、不注销。 */
public final class NativeDiagnostic {
    static final String ZERO = "00000000-0000-0000-0000-000000000000";
    static final int LIMIT = 1024 * 1024;
    static String hash(byte[] data, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
    }
    static String candidate(byte[] data) {
        if (data.length < 5 || data.length > LIMIT) return "UNPARSED";
        int pos = 3;
        String[] values = new String[2];
        for (int i = 0; i < 2; i++) {
            if (pos >= data.length) return "UNPARSED";
            int length = Byte.toUnsignedInt(data[pos++]);
            if (length > data.length - pos) return "UNPARSED";
            values[i] = new String(data, pos, length, StandardCharsets.US_ASCII);
            pos += length;
        }
        for (int i : new int[]{1, 0})
            if (values[i].matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
                return values[i].toUpperCase(Locale.ROOT);
        return "UNPARSED";
    }
    static byte[] bounded(java.io.InputStream input) throws Exception {
        byte[] bytes = input.readNBytes(LIMIT + 1);
        if (bytes.length < 5 || bytes.length > LIMIT) throw new Exception("MODULE_SIZE_INVALID");
        return bytes;
    }
    static long little32(byte[] data, int offset) {
        return Byte.toUnsignedLong(data[offset]) | (Byte.toUnsignedLong(data[offset+1]) << 8) |
            (Byte.toUnsignedLong(data[offset+2]) << 16) | (Byte.toUnsignedLong(data[offset+3]) << 24);
    }
    /** 仅记录固定布局的非秘密描述信息；未知格式不猜测偏移，不运行解码器。 */
    static void describeContainer(byte[] data, Properties report) {
        report.setProperty("container_header_status", "UNPARSED");
        if (data.length < 5 || data.length > LIMIT || data[0]!='0' || data[1]!='0' || data[2]!='1') return;
        int position=3;
        for (int field=0;field<2;field++) {
            if(position>=data.length)return;
            int count=Byte.toUnsignedInt(data[position++]);
            if(count>data.length-position)return;
            position+=count;
        }
        if(data.length-position<9)return;
        long descriptor=little32(data,position+5);
        report.setProperty("container_format", "001");
        report.setProperty("container_header_status", "001_TWO_LENGTH_STRINGS_AND_DESCRIPTOR");
        report.setProperty("container_encoding_type", Long.toString(descriptor >>> 28));
        report.setProperty("container_declared_output_bytes", Long.toString(descriptor & 0x0fffffffL));
        report.setProperty("container_dictionary_bytes", Long.toString(little32(data,position+1)));
        report.setProperty("container_payload_bytes", Integer.toString(data.length-position-9));
    }
    static String localUrl(Path file) throws Exception {
        if (Files.size(file) > 8192) throw new Exception("URL_FILE_TOO_LARGE");
        String value = Files.readString(file).strip();
        HttpUrl url = HttpUrl.get(value);
        String host = url.host();
        if (!url.username().isEmpty() || !url.password().isEmpty() || url.fragment() != null ||
            host.equals("localhost") || host.equals("::1") || host.startsWith("127.") ||
            host.startsWith("169.254.") || host.equals("0.0.0.0")) throw new Exception("URL_REJECTED");
        return url.toString();
    }
    static byte[] fetchModule(OkHttpClient http, String ticketUrl) throws Exception {
        if (!HttpUrl.get(ticketUrl).encodedPath().endsWith("/ticket.cgi")) throw new Exception("NOT_TICKET_ENDPOINT");
        byte[] data = ZERO.getBytes(StandardCharsets.UTF_8);
        Request request = new Request.Builder().url(ticketUrl)
            .header("User-Agent", Constants.USER_AGENT).header("Accept", Constants.REQUEST_ACCEPT)
            .header("Algo-ID", ZERO).header("Client-ID", "11111111-1111-4111-8111-111111111111")
            .header("CDC-Checksum", hash(data, "MD5"))
            .post(RequestBody.create(data, MediaType.get("application/x-www-form-urlencoded"))).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new Exception("MODULE_HTTP_FAILED");
            try (var stream = response.body().byteStream()) { return bounded(stream); }
        }
    }
    static String ticketUrl(Path input, OkHttpClient http) throws Exception {
        if (Files.isRegularFile(input.resolve("ticket-url.txt"))) return localUrl(input.resolve("ticket-url.txt"));
        String portal = null, body = null, userIp = "", acIp = "";
        if (Files.isRegularFile(input.resolve("portal-url.txt"))) portal = localUrl(input.resolve("portal-url.txt"));
        else {
            var probe = NetworkConnectivityKt.checkConnectivity(
                RuntimeConfig.INSTANCE.getNetworkCheckUrls(), http);
            if (probe.getStatus() == ConnectivityStatus.SUCCESS) throw new Exception("NEED_PORTAL_WHILE_ONLINE");
            portal = probe.getPortalUrl(); body = probe.getPortalBody();
            userIp = Objects.toString(probe.getUserIp(), ""); acIp = Objects.toString(probe.getAcIp(), "");
            if (portal == null && body == null) throw new Exception("PORTAL_NOT_DISCOVERED");
        }
        return PortalConfiguration.INSTANCE.discover(portal, body, userIp, acIp, http).getTicketUrl();
    }
    static void prepareOracle(Path output, Properties report) throws Exception {
        // Session only loads the module and calls native aid/enc/dec. It has no HTTP client.
        States.INSTANCE.setClientId("11111111-1111-4111-8111-111111111111");
        States.INSTANCE.setMacAddress("02:00:00:00:00:01");
        States.INSTANCE.setUserIp("192.0.2.1"); States.INSTANCE.setAcIp("192.0.2.2");
        Files.createDirectories(States.INSTANCE.getRootDir().toPath());
        String backend = System.getenv().getOrDefault("DIAGNOSTIC_BACKEND", "unicorn2");
        if (!backend.equals("unicorn2") && !backend.equals("dynarmic")) throw new Exception("INVALID_BACKEND");
        States.INSTANCE.setUseDynarmic(backend.equals("dynarmic"));
        report.setProperty("native_backend", backend);
        report.setProperty("stage", "NATIVE_SESSION_LOADING");
        save(output, report);
        String fixedEpoch = System.getenv("DIAGNOSTIC_EPOCH_SECONDS");
        if (fixedEpoch != null) {
            long seconds = Long.parseLong(fixedEpoch);
            if (seconds < 0 || seconds > Integer.MAX_VALUE) throw new Exception("FIXED_CLOCK_INVALID");
            var emulator = AndroidMock.Companion.getInstance().getEmulator();
            var libc = emulator.getMemory().findModule("libc.so");
            var symbol = libc == null ? null : libc.findSymbolByName("gettimeofday");
            if (symbol == null) throw new Exception("FIXED_CLOCK_UNAVAILABLE");
            HookZz.getInstance(emulator).replace(symbol, new ReplaceCallback() {
                @Override public HookStatus onCall(Emulator<?> current, HookContext context, long origin) {
                    var tv = context.getPointerArg(0);
                    var tz = context.getPointerArg(1);
                    if (tv != null) { tv.setInt(0, (int)seconds); tv.setInt(4, 0); }
                    if (tz != null) { tz.setInt(0, 0); tz.setInt(4, 0); }
                    return HookStatus.LR(current, 0);
                }
            });
            report.setProperty("fixed_epoch_seconds", Long.toString(seconds));
            report.setProperty("fixed_microseconds", "0");
            save(output, report);
        }
    }
    static void oracle(byte[] module, Path output, Properties report) throws Exception {
        prepareOracle(output, report);
        Session session = new Session(module);
        try {
            String aid = States.INSTANCE.getAlgoId();
            if (!aid.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
                throw new Exception("NATIVE_AID_INVALID");
            report.setProperty("native_aid", aid.toUpperCase(Locale.ROOT));
            report.setProperty("stage", "NATIVE_AID_COLLECTED_ENC_PENDING");
            save(output, report);
            try (var stream = NativeDiagnostic.class.getResourceAsStream("/libdaproxy.so")) {
                if (stream == null) throw new Exception("LIBRARY_MISSING");
                report.setProperty("native_library_sha256", hash(stream.readAllBytes(), "SHA-256"));
            }
            StringBuilder vectors = new StringBuilder("sequence\tinput_utf8_hex\tnative_enc_utf8_hex\n");
            int sequence = 0;
            for (int size : new int[]{1,7,8,9,15,16,17,31,32,33}) {
                String value = "a".repeat(size);
                vectors.append(++sequence).append('\t').append(HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)))
                    .append('\t').append(HexFormat.of().formatHex(session.encrypt(value).getBytes(StandardCharsets.UTF_8))).append('\n');
            }
            String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><request><client-id>11111111-1111-4111-8111-111111111111</client-id><ipv4>192.0.2.1</ipv4><local-time>2026-01-01 00:00:00</local-time><test>中文 &amp; &lt;test&gt;</test></request>";
            vectors.append(++sequence).append('\t').append(HexFormat.of().formatHex(xml.getBytes(StandardCharsets.UTF_8)))
                .append('\t').append(HexFormat.of().formatHex(session.encrypt(xml).getBytes(StandardCharsets.UTF_8))).append('\n');
            Files.writeString(output.resolve("native-enc.tsv"), vectors, StandardOpenOption.CREATE_NEW);
            report.setProperty("enc_vectors", Integer.toString(sequence));
            report.setProperty("response_direction", "NOT_COLLECTED_NO_SERVER_RESPONSE_OR_LOGIN");
        } finally { session.free(); }
    }
    static final Set<String> SAFE = Set.of("MODULE_SIZE_INVALID", "MODULE_HTTP_FAILED", "URL_FILE_TOO_LARGE",
        "URL_REJECTED", "NOT_TICKET_ENDPOINT", "NEED_PORTAL_WHILE_ONLINE", "PORTAL_NOT_DISCOVERED", "NATIVE_AID_INVALID", "LIBRARY_MISSING");
    public static void main(String[] args) {
        // Hard wall time includes module loading/emulation; no retry or background service.
        Thread watchdog = new Thread(() -> { try { Thread.sleep(90000); Runtime.getRuntime().halt(124); } catch (InterruptedException ignored) {} });
        watchdog.setDaemon(true); watchdog.start();
        LogManager.resetConfiguration(); LogManager.getRootLogger().addAppender(new NullAppender());
        Properties report = new Properties();
        report.setProperty("tool_version", "3"); report.setProperty("utc", Instant.now().toString());
        report.setProperty("channel", "Android32/2082"); report.setProperty("login_attempted", "false");
        Path output = Path.of(System.getenv().getOrDefault("DIAGNOSTIC_OUTPUT", "/output"));
        Path input = Path.of(System.getenv().getOrDefault("DIAGNOSTIC_INPUT", "/input"));
        if (Files.exists(output.resolve("report.properties"))) {
            System.out.println("OUTPUT_EXISTS: use a new output directory"); System.exit(2);
        }
        int code = 1;
        try {
            if (args.length != 1 || !Set.of("collect", "offline", "collect-module", "offline-header").contains(args[0])) throw new Exception("INVALID_ARGUMENTS");
            boolean offline=args[0].equals("offline") || args[0].equals("offline-header");
            boolean moduleOnly=args[0].equals("collect-module") || args[0].equals("offline-header");
            report.setProperty("native_module_executed", "false");
            report.setProperty("diagnostic_mode", args[0]);
            if (!offline && System.getenv("DIAGNOSTIC_EPOCH_SECONDS") != null)
                throw new Exception("FIXED_CLOCK_OFFLINE_ONLY");
            byte[] module;
            if (offline) {
                try (var stream = Files.newInputStream(input.resolve("module.bin"))) { module = bounded(stream); }
            } else {
                OkHttpClient http = NetClientKt.createProbeHttpClient(10).newBuilder()
                    .followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).build();
                try { module = fetchModule(http, ticketUrl(input, http)); }
                finally { http.dispatcher().cancelAll(); http.connectionPool().evictAll(); http.dispatcher().executorService().shutdownNow(); }
            }
            Files.write(output.resolve("module.bin"), module, StandardOpenOption.CREATE_NEW);
            report.setProperty("module_bytes", Integer.toString(module.length));
            report.setProperty("module_sha256", hash(module, "SHA-256"));
            report.setProperty("header_candidate", candidate(module));
            describeContainer(module, report);
            report.setProperty("stage", "MODULE_SAVED_ORACLE_PENDING");
            save(output, report);
            if (moduleOnly) {
                report.setProperty("stage", "MODULE_SAVED_HEADER_RECORDED");
            } else {
                report.setProperty("native_module_executed", "attempted");
                oracle(module, output, report);
                report.setProperty("stage", "NATIVE_ENC_COLLECTED_RESPONSE_DIRECTION_PENDING");
            }
            code = 0;
        } catch (Throwable failure) {
            String message = failure.getMessage();
            report.setProperty("result", SAFE.contains(Objects.toString(message, "")) ? message : "DIAGNOSTIC_FAILED");
            report.setProperty("failure_class", failure.getClass().getName());
            if (Boolean.getBoolean("diagnostic.debug")) failure.printStackTrace(System.err);
        } finally {
            try { save(output, report); } catch (Exception ignored) {}
        }
        System.out.println("DIAGNOSTIC_COMPLETE exit=" + code + " loginAttempted=false; read report.properties");
        System.exit(code);
    }
    static void save(Path output, Properties report) throws Exception {
        try (var stream = Files.newOutputStream(output.resolve("report.properties"))) {
            report.store(stream, "No credentials, full URLs, tickets or keys in this summary");
        }
    }
}
