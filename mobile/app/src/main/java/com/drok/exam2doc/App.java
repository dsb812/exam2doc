package com.drok.exam2doc;

import android.app.Application;
import android.content.Context;

import org.opencv.android.OpenCVLoader;

/** 提供全局 Context，并在应用启动时最先加载 OpenCV 原生库。 */
public class App extends Application {
    private static App instance;
    private static boolean opencvReady;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            opencvReady = OpenCVLoader.initLocal();
        } catch (Throwable t) {
            opencvReady = false;
        }
        // v1.1.2 一次性重置：让所有用户先回到稳定 CPU 模式，验证基础流程后再手动开 GPU
        android.content.SharedPreferences p =
                getSharedPreferences("exam2doc", MODE_PRIVATE);
        if (!p.getBoolean("nnapi_reset_112", false)) {
            p.edit().putBoolean("nnapi", false)
                    .putBoolean("nnapi_reset_112", true).apply();
        }
    }

    public static Context context() {
        return instance;
    }

    public static boolean isOpencvReady() {
        return opencvReady;
    }
}
