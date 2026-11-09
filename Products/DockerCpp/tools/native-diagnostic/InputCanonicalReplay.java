import com.rsplwe.esurfing.hook.Session;
import org.apache.log4j.LogManager;
import org.apache.log4j.varia.NullAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 对先前合成self-dec结果做重新加密核对；不代表服务端响应验证。 */
public final class InputCanonicalReplay {
    public static void main(String[] args) throws Exception {
        if(args.length != 0) System.exit(2);
        Thread watchdog=new Thread(() -> { try { Thread.sleep(90000); Runtime.getRuntime().halt(124); } catch(InterruptedException ignored) {} });
        watchdog.setDaemon(true);watchdog.start();
        LogManager.resetConfiguration();LogManager.getRootLogger().addAppender(new NullAppender());
        Path output=Path.of("/output");
        byte[] module;
        try(var stream=Files.newInputStream(Path.of("/input/module.bin"))) { module=NativeDiagnostic.bounded(stream); }
        NativeDiagnostic.prepareOracle(output, new Properties());
        Session session=new Session(module);
        int total=0,matched=0;
        try {
            try(var file=Files.newBufferedWriter(output.resolve("replay.tsv"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
                file.write("case\treenc_equal\n");
                var lines=Files.readAllLines(Path.of("/input/prior.tsv"));
                for(String line:lines.subList(1,lines.size())) {
                    String[] cells=line.split("\t",-1);
                    if(cells.length!=5 || !cells[4].equals("OK")) throw new Exception("INVALID_SYNTHETIC_FIXTURE");
                    String plain=new String(HexFormat.of().parseHex(cells[3]),StandardCharsets.UTF_8);
                    String expected=new String(HexFormat.of().parseHex(cells[2]),StandardCharsets.UTF_8);
                    boolean equal=session.encrypt(plain).equals(expected);
                    total++;if(equal)matched++;
                    file.write(cells[0]+"\t"+equal+"\n");
                }
            }
            String base=InputSemanticsProbe.xml(InputSemanticsProbe.fields("2026-01-01 00:00:00","192.0.2.1"));
            var cases=new LinkedHashMap<String,String>();
            cases.put("no-time",base.replace("<local-time>2026-01-01 00:00:00</local-time>",""));
            cases.put("empty-time",base.replace("<local-time>2026-01-01 00:00:00</local-time>","<local-time/>"));
            cases.put("duplicate-time",base.replace("</local-time>","</local-time><local-time>2030-12-31 23:59:59</local-time>"));
            for(int size:new int[]{1,7,8,9,15,16,17,31,32,33})
                cases.put("valid-payload-"+size,base.replace("</request>","<probe>"+"a".repeat(size)+"</probe></request>"));
            try(var file=Files.newBufferedWriter(output.resolve("time-cases.tsv"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
                file.write("case\tinput_utf8_hex\tenc_utf8_hex\tself_dec_utf8_hex\n");
                for(var item:cases.entrySet()) {
                    String enc=session.encrypt(item.getValue());
                    String dec=session.decrypt(enc);
                    file.write(item.getKey()+"\t"+InputSemanticsProbe.hex(item.getValue())+"\t"+InputSemanticsProbe.hex(enc)+"\t"+InputSemanticsProbe.hex(dec)+"\n");
                }
            }
        } finally {session.free();}
        Files.writeString(output.resolve("scope.txt"),"replay_total="+total+"\nreplay_equal="+matched+"\nextra_time_cases=3\nvalid_payload_cases=10\nnetwork=none\nlogin_attempted=false\nself_dec_is_server_response_oracle=false\n");
        if(matched!=total) throw new Exception("CANONICAL_REPLAY_MISMATCH");
        System.out.println("CANONICAL_REPLAY_COMPLETE matched="+matched);
        System.exit(0);
    }
}
