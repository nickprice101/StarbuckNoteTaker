package ai.mlc.mlcllm;

import org.apache.tvm.Device;
import org.apache.tvm.Function;
import org.apache.tvm.Module;
import org.apache.tvm.TVMValue;

/**
 * Java bridge to the MLC LLM JSON FFI engine.
 *
 * This class wraps the TVM packed functions that implement the MLC LLM
 * inference backend.  It mirrors the class of the same name found in the
 * reference MLC-LLM Android demo APK and must be kept in sync with the
 * installed {@code libtvm4j_runtime_packed.so}.
 */
public class JSONFFIEngine {

    /** Callback invoked on the stream-back thread for each response chunk. */
    public interface KotlinFunction {
        void invoke(String result);
    }

    private final Module jsonFFIEngine;
    private final Function initBackgroundEngineFunc;
    private final Function reloadFunc;
    private final Function unloadFunc;
    private final Function resetFunc;
    private final Function chatCompletionFunc;
    private final Function abortFunc;
    private final Function getLastErrorFunc;
    private final Function runBackgroundLoopFunc;
    private final Function runBackgroundStreamBackLoopFunc;
    private final Function exitBackgroundLoopFunc;
    private final Device device;

    private Function requestStreamCallback;

    public JSONFFIEngine() {
        this("opencl");
    }

    public JSONFFIEngine(String deviceType) {
        Module engine = Function.getFunction("mlc.json_ffi.CreateJSONFFIEngine").invoke().asModule();
        this.jsonFFIEngine = engine;
        this.initBackgroundEngineFunc        = engine.getFunction("init_background_engine");
        this.reloadFunc                      = engine.getFunction("reload");
        this.unloadFunc                      = engine.getFunction("unload");
        this.resetFunc                       = engine.getFunction("reset");
        this.chatCompletionFunc              = engine.getFunction("chat_completion");
        this.abortFunc                       = engine.getFunction("abort");
        this.getLastErrorFunc                = engine.getFunction("get_last_error");
        this.runBackgroundLoopFunc           = engine.getFunction("run_background_loop");
        this.runBackgroundStreamBackLoopFunc = engine.getFunction("run_background_stream_back_loop");
        this.exitBackgroundLoopFunc          = engine.getFunction("exit_background_loop");
        this.device = createDevice(deviceType);
    }

    /**
     * Initialises the background engine and registers the stream callback.
     *
     * <p>Must be called before {@link #runBackgroundLoop()} and
     * {@link #chatCompletion(String, String)}.
     *
     * @param callback Receives streamed response JSON chunks on the stream-back thread.
     */
    public void initBackgroundEngine(final KotlinFunction callback) {
        this.requestStreamCallback = Function.convertFunc(args -> {
            callback.invoke(args[0].asString());
            return 1;
        });
        initBackgroundEngineFunc
                .pushArg(device.deviceType)
                .pushArg(device.deviceId)
                .pushArg(requestStreamCallback)
                .invoke();
    }

    private static Device createDevice(String deviceType) {
        String normalized = deviceType == null ? "opencl" : deviceType.trim().toLowerCase();
        switch (normalized) {
            case "cpu":
            case "llvm":
                return Device.cpu();
            case "vulkan":
                return Device.vulkan();
            case "opencl":
            case "cl":
            case "":
                return Device.opencl();
            default:
                throw new IllegalArgumentException("Unsupported MLC device type: " + deviceType);
        }
    }

    /** Loads (or hot-swaps) a model using the given engine-config JSON. */
    public void reload(String engineConfigJson) {
        reloadFunc.pushArg(engineConfigJson).invoke();
    }

    /**
     * Submits a chat-completion request.
     *
     * @param requestJson JSON-encoded {@code ChatCompletionRequest}.
     * @param requestId   Unique request identifier; echoed back in each response chunk.
     */
    public boolean chatCompletion(String requestJson, String requestId) {
        return chatCompletionFunc.pushArg(requestJson).pushArg(requestId).invoke().asLong() != 0L;
    }

    /** Returns the native JSON FFI parser/validation error from the last rejected request. */
    public String getLastError() {
        return getLastErrorFunc.invoke().asString();
    }

    /** Aborts an in-flight chat-completion request. */
    public void abort(String requestId) {
        abortFunc.pushArg(requestId).invoke();
    }

    /** Blocks until the background inference loop exits (call after {@link #exitBackgroundLoop()}). */
    public void runBackgroundLoop() {
        runBackgroundLoopFunc.invoke();
    }

    /** Blocks until the background stream-back loop exits. */
    public void runBackgroundStreamBackLoop() {
        runBackgroundStreamBackLoopFunc.invoke();
    }

    /** Signals the background loops to stop. */
    public void exitBackgroundLoop() {
        exitBackgroundLoopFunc.invoke();
    }

    /** Unloads the current model and frees GPU memory. */
    public void unload() {
        unloadFunc.invoke();
    }

    /** Resets the KV-cache / conversation history without unloading the model. */
    public void reset() {
        resetFunc.invoke();
    }
}
