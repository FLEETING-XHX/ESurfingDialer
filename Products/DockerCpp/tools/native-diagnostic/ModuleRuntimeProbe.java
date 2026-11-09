import com.rsplwe.esurfing.hook.Session;
import com.rsplwe.esurfing.hook.AndroidMock;
import com.github.unidbg.pointer.UnidbgPointer;
import org.apache.log4j.LogManager;
import org.apache.log4j.varia.NullAppender;
import java.nio.file.*;
import java.util.*;

/** 仅导出隔离模拟器的当前模块映像；可能含模块私有数据，禁止公开原始产物。 */
public final class ModuleRuntimeProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=0)System.exit(2);
        Thread watchdog=new Thread(() -> {try{Thread.sleep(90000);Runtime.getRuntime().halt(124);}catch(InterruptedException ignored){}});
        watchdog.setDaemon(true);watchdog.start();
        LogManager.resetConfiguration();LogManager.getRootLogger().addAppender(new NullAppender());
        Path output=Path.of("/output");
        byte[] module;
        try(var stream=Files.newInputStream(Path.of("/input/module.bin"))){module=NativeDiagnostic.bounded(stream);}
        NativeDiagnostic.prepareOracle(output,new Properties());
        Session session=new Session(module);
        try {
            var emulator=AndroidMock.Companion.getInstance().getEmulator();
            long handle=session.getSessionId();
            var pointer=UnidbgPointer.pointer(emulator,handle);
            Files.write(output.resolve("private-handle.bin"),emulator.getBackend().mem_read(handle,56),StandardOpenOption.CREATE_NEW);
            var meta=new Properties();
            meta.setProperty("handle",Long.toHexString(handle));
            meta.setProperty("native_aid",com.rsplwe.esurfing.States.INSTANCE.getAlgoId());
            Set<Long> addresses=new HashSet<>();
            for(int offset:new int[]{0x18,0x1c,0x20,0x24,0x28,0x2c,0x30,0x34}){
                long value=Integer.toUnsignedLong(pointer.getInt(offset));
                meta.setProperty("handle_"+Integer.toHexString(offset),Long.toHexString(value));
                if(offset>=0x1c)addresses.add(value&~1L);
            }
            int count=0;
            long encAddress=Integer.toUnsignedLong(pointer.getInt(0x24))&~1L;
            long imageBase=-1;
            for(var map:emulator.getMemory().getMemoryMap())
                if(encAddress>=map.base && encAddress<map.base+map.size)imageBase=map.base;
            for(var map:emulator.getMemory().getMemoryMap()){
                boolean selected=false;
                for(long address:addresses)if(address>=map.base && address<map.base+map.size)selected=true;
                if(imageBase>=0 && map.base>=imageBase && map.base<imageBase+0x20000)selected=true;
                if(!selected)continue;
                if(map.size>32*1024*1024)throw new Exception("PRIVATE_IMAGE_TOO_LARGE");
                String name="private-map-"+Long.toHexString(map.base)+".bin";
                Files.write(output.resolve(name),emulator.getBackend().mem_read(map.base,map.size),StandardOpenOption.CREATE_NEW);
                meta.setProperty("map_"+count,Long.toHexString(map.base)+","+Long.toHexString(map.size)+","+map.prot+","+name);
                count++;
            }
            meta.setProperty("selected_maps",Integer.toString(count));
            meta.setProperty("network","none");meta.setProperty("login_attempted","false");
            try(var stream=Files.newOutputStream(output.resolve("image.properties"),StandardOpenOption.CREATE_NEW)){meta.store(stream,"Private analysis image; may contain cryptographic material; do not publish raw dumps");}
        } finally {session.free();}
        System.out.println("PRIVATE_MODULE_IMAGE_SAVED loginAttempted=false");System.exit(0);
    }
}
