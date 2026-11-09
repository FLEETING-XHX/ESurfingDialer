import com.rsplwe.esurfing.hook.Session;
import com.rsplwe.esurfing.hook.AndroidMock;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.context.Arm32RegisterContext;
import com.github.unidbg.hook.hookzz.*;
import org.apache.log4j.LogManager;
import org.apache.log4j.varia.NullAppender;
import java.nio.file.*;
import java.util.*;

/** 隔离环境中记录原生loader交给LZMA的字节；不调用Code/Inspect/网络。 */
public final class Type0LoaderBoundaryProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=0)System.exit(2);
        LogManager.resetConfiguration();LogManager.getRootLogger().addAppender(new NullAppender());
        Path out=Path.of("/output");NativeDiagnostic.prepareOracle(out,new Properties());
        var emu=AndroidMock.Companion.getInstance().getEmulator();var library=emu.getMemory().findModule("libdaproxy.so");
        if(library==null)throw new Exception("LIBRARY_MISSING");
        final byte[][] observed={null};
        HookZz.getInstance(emu).wrap(library.base+0x2e81,new WrapCallback<Arm32RegisterContext>(){
            public void preCall(Emulator<?> current,Arm32RegisterContext context,HookEntryInfo info){
                int length=context.getPointerArg(3).getInt(0);
                if(length<5||length>1048576)throw new IllegalStateException("BOUNDARY_SIZE_INVALID");
                observed[0]=context.getPointerArg(2).getByteArray(0,length);
            }
        });
        byte[] module=Files.readAllBytes(Path.of("/input/module.bin"));
        Session session=new Session(module);session.free();
        if(observed[0]==null)throw new Exception("LZMA_BOUNDARY_NOT_OBSERVED");
        Files.write(out.resolve("private-native-lzma-input.bin"),observed[0],StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("boundary.properties"),"bytes="+observed[0].length+"\nfirst_byte_zero="+(observed[0][0]==0)+"\nnetwork=none\nlogin_attempted=false\n");
        System.out.println("TYPE0_LZMA_BOUNDARY_COMPLETE");System.exit(0);
    }
}
