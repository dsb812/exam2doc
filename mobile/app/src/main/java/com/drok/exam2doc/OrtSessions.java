package com.drok.exam2doc;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** ONNX Runtime 会话工厂。NNAPI（GPU/DSP/NPU）为实验性开关，默认走 CPU 以保证稳定。 */
public final class OrtSessions {

    public static OrtSession create(OrtEnvironment env, byte[] model, boolean useNnapi) {
        if (useNnapi) {
            try {
                OrtSession.SessionOptions so = new OrtSession.SessionOptions();
                so.addNnapi();
                return env.createSession(model, so);
            } catch (Throwable t) {
                // NNAPI 不可用/初始化失败 → 回退 CPU
            }
        }
        try {
            return env.createSession(model, new OrtSession.SessionOptions());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private OrtSessions() { }
}
