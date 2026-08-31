package com.localorderai;

import android.app.Application;

import com.localorderai.utils.AppLogger;

public class LocalOrderAiApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.attachContext(this);
        AppLogger.init(this);
    }
}
