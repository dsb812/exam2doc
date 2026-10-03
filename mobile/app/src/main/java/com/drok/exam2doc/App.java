package com.drok.exam2doc;

import android.app.Application;
import android.content.Context;

/** 提供全局 Context（读取 assets 用）。 */
public class App extends Application {
    private static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static Context context() {
        return instance;
    }
}
