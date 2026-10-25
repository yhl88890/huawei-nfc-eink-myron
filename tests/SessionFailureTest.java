package org.nfcresearch.qpolllab;

public final class SessionFailureTest {
    private static void require(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        SessionFailure f=new SessionFailure();
        f.accept("ERROR","IOException: D1 失败，SW=6985");
        f.accept("PROCESS_EXIT","1");
        require(f.message().contains("SW=6985")&&f.message().contains("退出码=1"));
        f.accept("RESTORE_FAILED","恢复未确认，请重启 NFC");
        require(f.message().contains("SW=6985")&&f.message().contains("恢复未确认"));
        f.clear();require(!f.present());
        f.accept("QTAG_REJECTED","系统拒绝 QTag，响应状态=05");
        f.accept("ERROR","IllegalStateException: 系统拒绝 QTag，响应状态=05");
        f.accept("ERROR","后端进程退出码=1");
        require(f.message().startsWith("系统拒绝 QTag，响应状态=05")&&f.message().contains("退出码=1"));
        f.clear();f.accept("PROCESS_EXIT","137");
        require(f.message().contains("未收到具体错误")&&f.message().contains("137"));
        System.out.println("SessionFailure regression tests passed");
    }
}
