package com.drok.exam2doc;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** ONNX Runtime 会话工厂：优先 NNAPI（Android 自动调度 GPU/DSP/NPU），失败回退 CPU。 */
public final class OrtSessions {

    public static OrtSession create(OrtEnvironment env, byte[] model) {
        try {
            OrtSession.SessionOptions so = new OrtSession.SessionOptions();
            so.addNnapi();
            return env.createSession(model, so);
        } catch (Throwable t) {
            // NNAPI 不可用或初始化失败 → 纯 CPU
            try {
                return env.createSession(model, new OrtSession.SessionOptions());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private OrtSessions() { }
}
