package yph.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import yph.constant.Constant;
import yph.filter.AnrFilter;
import yph.filter.CpuFilter;
import yph.filter.CrashFilter;

import static yph.utils.RuntimeUtil.execAsync;

/**
 * Created by _yph on 2018/3/15 0015.
 */

public class CmdUtil {

    private CmdUtil() {
    }

    public static CmdUtil get() {
        return Singleton.instance;
    }

    private static class Singleton {
        static CmdUtil instance = new CmdUtil();
    }

    private final String adb = SystemEnvUtil.getCopyAdb();
    //    private final String adb = SystemEnvUtil.getResCopyAdb();
    private String pkgName;

    public void init(String pkgName) {
        this.pkgName = pkgName;
    }

    public List<String> getDevices() {
        List<String> devices = new ArrayList<>();
        List<String> results = RuntimeUtil.exec(adb + " devices", Constant.GET_DEVICES);
        Log.i(results);
        if (results.size() > 0) {
            for (int i = 0; i < results.size(); i++) {
                String deviceUdid = results.get(i);
                deviceUdid = deviceUdid.substring(0, deviceUdid.indexOf("\t"));
                devices.add(deviceUdid);
            }
        } else {
            throw new IllegalStateException("Can't find devices");
        }
        return devices;
    }

    public String getPlatformVersion(String deviceUdid) {
        List<String> results = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell getprop ro.build.version.release", "Get " + deviceUdid + " Platform Version");
        Log.i(results);
        return results.get(0);
    }

    public int getPlatformVersionSdk(String deviceUdid) {
        int sdkNum = Integer.parseInt(RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell getprop ro.build.version.sdk", "").get(0));
        return sdkNum;
    }

    public void installApk(String deviceUdid, String apkPath) {
        RuntimeUtil.exec(adb + " -s " + deviceUdid + " install " + apkPath, "Install App");
    }

    public int getVersionCode(String deviceUdid) {
        List<String> results = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell dumpsys package " + pkgName + " | findstr versionCode");
        String versionCode = "0";
        if (results.size() > 0) {
            versionCode = results.get(0);
            versionCode = versionCode.substring(versionCode.indexOf("versionCode="), versionCode.indexOf(" targetSdk"));
        }
        return Integer.valueOf(versionCode);
    }

    public String getDeviceName(String deviceUdid) {
        List<String> results = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell getprop ro.product.model", "Get " + deviceUdid + " Device`s Name");
        Log.i(results);
        return results.get(0);
    }

    public Timer getCpu(String deviceUdid, RuntimeUtil.AsyncInvoke asyncInvoke) {
        return execAsync(adb + " -s " + deviceUdid + " shell top -n 1 -s  cpu|grep " + pkgName, asyncInvoke, 1, CpuFilter.get());
    }

    public CpuSnapshot getCpu(String deviceUdid , int pid) {
        try {
            String sysCpu = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell cat /proc/stat |head -n 1").get(0);
            String[] cpuInfoArray = sysCpu.split(" ");
            if (cpuInfoArray.length < 9) {
                throw new IllegalStateException("cpu info array size must great than 9");
            }
            long user = Long.parseLong(cpuInfoArray[2]);
            long nice = Long.parseLong(cpuInfoArray[3]);
            long system = Long.parseLong(cpuInfoArray[4]);
            long idle = Long.parseLong(cpuInfoArray[5]);
            long ioWait = Long.parseLong(cpuInfoArray[6]);
            long total = user + nice + system + idle + ioWait
                    + Long.parseLong(cpuInfoArray[7])
                    + Long.parseLong(cpuInfoArray[8]);
            String appCpu = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell cat /proc/" + pid + "/stat |head -n 1").get(0);
            String[] pidCpuInfoList = appCpu.split(" ");
            if (pidCpuInfoList.length < 17) {
                throw new IllegalStateException("pid cpu info array size must great than 17");
            }
            long appCpuTime = Long.parseLong(pidCpuInfoList[13])
                    + Long.parseLong(pidCpuInfoList[14])
                    + Long.parseLong(pidCpuInfoList[15])//有些不要15，16 待考究
                    + Long.parseLong(pidCpuInfoList[16]);//有些不要15，16 待考究
            return new CpuSnapshot(user, system, idle, ioWait, total, appCpuTime);
        }catch (Exception e){
            return null;
        }
    }

    public long getTraffic(String deviceUdid, int uid) {
        long rcvTraffic = -1, sndTraffic = -1;
        List<String> results = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell cat /proc/uid_stat/" + uid + "/tcp_rcv");
        if (!results.isEmpty()) {
            rcvTraffic = Long.parseLong(results.get(0));
        }
        results = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell cat /proc/uid_stat/" + uid + "/tcp_snd");
        if (!results.isEmpty()) {
            sndTraffic = Long.parseLong(results.get(0));
        }
        return rcvTraffic + sndTraffic < 0 ? -1 : rcvTraffic + sndTraffic;
    }

    public int getMem(String deviceUdid) {
        List<String> results = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell dumpsys meminfo " + pkgName + "|grep TOTAL ");
        if (results.size() > 0) {
            String mem = results.get(0).replace("TOTAL", "");
            return Integer.valueOf(getWordBetweenBlank(mem)) / 1000;
        }
        return -1;
    }

    public List<String> getCrashLog(String deviceUdid) {
        List<String> results = new ArrayList<>();
        try {
            final Process process = Runtime.getRuntime().exec(adb + " -s " + deviceUdid + " shell logcat -v process *:E");
            final Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    process.destroy();
                    timer.cancel();
                }
            }, 500);
            results.addAll(RuntimeUtil.exec(process, Constant.CHECK_CRASH, new CrashFilter()));
            CmdUtil.get().clearLog(deviceUdid);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return results;
    }

    public void clearLog(String deviceUdid) {
        RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell logcat -c");
    }

    public String getAnrLog(String deviceUdid) {
        String AnrLog = "";
        List<String> results = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell cat /data/anr/traces.txt | grep -B 1 'Cmd line: " + pkgName + "'", Constant.CHECK_ANR);
        if (results.size() > 0) {
            String time = results.get(0);
            time = time.substring(time.indexOf("at") + 3, time.lastIndexOf(":") + 3);
            long l = TimeUtil.timeSubtract(time);
            if (l != -1 && l < 2) {//2分钟之内的anr
                results = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell cat /data/anr/traces.txt", "", new AnrFilter());
                for (String s : results) {
                    AnrLog = AnrLog + s + "\n";
                }
            }
        }
        return AnrLog;
    }

    public String getWordBetweenBlank(String mem) {
        String menTem = "";
        char[] chars = mem.toCharArray();
        boolean b = false;
        for (char c : chars) {
            if (c == ' ') {
                if (b) {
                    break;
                }
            } else {
                menTem = menTem + c;
                b = true;
            }
        }
        return menTem.trim();
    }

    public String execPs(String deviceUdid) {
        try {
            List<String> results = RuntimeUtil.exec(adb + " -s " + deviceUdid + " shell ps " + (getPlatformVersionSdk(deviceUdid) > 25 ? "-A " : "") +
                    "|grep " + pkgName + " |grep -v :");
            return results.get(0);
        } catch (Exception e) {
            return "";
        }
    }

    public boolean isProcessRunning(String keyMsg) {
        boolean running;
        List<String> results = RuntimeUtil.exec("cmd.exe /c netstat -ano|findstr " + keyMsg);
        if (results.isEmpty()) {
            running = false;
        } else {
            running = true;
        }
        return running;
    }

    public void killProcessIfExist(String keyMsg) {
        List<String> results = RuntimeUtil.exec("cmd.exe /c netstat -ano|findstr " + keyMsg);
        if (!results.isEmpty()) {
            RuntimeUtil.exec("cmd.exe /c taskkill /f /pid " + results.get(0).substring(results.get(0).lastIndexOf(" ")));
        }
    }

}
