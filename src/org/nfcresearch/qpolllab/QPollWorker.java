package org.nfcresearch.qpolllab;

import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded root helper for observation, identity reads, and explicitly requested image refresh. */
public final class QPollWorker {
    private static Object service, callback;
    private static Class<?> adapterType;
    private static final Binder token = new Binder();
    private static final AtomicBoolean stopping = new AtomicBoolean();
    private static final AtomicBoolean cleaned = new AtomicBoolean();
    private static final AtomicBoolean tagSeen = new AtomicBoolean();
    private static volatile boolean readerTouched, qtagTouched, readerReleased, qtagDisabled;
    private static int discoveredCode, lostCode = -1;
    private static Process logcat;
    private static volatile Tag detectedTag;
    private static volatile String qtagRejection;
    private static volatile boolean qDiscoverySent,qDiscoveryAccepted;
    private static String mode;
    private static boolean halDiscovery;
    private static boolean hookRestored=true;
    private static File hookOffAck;
    private static final Binder callbackBinder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString("android.nfc.IAppCallback");
                return true;
            }
            if (code != discoveredCode && code != lostCode) return super.onTransact(code, data, reply, flags);
            data.enforceInterface("android.nfc.IAppCallback");
            Tag tag = data.readInt() == 0 ? null : Tag.CREATOR.createFromParcel(data);
            if (code == discoveredCode && tag != null && tagSeen.compareAndSet(false,true)) {
                detectedTag=tag;
                emit("TAG", "收到标签 UID=" + hex(tag.getId()) + "；技术=" + Arrays.toString(tag.getTechList()));
                NfcA a = NfcA.get(tag);
                if (a != null) emit("META", "ATQA=" + hex(a.getAtqa()) + "；SAK=" + String.format("%04X", a.getSak() & 0xffff));
                stopping.set(true);
            } else if (code == lostCode) {
                emit("LOST", "收到标签离场通知（不代表已读到目标屏）");
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    private static synchronized void emit(String kind, String message) {
        System.out.println("QP|" + kind + "|" + message.replace('\n', ' '));
        System.out.flush();
    }
    private static String hex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02X", b & 255));
        return s.toString();
    }
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        try { return adapterType.getMethod(name, types).invoke(service, args); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
    private static void reader(int flags) throws Exception {
        Bundle extras=new Bundle();
        if("refresh".equals(mode)||"identity".equals(mode))extras.putInt("presence",5000);
        Class<?> callbackType=Class.forName("android.nfc.IAppCallback");
        try {
            // Android 15+: setReaderMode(IBinder, IAppCallback, int, Bundle, String packageName)
            invoke("setReaderMode", new Class<?>[]{IBinder.class, callbackType,
                    int.class, Bundle.class, String.class}, token, callback, flags, extras, "com.android.shell");
        } catch (NoSuchMethodException e) {
            // Android 14 and older ROMs (incl. HyperOS on some devices): no caller-package parameter.
            invoke("setReaderMode", new Class<?>[]{IBinder.class, callbackType,
                    int.class, Bundle.class}, token, callback, flags, extras);
        }
    }
    private static int qtag(int action) throws Exception {
        return (Integer) invoke("sendVendorNciMessage", new Class<?>[]{int.class,int.class,int.class,byte[].class},
                1, 15, 112, new byte[]{0x31, (byte) action});
    }
    private static void init() throws Exception {
        if (android.os.Process.myUid()!=2000) throw new SecurityException("NFC worker 必须以 UID 2000 运行；Root 后端切换身份失败，未调用 NFC");
        String domain=new String(java.nio.file.Files.readAllBytes(new File("/proc/self/attr/current").toPath()),"UTF-8").replace("\u0000","").trim();
        if(!domain.equals("u:r:shell:s0"))throw new SecurityException("NFC worker SELinux 域不是 shell："+domain);
        emit("WORKER_IDENTITY","UID=2000；SELinux="+domain);
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "nfc");
        if (binder == null) throw new IllegalStateException("未找到 NFC 服务");
        adapterType = Class.forName("android.nfc.INfcAdapter");
        service = Class.forName("android.nfc.INfcAdapter$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        { StringBuilder probe=new StringBuilder();
          for (java.lang.reflect.Method m : adapterType.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            String n = m.getName();
            if (n.contains("endor") || n.contains("oller") || n.toLowerCase(java.util.Locale.ROOT).contains("poll")
                    || n.contains("Reader") || n.contains("ebug") || n.contains("iscovery") || n.contains("ender"))
                probe.append(n).append('(').append(m.getParameterCount()).append(") ");
          }
          emit("API_PROBE", probe.toString());
        }
        int state = (Integer) invoke("getState", new Class<?>[0]);
        emit("STATE", "执行身份 UID="+android.os.Process.myUid()+"；NFC 状态=" + state + (state == 3 ? "（已开启）" : "（未开启）"));
        if (state != 3) throw new IllegalStateException("请先在系统设置中开启 NFC");
    }
    private static void setupCallback() throws Exception {
        Class<?> type = Class.forName("android.nfc.IAppCallback");
        Class<?> stub = Class.forName("android.nfc.IAppCallback$Stub");
        Field field = stub.getDeclaredField("TRANSACTION_onTagDiscovered");
        field.setAccessible(true); discoveredCode = field.getInt(null);
        try { field = stub.getDeclaredField("TRANSACTION_onTagLost"); field.setAccessible(true); lostCode = field.getInt(null); }
        catch (NoSuchFieldException ignored) { }
        callback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getName().equals("asBinder")) return callbackBinder;
            if (method.getName().equals("toString")) return "QPollLabCallback";
            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
            if (method.getName().equals("equals")) return object == args[0];
            return null;
        });
    }
    private static void startLogcat() {
        try {
            // Snapshot polling: some ROMs (HyperOS/SN100U) deliver no streaming output to a
            // piped logcat child, while `logcat -d` dumps fine. Poll the buffer instead.
            java.util.Set<String> seen = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
            final int[] rounds = {0};
            Thread monitor = new Thread(() -> {
                while (!cleaned.get()) {
                    try {
                        java.lang.Process snap = new ProcessBuilder("/system/bin/logcat", "-d", "-v", "threadtime",
                                "-s", "NxpNciX:D", "NxpNciR:D").redirectErrorStream(true).start();
                        int lines = 0;
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(snap.getInputStream()))) {
                            String line;
                            while ((line = in.readLine()) != null) {
                                if (line.startsWith("---------")) continue;
                                lines++;
                                if (rounds[0] == 0 && lines <= 3) emit("SNAPRAW", line);
                                if (!seen.add(line)) continue;
                                int marker = line.indexOf("> ");
                                if (marker < 0) continue;
                                String packet = line.substring(marker + 2).trim().replace(" ", "");
                                if(!cleaned.get()&&line.contains("NxpNciX")&&NciDiscovery.hasQPoll(packet)){qDiscoverySent=true;emit("CAPABILITY","已发出 0x71 Q-Poll 寻卡，等待控制器确认。");}
                                if(!cleaned.get()&&line.contains("NxpNciR")&&packet.toUpperCase(java.util.Locale.ROOT).startsWith("6105")&&halDiscovery&&!qDiscoveryAccepted){
                                    qDiscoveryAccepted=true;
                                    emit("CAPABILITY","Q-Poll 屏已应答：RF 接口激活（0x71 发现类型，入向已还原为 Type A 上报）");
                                }
                                if(!cleaned.get()&&qDiscoverySent&&line.contains("NxpNciR")&&packet.startsWith("410301")&&packet.length()>=8){
                                    if(packet.substring(6,8).equals("00")){qDiscoveryAccepted=true;emit("CAPABILITY","已确认控制器接受 Q-Poll 寻卡（0x71）；目标屏通信与刷新需单独验证。");}
                                    else emit("CAPABILITY","Q-Poll 寻卡被拒绝，响应状态="+packet.substring(6,8));
                                }
                                if(line.contains("NxpNciR")&&packet.toUpperCase(java.util.Locale.ROOT).startsWith("4F7001")&&packet.length()>=8&&!cleaned.get()){
                                    String status=packet.substring(6,8);
                                    if(!status.equals("00")){qtagRejection="系统拒绝 QTag，响应状态="+status+"；当前系统未成功开启 Q-Poll。";stopping.set(true);emit("QTAG_REJECTED",qtagRejection);}
                                }
                                if (line.contains("NxpNciX") && packet.startsWith("2103")) emit("RF_TX", packet);
                                if (line.contains("NxpNciR") && (packet.startsWith("4103") || packet.startsWith("6103") || packet.startsWith("6105"))) emit("RF_RX", packet);
                            }
                        }
                        snap.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); snap.destroy();
                        if (++rounds[0] == 1) emit("SNAP_STATE", "快照轮询第 1 轮读到 " + lines + " 行");
                    } catch (Exception e) { emit("SNAP_ERROR", String.valueOf(e)); }
                    SystemClock.sleep(400);
                }
            }, "nci-monitor");
            monitor.setDaemon(true); monitor.start();
        } catch (Exception e) { emit("LOG_UNAVAILABLE", "无法读取 HAL 日志；仍可观察标签回调：" + e); }
    }
    private static synchronized void cleanup() {
        if (!cleaned.compareAndSet(false, true)) return;
        if(halDiscovery&&readerTouched){
            emit("HOOK_RESTORE_REQUEST","先读取注入统计并关闭兼容开关，再恢复普通寻卡");
            long deadline=SystemClock.elapsedRealtime()+20000;
            while(hookOffAck!=null&&!hookOffAck.exists()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20);
            hookRestored=hookOffAck!=null&&hookOffAck.exists();
            if(!hookRestored)emit("RESTORE_FAILED","未收到 Root 兼容层关闭确认，请重启 NFC");
        }
        if (readerTouched) {
            try { reader(0); readerReleased = true; emit("READER_OFF", "ReaderMode 已释放"); }
            catch (Exception e) { emit("CLEANUP_ERROR", "释放 ReaderMode 失败：" + e); }
        }
        if (qtagTouched) {
            try {
                int status = qtag(0);
                qtagDisabled = status == 0;
                emit(status == 0 ? "QTAG_OFF" : "CLEANUP_ERROR", "关闭 QTag，API 状态=" + status);
            } catch (Exception e) { emit("CLEANUP_ERROR", "关闭 QTag 失败：" + e); }
        }
        SystemClock.sleep(150);
        if (logcat != null) logcat.destroy();
    }
    public static void main(String[] args) {
        int exit = 0;
        boolean sessionStarted = false;
        try {
            if (args.length != 6 || !(args[0].equals("inspect") || args[0].equals("qtag") || args[0].equals("standard")||args[0].equals("identity")||args[0].equals("refresh")))
                throw new IllegalArgumentException("Invalid mode or argument count");
            mode=args[0];
            BackendPolicy.validate(android.os.Process.myUid(),mode,"system");
            if(!args[5].equals("sn220")&&!args[5].equals("system"))throw new IllegalArgumentException("Unknown discovery backend");
            halDiscovery=args[5].equals("sn220")&&BackendPolicy.needsQPoll(mode);
            hookRestored=!halDiscovery;
            boolean panelOperation=mode.equals("identity")||mode.equals("refresh");
            int seconds = Integer.parseInt(args[1]);
            if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("时长超出当前模式允许范围");
            File stopFile = new File(args[2]);
            hookOffAck=new File(stopFile.getParentFile(),"hook-off-ack");
            if (stopFile.exists()) { emit("END", "已取消，未启动寻卡"); return; }
            // Bridge prepares a shell-owned lock; it survives bridge death while this worker runs.
            try (RandomAccessFile lockFile = new RandomAccessFile(args[3], "rw"); FileLock lock = lockFile.getChannel().tryLock()) {
                if (lock == null) throw new IllegalStateException("另一轮测试正在运行");
                try {
                init();
                if (args[0].equals("inspect")) {
                    emit("CHIP",ChipInfo.inspect());
                    adapterType.getMethod("sendVendorNciMessage", int.class, int.class, int.class, byte[].class);
                    emit("READY", "NXP 测试入口存在，可开始测试（尚未验证空口能力）");
                    // --- 0x71 discovery probe: does this firmware accept the NXP private discovery type? ---
                    try {
                        Runtime.getRuntime().addShutdownHook(new Thread(QPollWorker::cleanup));
                        startLogcat();
                        qtagTouched = true;
                        int qs = qtag(1);
                        emit("PROBE", "QTag 命令下发，API 状态=" + qs);
                        readerTouched = true;
                        reader(0x81);
                        emit("PROBE", "ReaderMode 已开启（NFC-A）");
                        java.lang.reflect.Method upd = null;
                        for (java.lang.reflect.Method m : adapterType.getMethods())
                            if (m.getName().equals("updateDiscoveryTechnology")) upd = m;
                        if (upd == null) {
                            emit("PROBE", "updateDiscoveryTechnology 接口不存在");
                        } else {
                            emit("PROBE", "签名=" + java.util.Arrays.toString(upd.getParameterTypes()));
                            Object result;
                            if (upd.getParameterCount() >= 3)
                                result = upd.invoke(service, java.util.Arrays.copyOfRange(new Object[]{token, 0x71, 0, null}, 0, upd.getParameterCount()));
                            else
                                result = upd.invoke(service, token, 0x71);
                            emit("PROBE", "updateDiscoveryTechnology(0x71) 返回=" + result);
                        }
                        SystemClock.sleep(3000);
                        emit("PROBE", "若上方 RF_TX 的 2103 发现包里带 71，或无异常返回，则固件接受该发现类型");
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        emit("PROBE", "updateDiscoveryTechnology(0x71) 被拒：" + e.getCause());
                    } catch (Exception e) {
                        emit("PROBE", "0x71 探测失败：" + e);
                    } finally {
                        cleanup();
                    }
                    emit("END", "探针完成，未发送寻卡以外的任何空口图像指令"); return;
                }
                if (stopFile.exists()) { emit("END", "已取消，未启动寻卡"); return; }
                setupCallback();
                Runtime.getRuntime().addShutdownHook(new Thread(QPollWorker::cleanup));
                startLogcat();
                if ((args[0].equals("qtag")||panelOperation)&&!halDiscovery) {
                    qtagTouched = true;
                    int status = qtag(1);
                    emit("QTAG_ON", "启用 QTag，API 状态=" + status + "（只是主机状态）");
                    if (status != 0) throw new IllegalStateException("QTag 启用失败");
                }
                if(halDiscovery)emit("HOOK","使用 HAL Q-Poll 兼容层，跳过系统 QTag 命令");
                readerTouched = true;
                reader(0x81); // NFC-A + skip NDEF; the NXP extension rewrites discovery to Q_POLL.
                sessionStarted = true;
                emit("START", args[0] + ":" + seconds);
                if (halDiscovery) {
                    // NfcService rejects reader-mode requests from shell processes (foreground check),
                    // so the system may not re-emit RF_DISCOVER on session start. Toggling NFC off/on
                    // forces a full discovery reconfiguration while the HAL hook lease stays active.
                    try {
                        emit("TRIGGER", "切换 NFC 开关以触发发现重配（绕过前台检查）");
                        java.lang.Process p1=new ProcessBuilder("/system/bin/svc","nfc","disable").redirectErrorStream(true).start();
                        p1.getInputStream().read();p1.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);
                        SystemClock.sleep(600);
                        java.lang.Process p2=new ProcessBuilder("/system/bin/svc","nfc","enable").redirectErrorStream(true).start();
                        p2.getInputStream().read();p2.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        emit("TRIGGER_ERROR", "NFC 开关切换失败：" + e);
                    }
                }
                long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
                while (!stopping.get() && !stopFile.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
                if(qtagRejection!=null)throw new IllegalStateException(qtagRejection);
                if((mode.equals("qtag")||panelOperation)&&!qDiscoveryAccepted)emit("CAPABILITY",halDiscovery?"本轮寻卡窗口结束；Q-Poll 判定以上方兼容层统计为准（判定链：改写→控制器响应→屏激活）。":"尚未确认 Q-Poll：未获得 0x71 指令及成功响应的完整证据；请查看日志。");
                if (tagSeen.get()&&panelOperation) {
                    final int scanSeconds=seconds;
                    final File panelJob=new File(args[4]);
                    final byte[] targetUid=detectedTag.getId().clone();
                    final int[] connectionAttempt={0};
                    ConnectionRetry.run(()->{
                        if(stopFile.exists())throw new InterruptedException("已停止，未开始协议通信");
                        if(!Arrays.equals(targetUid,detectedTag.getId()))throw new java.io.IOException("重新寻到不同 UID 的标签，停止；尚未发送 APDU");
                        emit("CONNECT_ATTEMPT",(++connectionAttempt[0])+" / 3");
                        PanelSession.run(detectedTag,mode,panelJob,stopFile,QPollWorker::emit);
                    },()->{
                        if(stopFile.exists())throw new InterruptedException("已停止重新寻卡");
                        emit("RECONNECT","尚未发送 APDU；重新寻卡以获取新标签，最多三次连接尝试");
                        reader(0);
                        SystemClock.sleep(200);
                        detectedTag=null;tagSeen.set(false);stopping.set(false);
                        if(stopFile.exists())throw new InterruptedException("已停止重新寻卡");
                        reader(0x81);
                        emit("START",mode+":"+scanSeconds);
                        long retryDeadline=SystemClock.elapsedRealtime()+scanSeconds*1000L;
                        while(!stopping.get()&&!stopFile.exists()&&SystemClock.elapsedRealtime()<retryDeadline)SystemClock.sleep(20);
                        if(stopFile.exists())throw new InterruptedException("已停止重新寻卡");
                        if(qtagRejection!=null)throw new IllegalStateException(qtagRejection);
                        if(!tagSeen.get())throw new java.io.IOException("连接失败后重新寻卡超时；尚未发送 APDU");
                    });
                    if(mode.equals("identity"))emit("RESULT","D1 屏幕规格与原厂配置校验通过，未写图像");
                }
                else if (tagSeen.get()) emit("RESULT", "已收到标签；只显示元信息，未发送 APDU");
                else if (stopFile.exists()) emit("RESULT", "用户停止；未收到标签");
                else emit("RESULT", "本轮超时，未收到标签；不能据此认定硬件不支持");
                } finally { cleanup(); }
            }
        } catch (InterruptedException e) {
            emit("CANCELLED",e.getMessage());exit=3;
        } catch (Throwable e) {
            emit("ERROR", e.getClass().getSimpleName() + ": " + e.getMessage()); exit = 1;
            java.io.StringWriter trace=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(trace));
            for(String line:trace.toString().split("\n"))emit("TRACE",line.trim());
        } finally {
            cleanup();
            if (readerTouched || qtagTouched) {
                boolean ok = (!readerTouched || readerReleased) && (!qtagTouched || qtagDisabled) && (!halDiscovery || hookRestored);
                emit(ok ? "RESTORED" : "RESTORE_FAILED", ok ? "已恢复普通寻卡" : "恢复未确认，请重启 NFC 开关后再试");
                if (!ok) exit = 2;
            }
            if (sessionStarted || exit != 0) emit("END", "本轮结束");
        }
        System.exit(exit);
    }
}
