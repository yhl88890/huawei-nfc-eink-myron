package org.nfcresearch.qpolllab;
import java.io.*;
import java.util.regex.*;

public final class ChipInfo {
    public static String inspect(){
        for(String path:new String[]{"/odm/etc/libnfc-nxp.conf","/vendor/etc/libnfc-nxp.conf"}){
            try(BufferedReader in=new BufferedReader(new FileReader(path))){String line;while((line=in.readLine())!=null){
                if(line.trim().startsWith("#"))continue;
                Matcher match=Pattern.compile("^\\s*NXP_FW_NAME\\s*=\\s*\"([^\"]+)\"").matcher(line);
                if(match.find()){
                    String firmware=match.group(1);Matcher chip=Pattern.compile("lib([a-zA-Z0-9]+)_fw\\.so").matcher(firmware);
                    return "NXP "+(chip.matches()?chip.group(1).toUpperCase(java.util.Locale.ROOT):firmware)+"\n依据："+path+" / "+firmware+"\n来自系统固件配置，非芯片 ID 实读";
                }
            }}catch(IOException ignored){}
        }
        return "未能从当前可读配置识别，请查看系统日志；不据此判定不支持 NFC 或 Q-Poll。";
    }
}
