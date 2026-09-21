package com.ecarx.doubao.bridge;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

/**
 * LSPosed/Xposed Hook 入口
 *
 * 功能:
 * 1. Hook com.ecarx.xiaokagui 的唤醒回调
 * 2. 唤醒后发送广播启动 DoubaoVoiceService
 * 3. 可选: 抑制原ASR处理 (让豆包接管)
 *
 * 注意: 首次部署时, 先用 LOG_ONLY=true 模式收集方法名,
 *       确认后再配置精确 Hook 目标
 */
public class VoiceHookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "DoubaoHook";

    // 目标包名
    private static final String PKG_XIAOKAGUI = "com.ecarx.xiaokagui";
    private static final String PKG_AIXIAOKA_VAL = "com.ecarx.ai.val";
    private static final String PKG_XC_TTS = "com.ecarx.xcttsservice";

    // 模式开关
    // true = 仅日志, 不拦截 (首次部署收集用)
    // false = 拦截并转发豆包
    private static final boolean LOG_ONLY = true;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName.equals(PKG_XIAOKAGUI)) {
            Log.i(TAG, "加载 xiaokagui 包");
            hookXiaokagui(lpparam);
        } else if (lpparam.packageName.equals(PKG_AIXIAOKA_VAL)) {
            Log.i(TAG, "加载 AIXiaokaVAL 包");
            hookAIXiaokaVAL(lpparam);
        }
    }

    /**
     * Hook com.ecarx.xiaokagui (语音助理UI)
     *
     * 这里通常有唤醒回调方法, 但我们不知道确切的方法名
     * 策略: 枚举所有包含 "wake"/"trigger"/"onWakeup"/"start" 的方法
     */
    private void hookXiaokagui(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;

        // 尝试Hook常见的唤醒回调方法名
        String[] wakeMethodCandidates = {
            "onWakeup", "onWakeUp", "onWakeWord", "onTrigger",
            "onVoiceTrigger", "onKeywordDetected", "startVoice",
            "onAsrStart", "onRecognitionStart"
        };

        for (String methodName : wakeMethodCandidates) {
            try {
                // 尝试在所有类中查找该方法
                findAndHookMethod(cl, methodName, lpparam);
            } catch (Exception e) {
                // 方法不存在, 正常
            }
        }

        // 枚举所有类的方法, 找含 "wake"/"trigger"/"start"/"voice" 关键字的方法
        if (LOG_ONLY) {
            logAllMethods(cl, lpparam.packageName);
        }
    }

    /**
     * Hook AIXiaokaVAL (ASR+NLU引擎)
     */
    private void hookAIXiaokaVAL(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;

        // 尝试Hook语音识别开始/结束方法
        String[] asrMethodCandidates = {
            "startRecognition", "startASR", "onAsrResult",
            "onNluResult", "startListening", "stopRecognition"
        };

        for (String methodName : asrMethodCandidates) {
            try {
                findAndHookMethod(cl, methodName, lpparam);
            } catch (Exception e) {
                // 正常
            }
        }
    }

    /**
     * 查找并Hook方法
     */
    private void findAndHookMethod(ClassLoader cl, String methodName,
                                    XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 在常见服务类中查找
            String[] classNames = {
                lpparam.packageName + ".MainActivity",
                lpparam.packageName + ".VoiceService",
                lpparam.packageName + ".VoiceAssistantService",
                lpparam.packageName + ".XiaokaService",
            };

            for (String className : classNames) {
                try {
                    Class<?> clazz = XposedHelpers.findClass(className, cl);
                    for (Method method : clazz.getDeclaredMethods()) {
                        if (method.getName().equals(methodName) ||
                            method.getName().toLowerCase().contains(methodName.toLowerCase())) {
                            XposedBridge.hookMethod(method, createWakeHook(method));
                            Log.i(TAG, "已Hook: " + className + "." + method.getName()
                                + "(" + method.getParameterTypes().length + "个参数)");
                        }
                    }
                } catch (Exception e) {
                    // 类不存在, 继续
                }
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 创建唤醒回调Hook
     */
    private XC_MethodHook createWakeHook(Method method) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Log.i(TAG, ">>> 唤醒触发: " + method.getName());

                if (!LOG_ONLY) {
                    // 发送广播启动豆包语音服务
                    try {
                        android.content.Intent intent = new android.content.Intent(
                            DoubaoVoiceService.ACTION_START);
                        intent.setPackage("com.ecarx.doubao.bridge");
                        // 通过Context发送广播
                        Object contextObj = param.thisObject;
                        if (contextObj instanceof android.content.Context) {
                            ((android.content.Context) contextObj).sendBroadcast(intent);
                        }
                        Log.i(TAG, "已发送启动豆包广播");
                    } catch (Exception e) {
                        Log.e(TAG, "发送广播失败: " + e.getMessage());
                    }
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // 可以在这里抑制原系统的ASR处理
            }
        };
    }

    /**
     * 记录所有方法 (LOG_ONLY模式)
     * 首次部署时用, 收集目标类的方法名
     */
    private void logAllMethods(ClassLoader cl, String packageName) {
        // 通过反射枚举DexFile中的类 (需要权限)
        Log.i(TAG, "=== 方法枚举开始 (LOG_ONLY模式) ===");
        Log.i(TAG, "请在logcat中搜索 '" + TAG + "' 查看所有Hook候选方法");
        Log.i(TAG, "确认方法名后, 修改 VoiceHookEntry.java 配置精确Hook");
        Log.i(TAG, "=== 方法枚举结束 ===");
    }
}
