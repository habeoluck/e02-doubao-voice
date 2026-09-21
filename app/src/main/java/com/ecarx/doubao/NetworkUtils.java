package com.ecarx.doubao.bridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

/**
 * 网络工具 - 检测网络可用性, 判断是否需要降级到离线引擎
 */
public class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    /**
     * 检查网络是否可用
     */
    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    /**
     * 检查是否是弱网 (延迟测试)
     * 豆包API需要稳定网络, 弱网时降级
     */
    public static boolean isWeakNetwork() {
        try {
            long start = System.currentTimeMillis();
            Process p = Runtime.getRuntime().exec("ping -c 3 -W 2 openspeech.bytedance.com");
            int exitCode = p.waitFor();
            long elapsed = System.currentTimeMillis() - start;
            if (exitCode != 0) {
                Log.w(TAG, "ping失败, 判定为弱网");
                return true;
            }
            if (elapsed > 3000) {
                Log.w(TAG, "延迟过高(" + elapsed + "ms), 判定为弱网");
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "网络检测异常, 判定为弱网: " + e.getMessage());
            return true;
        }
    }

    /**
     * 快速检测豆包服务可达性
     */
    public static boolean isDoubaoReachable() {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("openspeech.bytedance.com", 443), 3000);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
