import com.rsplwe.esurfing.hook.Session;
import org.apache.log4j.LogManager;
import org.apache.log4j.varia.NullAppender;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 只在network:none中研究合成输入；self-dec不是服务端响应方向的黄金向量。 */
public final class InputSemanticsProbe {
    static final String DECL = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";
    static String xml(String fields) { return DECL + "<request>" + fields + "</request>"; }
    static String hex(String value) { return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)); }
    static String fields(String time, String ip) {
        return "<user-agent>CCTP/Android8_vpn/2082</user-agent>" +
            "<client-id>11111111-1111-4111-8111-111111111111</client-id>" +
            "<local-time>"+time+"</local-time><host-name>Xiaomi 6</host-name>" +
            "<ipv4>"+ip+"</ipv4><ipv6></ipv6><mac>02:00:00:00:00:01</mac><ostag>Xiaomi 6</ostag>";
    }
    public static void main(String[] args) throws Exception {
        if(args.length > 1 || (args.length == 1 && !args[0].equals("enc-only"))) System.exit(2);
        boolean selfDec = args.length == 0;
        Thread watchdog = new Thread(() -> { try { Thread.sleep(90000); Runtime.getRuntime().halt(124); } catch(InterruptedException ignored) {} });
        watchdog.setDaemon(true); watchdog.start();
        LogManager.resetConfiguration(); LogManager.getRootLogger().addAppender(new NullAppender());
        Path output=Path.of("/output");
        if(Files.exists(output.resolve("semantics.tsv"))) throw new Exception("OUTPUT_EXISTS");
        byte[] module;
        try(var stream=Files.newInputStream(Path.of("/input/module.bin"))) { module=NativeDiagnostic.bounded(stream); }
        Properties report = new Properties();
        NativeDiagnostic.prepareOracle(output, report);
        var cases = new LinkedHashMap<String,String>();
        cases.put("plain-1", "a"); cases.put("plain-33", "a".repeat(33));
        cases.put("empty", ""); cases.put("whitespace", " \r\n\t");
        cases.put("malformed", "<request><test>A");
        cases.put("empty-root", "<request/>");
        cases.put("empty-root-long", "<request></request>");
        cases.put("decl-empty-root", xml(""));
        cases.put("unknown-A", xml("<test>A</test>"));
        cases.put("unknown-B", xml("<test>B</test>"));
        cases.put("other-root", DECL+"<response><test>A</test></response>");
        cases.put("other-root-then-plain", "a");
        cases.put("entity-escaped", xml("<test>A&amp;B&lt;C&gt;</test>"));
        cases.put("entity-cdata", xml("<test><![CDATA[A&B<C>]]></test>"));
        cases.put("unicode", xml("<test>中文😀</test>"));
        String base=xml(fields("2026-01-01 00:00:00", "192.0.2.1"));
        cases.put("ticket-base", base);
        cases.put("ticket-time-changed", xml(fields("2030-12-31 23:59:59", "192.0.2.1")));
        cases.put("ticket-ip-changed", xml(fields("2026-01-01 00:00:00", "198.51.100.7")));
        cases.put("ticket-space-indentation", base.replace("><", ">\n    <"));
        cases.put("ticket-crlf", base.replace("><", ">\r\n<"));
        cases.put("ticket-comment", base.replace("<request>", "<request><!--synthetic comment-->"));
        cases.put("ticket-no-declaration", base.substring(DECL.length()));
        cases.put("ticket-extra-field", base.replace("</request>","<extra>synthetic</extra></request>"));
        cases.put("ticket-field-order", xml("<ostag>Xiaomi 6</ostag><mac>02:00:00:00:00:01</mac><ipv6/>"+
            "<ipv4>192.0.2.1</ipv4><host-name>Xiaomi 6</host-name><local-time>2026-01-01 00:00:00</local-time>"+
            "<client-id>11111111-1111-4111-8111-111111111111</client-id><user-agent>CCTP/Android8_vpn/2082</user-agent>"));
        cases.put("login-synthetic", xml("<user-agent>CCTP/Android8_vpn/2082</user-agent>"+
            "<client-id>11111111-1111-4111-8111-111111111111</client-id><local-time>2026-01-01 00:00:00</local-time>"+
            "<ticket>synthetic-ticket</ticket><userid>synthetic-user</userid><passwd>synthetic-password</passwd>"));
        cases.put("ticket-base-repeat", base);
        cases.put("request-then-plain", "a");
        Session session=new Session(module);
        try(var file=Files.newBufferedWriter(output.resolve("semantics.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            file.write("case\tinput_utf8_hex\tenc_utf8_hex\tself_dec_utf8_hex\tstatus\n");
            for(var item:cases.entrySet()) {
                String enc="",dec="",status="OK";
                try {
                    enc=session.encrypt(item.getValue());
                    if(selfDec) {
                        try { dec=session.decrypt(enc); } catch(Throwable e) { status="SELF_DEC_"+e.getClass().getSimpleName(); }
                    }
                } catch(Throwable e) { status="ENC_"+e.getClass().getSimpleName(); }
                file.write(item.getKey()+"\t"+hex(item.getValue())+"\t"+hex(enc)+"\t"+hex(dec)+"\t"+status+"\n");
                file.flush();
            }
        } finally { session.free(); }
        Files.writeString(output.resolve("scope.txt"), "self_dec_enabled="+selfDec+"\n"+"cases="+cases.size()+"\nnetwork=none\nlogin_attempted=false\nself_dec_is_server_response_oracle=false\n");
        System.out.println("INPUT_SEMANTICS_COMPLETE cases="+cases.size()+" loginAttempted=false");
        System.exit(0);
    }
}
