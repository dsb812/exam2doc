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
    }

    public static Context context() {
        return instance;
    }

    public static boolean isOpencvReady() {
        return opencvReady;
    }
}
