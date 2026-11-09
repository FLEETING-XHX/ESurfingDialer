import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;

public class NativeDiagnosticTest {
    static int checks;
    static void check(boolean value) { if (!value) throw new AssertionError("check " + (checks + 1)); checks++; }
    public static void main(String[] args) throws Exception {
        String id = "03F8A638-5C23-418B-972C-A2BA6927EF77";
        byte[] module = new byte[41]; module[4] = 36;
        System.arraycopy(id.getBytes(StandardCharsets.US_ASCII), 0, module, 5, 36);
        check(NativeDiagnostic.candidate(module).equals(id));
        check(NativeDiagnostic.candidate(new byte[4]).equals("UNPARSED"));
        check(NativeDiagnostic.candidate(new byte[]{0,0,0,(byte)255,0}).equals("UNPARSED"));
        check(NativeDiagnostic.candidate(new byte[NativeDiagnostic.LIMIT+1]).equals("UNPARSED"));
        for (int type : new int[]{0,1,2,3,15}) {
            byte[] container=new byte[52];container[0]='0';container[1]='0';container[2]='1';container[4]=36;
            System.arraycopy(id.getBytes(StandardCharsets.US_ASCII),0,container,5,36);
            container[41]=0x5d;container[45]=1;container[46]=0x34;container[47]=0x12;container[49]=(byte)(type<<4);
            var header=new java.util.Properties();NativeDiagnostic.describeContainer(container,header);
            check(header.getProperty("container_encoding_type").equals(Integer.toString(type)));
            check(header.getProperty("container_declared_output_bytes").equals("4660"));
            check(header.getProperty("container_dictionary_bytes").equals("16777216"));
            check(header.getProperty("container_payload_bytes").equals("2"));
        }
        for (byte[] invalid : new byte[][]{new byte[4],new byte[52],new byte[]{'0','0','1',(byte)255,0},new byte[]{'0','0','1',0,0}}) {
            var header=new java.util.Properties();NativeDiagnostic.describeContainer(invalid,header);
            check(header.getProperty("container_header_status").equals("UNPARSED")&&!header.containsKey("container_encoding_type"));
        }
        check(Arrays.equals(NativeDiagnostic.bounded(new ByteArrayInputStream(module)), module));
        try { NativeDiagnostic.bounded(new ByteArrayInputStream(new byte[NativeDiagnostic.LIMIT+1])); throw new AssertionError(); }
        catch (Exception expected) { check(expected.getMessage().equals("MODULE_SIZE_INVALID")); }
        var directory = java.nio.file.Files.createTempDirectory("native-diagnostic-test-");
        var urlFile = directory.resolve("url.txt");
        java.nio.file.Files.writeString(urlFile, "http://127.0.0.1/ticket.cgi");
        try { NativeDiagnostic.localUrl(urlFile); throw new AssertionError(); }
        catch (Exception expected) { check(expected.getMessage().equals("URL_REJECTED")); }
        java.nio.file.Files.writeString(urlFile, "http://example.invalid/ticket.cgi?wlanuserip=192.0.2.1&wlanacip=192.0.2.2");
        check(NativeDiagnostic.localUrl(urlFile).contains("example.invalid"));
        java.nio.file.Files.delete(urlFile); java.nio.file.Files.delete(directory);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/ticket.cgi", exchange -> {
            try {
                check(exchange.getRequestMethod().equals("POST"));
                check(exchange.getRequestHeaders().getFirst("User-Agent").equals("CCTP/Android8_vpn/2082"));
                check(exchange.getRequestHeaders().getFirst("Algo-ID").equals(NativeDiagnostic.ZERO));
                check(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).equals(NativeDiagnostic.ZERO));
                check(exchange.getRequestHeaders().getFirst("CDC-Checksum").equals(NativeDiagnostic.hash(NativeDiagnostic.ZERO.getBytes(StandardCharsets.UTF_8), "MD5")));
                requests.incrementAndGet(); exchange.sendResponseHeaders(200, module.length);
                exchange.getResponseBody().write(module);
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { exchange.close(); }
        });
        server.start();
        OkHttpClient http = new OkHttpClient.Builder().followRedirects(false).build();
        try {
            check(Arrays.equals(module, NativeDiagnostic.fetchModule(http, "http://127.0.0.1:"+server.getAddress().getPort()+"/ticket.cgi")));
            check(requests.get() == 1);
            try { NativeDiagnostic.fetchModule(http, "http://127.0.0.1:"+server.getAddress().getPort()+"/auth.cgi"); throw new AssertionError(); }
            catch (Exception expected) { check(expected.getMessage().equals("NOT_TICKET_ENDPOINT")); }
            check(requests.get() == 1);
        } finally { server.stop(0); http.connectionPool().evictAll(); http.dispatcher().executorService().shutdownNow(); }
        System.out.println(checks + " offline diagnostic checks passed; no campus requests/native module execution");
    }
}
