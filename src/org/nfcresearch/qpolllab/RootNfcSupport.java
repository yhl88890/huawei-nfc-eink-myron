package org.nfcresearch.qpolllab;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Optional, device-specific preparation. Extracts only bundled code, never caller paths. */
final class RootNfcSupport implements AutoCloseable {
    interface Log {void line(String value);}
    private static final File DIRECTORY=new File("/data/vendor/nfc/qpolllab");
    private static final File ENABLE=new File(DIRECTORY,"enable");
    private final String apk;
    private final Log log;
    private boolean ownsLease,installed,leaseDisabled;
    private File injector,library;
    RootNfcSupport(String apk,Log log){this.apk=apk;this.log=log;}
    static void clearStaleLease()throws IOException {
        if(!ENABLE.exists())return;
        if(android.os.Process.myUid()!=0)throw new IOException("检测到上次 Root 兼容层会话开关，请以 Shizuku Root 重连后清理");
        if(!ENABLE.delete())throw new IOException("无法清理上次 HAL 会话开关，请重启 NFC");
    }
    void prepare()throws Exception {
        if(android.os.Process.myUid()!=0)throw new SecurityException("HAL 兼容层需要 Shizuku Root");
        boolean arm64=false;for(String abi:android.os.Build.SUPPORTED_ABIS)arm64|=abi.equals("arm64-v8a");
        String chip=ChipInfo.inspect();
        // SN220 and SN100U share the NXP nqx NCIL HAL (same hook symbols, verified on device).
        if(!arm64||!(chip.contains("NXP SN220")||chip.contains("NXP SN100U")))
            throw new IOException("兼容层仅支持 arm64 NXP SN220/SN100U 固件配置；当前设备不匹配");
        if(!DIRECTORY.getCanonicalFile().equals(DIRECTORY))throw new IOException("HAL directory must not be a symlink");
        if(!DIRECTORY.isDirectory()&&!DIRECTORY.mkdir())throw new IOException("无法建立 HAL 兼容层目录");
        run("chown","0:0",DIRECTORY.toString());run("chmod","755",DIRECTORY.toString());
        run("chcon","u:object_r:vendor_file:s0",DIRECTORY.toString());
        // The bridge's global lock covers the lease as well as NFC/ReaderMode cleanup.
        if(ENABLE.exists()&&!ENABLE.delete())throw new IOException("无法清理上次会话开关");
        ownsLease=true;
        injector=extract("qpoll-inject");library=extract("libqpollhook.so");
        run(injector.toString(),library.toString());
        installed=true;
        // The helper returns success only after both GOT slots were installed and verified.
        try(FileOutputStream output=new FileOutputStream(ENABLE)){output.write(1);}
        run("chmod","644",ENABLE.toString());run("chcon","u:object_r:vendor_file:s0",ENABLE.toString());
        log.line("QP|HOOK|NXP HAL 双向兼容层已确认安装（SN220/SN100U）；本会话启用，最长 360 秒");
        snapshot("before-worker");
    }
    private void snapshot(String phase) {
        if(!installed)return;
        log.line("QP|HOOK_STATS_PHASE|"+phase+"；计数为该版本库在当前 HAL 进程内的累计值");
        try{run(injector.toString(),library.toString(),"--status");}
        catch(Exception e){log.line("QP|HOOK_STATS_ERROR|只读统计失败："+e);}
    }
    void beforeReaderRestore()throws IOException {
        if(!ownsLease||leaseDisabled)return;
        try{snapshot("before-reader-restore");}finally{disableLease();}
    }
    private volatile boolean polling=true;
    /** Session-time verdicts straight from the hook's shared-memory counters (no logcat dependency). */
    void startStatusPolling() {
        if(injector==null||library==null)return;
        Thread poller=new Thread(()->{
            boolean announcedSend=false,announcedAccept=false,announcedAnswer=false;
            while(polling){
                try{
                    java.lang.Process p=new ProcessBuilder(injector.toString(),library.toString(),"--status").redirectErrorStream(true).start();
                    StringBuilder sb=new StringBuilder();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"))){String l;while((l=r.readLine())!=null)sb.append(l).append(' ');}
                    p.waitFor(15,TimeUnit.SECONDS);
                    String s=sb.toString();
                    java.util.regex.Matcher m=java.util.regex.Pattern.compile("rewrite_internal=(\\d+)").matcher(s);
                    if(m.find()&&Long.parseLong(m.group(1))>0&&!announcedSend){
                        announcedSend=true;log.line("QP|CAPABILITY|已发出 0x71 Q-Poll 寻卡（兼容层改写发现指令 "+m.group(1)+" 次），等待控制器确认。");
                    }
                    int i=s.indexOf("RX_DISCOVER_RSP");
                    if(i>=0){
                        int b=s.indexOf("before=410301",i);
                        if(b>=0&&!announcedAccept){
                            announcedAccept=true;String st=s.substring(b+13,b+15);
                            log.line(st.equals("00")?"QP|CAPABILITY|控制器已接受 Q-Poll 发现（0x71，响应状态 00）——固件支持 Q-Poll":"QP|CAPABILITY|Q-Poll 发现被控制器拒绝（响应状态 "+st+"）——固件不支持");
                        }
                    }
                    if(s.contains("RX_NOTIFICATION")&&s.contains("before=6105")&&!announcedAnswer){
                        announcedAnswer=true;log.line("QP|CAPABILITY|Q-Poll 屏已应答：RF 接口激活（0x71 发现类型，入向已还原为 Type A 上报）——硬件链路验证完成");
                    }
                }catch(Exception ignored){}
                try{Thread.sleep(2000);}catch(InterruptedException e){return;}
            }
        },"hook-status-poll");
        poller.setDaemon(true);poller.start();
    }
    private void disableLease()throws IOException {
        if(ENABLE.exists()&&!ENABLE.delete()){
            log.line("QP|RESTORE_FAILED|无法关闭 HAL 兼容开关，请重启 NFC");throw new IOException("HAL lease cleanup failed");
        }
        leaseDisabled=true;
    }
    private File extract(String name)throws Exception {
        byte[] data;
        try(ZipFile zip=new ZipFile(apk)){
            ZipEntry entry=zip.getEntry("assets/native/arm64-v8a/"+name);
            if(entry==null)throw new IOException("APK 未包含原生兼容层，请先用 NDK 构建 native/build.sh");
            if(entry.getSize()<1||entry.getSize()>4*1024*1024)throw new IOException("Invalid native asset size");
            try(InputStream input=zip.getInputStream(entry);ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int size;while((size=input.read(buffer))!=-1){if(output.size()+size>4*1024*1024)throw new IOException("Native asset too large");output.write(buffer,0,size);}data=output.toByteArray();
            }
        }
        String hash=PanelProtocol.toHex(MessageDigest.getInstance("SHA-256").digest(data)).toLowerCase(java.util.Locale.ROOT);
        File file=new File(DIRECTORY,hash+"-"+name);
        if(!file.getCanonicalFile().equals(file))throw new IOException("Native asset must not be a symlink");
        // Content-addressed files are never overwritten while mapped by the HAL.
        if(file.exists()){
            if(file.length()!=data.length||!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())),MessageDigest.getInstance("SHA-256").digest(data)))
                throw new IOException("已有兼容层文件校验失败，请停止使用该目录");
        }else{
            File temporary=File.createTempFile("asset-",".tmp",DIRECTORY);
            try{
                if(!temporary.setReadable(false,false)||!temporary.setReadable(true,true)||!temporary.setWritable(false,false)||!temporary.setWritable(true,true))throw new IOException("Cannot protect native asset");
                try(FileOutputStream output=new FileOutputStream(temporary)){output.write(data);output.getFD().sync();}
                Files.move(temporary.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }finally{temporary.delete();}
        }
        run("chown","0:0",file.toString());run("chmod","755",file.toString());run("chcon","u:object_r:vendor_file:s0",file.toString());
        return file;
    }
    private void run(String... command)throws Exception {
        boolean statusRead=command.length==3&&command[2].equals("--status");
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output=new StringBuilder();
        Thread reader=new Thread(()->{try(BufferedReader input=new BufferedReader(new InputStreamReader(process.getInputStream(),"UTF-8"))){String line;while((line=input.readLine())!=null)synchronized(output){if(output.length()<8192)output.append(line).append('\n');}}catch(IOException ignored){}});
        reader.setDaemon(true);reader.start();
        if(!process.waitFor(15,TimeUnit.SECONDS)){
            process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();
            if(!statusRead)log.line("QP|RESTORE_FAILED|HAL 准备超时，请关闭再开启 NFC");
            throw new IOException("HAL 准备超时："+command[0]);
        }
        reader.join(1000);String result;synchronized(output){result=output.toString().trim();}
        if(!result.isEmpty())log.line("QP|HOOK_DETAIL|"+result.replace('\n',' '));
        if(process.exitValue()!=0){
            if(!statusRead&&command[0].endsWith("-qpoll-inject")&&process.exitValue()>=6)log.line("QP|RESTORE_FAILED|HAL 注入未确认，请关闭再开启 NFC 后重试");
            throw new IOException("HAL 准备失败，未启动 NFC worker："+command[0]+" ("+process.exitValue()+")");
        }
    }
    @Override public void close()throws IOException {
        polling=false;
        if(ownsLease){
            try{if(!leaseDisabled)snapshot("after-worker-before-disable");}
            finally{disableLease();}
            log.line(installed?"QP|HOOK_OFF|已关闭 HAL 双向改写；库驻留至 NFC HAL 进程重启":"QP|HOOK_OFF|会话开关已清理；本轮未确认兼容层安装成功");
            ownsLease=false;
        }
    }
}
