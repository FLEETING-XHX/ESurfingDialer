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

/** 隔离、无网的生产接入对照：请求走原生Code，合成响应从原生primitive生成。 */
public final class SessionInteropProbe {
    public static void main(String[] args) throws Exception {
        boolean responseWithoutNul=args.length==1&&args[0].equals("--response-without-nul");
        if(args.length!=0&&!responseWithoutNul)System.exit(2);
        Thread watchdog=new Thread(() -> {try{Thread.sleep(120000);Runtime.getRuntime().halt(124);}catch(InterruptedException ignored){}});
        watchdog.setDaemon(true);watchdog.start();
        LogManager.resetConfiguration();LogManager.getRootLogger().addAppender(new NullAppender());
        Path output=Path.of("/output");byte[] module;
        try(var stream=Files.newInputStream(Path.of("/input/module.bin"))){module=NativeDiagnostic.bounded(stream);}
        NativeDiagnostic.prepareOracle(output,new Properties());
        Session session=new Session(module);
        int requests=0,responses=0;
        try {
            var emulator=AndroidMock.Companion.getInstance().getEmulator();
            long code=Integer.toUnsignedLong(UnidbgPointer.pointer(emulator,session.getSessionId()).getInt(0x24));
            long base;long primitive;
            if(NativeDiagnostic.hash(emulator.getBackend().mem_read(code-0x31c5+0x1d1c,25688),"SHA-256")
                .equals("6d5e0725f87e5e14dc5270b50096b9e684dec50c3f8dc2e6c0001286db50e865")){
                base=code-0x31c5;primitive=base+0x2215;
            }else if(NativeDiagnostic.hash(emulator.getBackend().mem_read(code-0x38a5+0x1c40,24896),"SHA-256")
                .equals("b1a8487cc9cb110e467ac00fe2c58b368dae385fdf31060b3e00e2f7f8912080")){
                base=code-0x38a5;primitive=base+0x22b1;
            }else throw new Exception("CODE_PROFILE_UNKNOWN");
            final UnidbgPointer[] injection={null};final int[] length={0},hooks={0};
            HookZz.getInstance(emulator).wrap(primitive,new WrapCallback<Arm32RegisterContext>() {
                public void preCall(Emulator<?> emu,Arm32RegisterContext context,HookEntryInfo info) {
                    if(injection[0]!=null){
                        var editable=(HookZzArm32RegisterContext)context;
                        editable.setR2((int)injection[0].peer);
                        editable.setR3(length[0]);hooks[0]++;
                    }
                }
            });
            var lines=Files.readAllLines(Path.of("/input/cases.tsv"));
            String baseRequest="<request><local-time>old</local-time><probe>synthetic</probe></request>";
            try(var file=Files.newBufferedWriter(output.resolve("vectors.tsv"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
                file.write("case\tkind\tinput_utf8_hex\tnormalized_utf8_hex\tciphertext_hex\n");
                for(String line:lines.subList(1,lines.size())){
                    String[] cells=line.split("\t",-1);
                    if(cells.length!=3)throw new Exception("SYNTHETIC_FIXTURE_INVALID");
                    byte[] plain=HexFormat.of().parseHex(cells[2]);String cipher,normalized;
                    if(cells[1].equals("request")){
                        cipher=session.encrypt(new String(plain,StandardCharsets.UTF_8));normalized=session.decrypt(cipher);requests++;
                    }else if(cells[1].equals("response")){
                        // 仅替换已识别原生加密primitive的输入。key/IV/轮函数均由原模块提供。
                        // 不使用C++算法来生成响应向量，不调用Inspect/登录/网络。
                        // 响应与客户端Code的C字符串输入不是同一约束。
                        // 空响应仍用NUL；非空响应可直接由XML字节做PKCS7，交给原生DeCode验证。
                        byte[] terminated=responseWithoutNul&&plain.length>0?plain:Arrays.copyOf(plain,plain.length+1);
                        var block=emulator.getMemory().malloc(terminated.length,true);
                        try{
                            injection[0]=block.getPointer();injection[0].write(0,terminated,0,terminated.length);length[0]=terminated.length;
                            int before=hooks[0];cipher=session.encrypt(baseRequest);injection[0]=null;
                            if(hooks[0]!=before+1)throw new Exception("PRIMITIVE_INJECTION_NOT_OBSERVED");
                        }finally{injection[0]=null;block.free();}
                        normalized=session.decrypt(cipher);
                        if(!Arrays.equals(plain,normalized.getBytes(StandardCharsets.UTF_8)))throw new Exception("NATIVE_RESPONSE_DIRECTION_MISMATCH");
                        responses++;
                    }else throw new Exception("SYNTHETIC_KIND_INVALID");
                    file.write(cells[0]+"\t"+cells[1]+"\t"+cells[2]+"\t"+HexFormat.of().withUpperCase().formatHex(normalized.getBytes(StandardCharsets.UTF_8))+"\t"+cipher+"\n");
                }
            }
        }finally{session.free();}
        Files.writeString(output.resolve("scope.properties"),"native_request_cases="+requests+"\nnative_primitive_response_cases="+responses+"\nresponse_nul="+(responseWithoutNul?"nonempty_responses_without_nul":"included")+"\nresponse_origin=synthetic_native_primitive_not_server\nnetwork=none\nlogin_attempted=false\n");
        System.out.println("SESSION_INTEROP_COMPLETE requests="+requests+" responses="+responses+" network=none");System.exit(0);
    }
}
