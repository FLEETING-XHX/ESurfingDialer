import com.rsplwe.esurfing.hook.Session;
import com.rsplwe.esurfing.hook.AndroidMock;
import com.github.unidbg.Emulator;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.arm.context.Arm32RegisterContext;
import com.github.unidbg.hook.hookzz.*;
import org.apache.log4j.LogManager;
import org.apache.log4j.varia.NullAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 已识别代码profile的私有边界取证；只处理合成XML/响应。 */
public final class CipherBoundaryProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=0)System.exit(2);
        Thread watchdog=new Thread(() -> {try{Thread.sleep(90000);Runtime.getRuntime().halt(124);}catch(InterruptedException ignored){}});
        watchdog.setDaemon(true);watchdog.start();
        LogManager.resetConfiguration();LogManager.getRootLogger().addAppender(new NullAppender());
        Path output=Path.of("/output");byte[] module;
        try(var stream=Files.newInputStream(Path.of("/input/module.bin"))){module=NativeDiagnostic.bounded(stream);}
        NativeDiagnostic.prepareOracle(output,new Properties());
        Session session=new Session(module);
        try {
            var emulator=AndroidMock.Companion.getInstance().getEmulator();
            long code=Integer.toUnsignedLong(UnidbgPointer.pointer(emulator,session.getSessionId()).getInt(0x24));
            long base=code-0x31c5;
            if(!NativeDiagnostic.hash(emulator.getBackend().mem_read(base+0x1d1c,25688),"SHA-256")
                .equals("6d5e0725f87e5e14dc5270b50096b9e684dec50c3f8dc2e6c0001286db50e865"))
                throw new Exception("CODE_PROFILE_UNKNOWN");
            final byte[][] encPlain={null},encCipher={null},decPadded={null};
            final UnidbgPointer[] decStruct={null};
            HookZz.getInstance(emulator).wrap(base+0x2215,new WrapCallback<Arm32RegisterContext>() {
                public void preCall(Emulator<?> emu,Arm32RegisterContext context,HookEntryInfo info) {
                    int size=context.getIntArg(3);
                    if(size<1 || size>65537)throw new IllegalStateException("BOUNDARY_SIZE_INVALID");
                    encPlain[0]=context.getPointerArg(2).getByteArray(0,size);
                }
                public void postCall(Emulator<?> emu,Arm32RegisterContext context,HookEntryInfo info) {
                    var result=context.getR0Pointer();
                    int size=result.getInt(4);
                    if(size<1 || size>65544)throw new IllegalStateException("BOUNDARY_SIZE_INVALID");
                    encCipher[0]=result.getPointer(8).getByteArray(0,size);
                }
            });
            HookZz.getInstance(emulator).wrap(base+0x25ed,new WrapCallback<Arm32RegisterContext>() {
                public void preCall(Emulator<?> emu,Arm32RegisterContext context,HookEntryInfo info) {decStruct[0]=context.getPointerArg(2);}
                public void postCall(Emulator<?> emu,Arm32RegisterContext context,HookEntryInfo info) {
                    var result=decStruct[0];int size=result.getInt(4);
                    if(size<1 || size>65544)throw new IllegalStateException("BOUNDARY_SIZE_INVALID");
                    decPadded[0]=result.getPointer(12).getByteArray(0,size);
                }
            });
            String raw=InputSemanticsProbe.xml(InputSemanticsProbe.fields("2026-01-01 00:00:00","192.0.2.1"));
            String enc=session.encrypt(raw);
            String decoded=session.decrypt(enc);
            byte[] canonical=decoded.getBytes(StandardCharsets.UTF_8);
            byte[] withNul=Arrays.copyOf(canonical,canonical.length+1);
            if(!Arrays.equals(withNul,encPlain[0]))throw new Exception("ENC_BOUNDARY_NOT_CANONICAL_PLUS_NUL");
            if(!Arrays.equals(HexFormat.of().parseHex(enc),encCipher[0]))throw new Exception("ENC_OUTPUT_NOT_UPPERCASE_HEX");
            byte[] padded=Arrays.copyOf(withNul,(withNul.length+7)&~7);
            if(!Arrays.equals(padded,decPadded[0]))throw new Exception("DEC_BOUNDARY_NOT_ZERO_PADDED");
            Files.write(output.resolve("private-enc-plain-with-nul.bin"),encPlain[0],StandardOpenOption.CREATE_NEW);
            Files.write(output.resolve("private-dec-padded.bin"),decPadded[0],StandardOpenOption.CREATE_NEW);
            int independentCases=0;
            if(Files.exists(Path.of("/input/cases.tsv"))) {
                var lines=Files.readAllLines(Path.of("/input/cases.tsv"));
                for(String line:lines.subList(1,lines.size())) {
                    String[] cells=line.split("\t",-1);
                    if(cells.length!=3)throw new Exception("INVALID_SYNTHETIC_CASE");
                    byte[] expected=HexFormat.of().parseHex(cells[1]);
                    String actual=session.decrypt(cells[2]);
                    if(!Arrays.equals(expected,actual.getBytes(StandardCharsets.UTF_8)))throw new Exception("CPP_GENERATED_CIPHERTEXT_NATIVE_DEC_MISMATCH");
                    independentCases++;
                }
                if(independentCases==0)throw new Exception("EMPTY_SYNTHETIC_CASE_SET");
            }
            var report=new Properties();
            report.setProperty("enc_boundary_equals_canonical_xml_plus_nul","true");
            report.setProperty("dec_boundary_equals_zero_padded_plaintext","true");
            report.setProperty("enc_hex_matches_raw_ciphertext","true");
            report.setProperty("independent_cpp_ciphertext_native_dec_cases",Integer.toString(independentCases));
            report.setProperty("server_authentication_tested","false");report.setProperty("network","none");
            try(var stream=Files.newOutputStream(output.resolve("boundary.properties"),StandardOpenOption.CREATE_NEW)){report.store(stream,"Synthetic boundary checks; no keys included");}
        }finally{session.free();}
        System.out.println("CIPHER_BOUNDARY_COMPLETE loginAttempted=false");System.exit(0);
    }
}
