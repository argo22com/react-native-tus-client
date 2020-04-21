package com.vinzscam.rntusclient;

import android.content.SharedPreferences;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.tus.android.client.TusPreferencesURLStore;
import io.tus.java.client.ProtocolException;
import io.tus.java.client.TusClient;
import io.tus.java.client.TusExecutor;
import io.tus.java.client.TusUpload;
import io.tus.java.client.TusUploader;

public class RNTusClientModule extends ReactContextBaseJavaModule {

    private final String ON_ERROR = "onError";
    private final String ON_SUCCESS = "onSuccess";
    private final String ON_PROGRESS = "onProgress";

    private final ReactApplicationContext reactContext;
    private Map<String, TusRunnable> executorsMap;
    private ExecutorService pool;

    public RNTusClientModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        this.executorsMap = new HashMap<>();
        pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    @Override
    public String getName() {
        return "RNTusClient";
    }

    @ReactMethod
    public void createUpload(String fileUrl, ReadableMap options, Callback callback) {
        String endpoint = options.getString("endpoint");
        int chunkSize = options.getInt("chunkSize");
        int requestPayloadSize = options.getInt("requestPayloadSize");
        Map<String, Object> rawHeaders = options.getMap("headers").toHashMap();
        Map<String, Object> rawMetadata = options.getMap("metadata").toHashMap();

        Map<String, String> metadata = new HashMap<>();
        for (String key : rawMetadata.keySet()) {
            metadata.put(key, String.valueOf(rawMetadata.get(key)));
        }
        Map<String, String> headers = new HashMap<>();
        for (String key : rawHeaders.keySet()) {
            headers.put(key, String.valueOf(rawHeaders.get(key)));
        }

        try {
            String uploadId = UUID.randomUUID().toString();
            TusRunnable executor = new TusRunnable(fileUrl, uploadId, endpoint, metadata, headers, chunkSize, requestPayloadSize);
            this.executorsMap.put(uploadId, executor);
            callback.invoke(uploadId);
        } catch (FileNotFoundException | MalformedURLException e) {
            callback.invoke(null, e.getMessage());
        }
    }

    @ReactMethod
    public void resume(String uploadId, String fileUrl, ReadableMap options, Callback callback) {
        TusRunnable executor = this.executorsMap.get(uploadId);
        if (executor != null) {
            pool.submit(executor);
            callback.invoke(true);
        } else {
            try {
                // Recreate the executor
                String endpoint = options.getString("endpoint");
                int chunkSize = options.getInt("chunkSize");
                int requestPayloadSize = options.getInt("requestPayloadSize");
                Map<String, Object> rawHeaders = options.getMap("headers").toHashMap();
                Map<String, Object> rawMetadata = options.getMap("metadata").toHashMap();

                Map<String, String> metadata = new HashMap<>();
                for (String key : rawMetadata.keySet()) {
                    metadata.put(key, String.valueOf(rawMetadata.get(key)));
                }
                Map<String, String> headers = new HashMap<>();
                for (String key : rawHeaders.keySet()) {
                    headers.put(key, String.valueOf(rawHeaders.get(key)));
                }
                TusRunnable newExecutor = new TusRunnable(fileUrl, uploadId, endpoint, metadata, headers, chunkSize, requestPayloadSize);
                this.executorsMap.put(uploadId, newExecutor);
                pool.submit(newExecutor);
                callback.invoke(true);
            } catch (FileNotFoundException | MalformedURLException e) {
                callback.invoke(false);
            }
        }
    }

    @ReactMethod
    public void abort(String uploadId, Callback callback) {
        try {
            TusRunnable executor = this.executorsMap.get(uploadId);
            if (executor != null) {
                executor.finish();
            }
            callback.invoke((Object) null);
        } catch (IOException | ProtocolException e) {
            callback.invoke(e.toString());
        }
    }

    class TusRunnable extends TusExecutor implements Runnable {
        private TusUpload upload;
        private TusUploader uploader;
        private String uploadId;
        private TusClient client;
        private boolean shouldFinish;
        private boolean isRunning;
        private int chunkSize;
        private int requestPayloadSize;
        private Timer progressTicker;

        public TusRunnable(String fileUrl,
                           String uploadId,
                           String endpoint,
                           Map<String, String> metadata,
                           Map<String, String> headers,
                           int chunkSize,
                           int requestPayloadSize
        ) throws FileNotFoundException, MalformedURLException {
            this.uploadId = uploadId;
            this.chunkSize = chunkSize;
            this.requestPayloadSize = requestPayloadSize;

            client = new TusClient();
            client.setUploadCreationURL(new URL(endpoint));

            SharedPreferences pref = getReactApplicationContext().getSharedPreferences("tus", 0);
            client.enableResuming(new TusPreferencesURLStore(pref));
            client.setHeaders(headers);

            File file = new File(fileUrl);
            upload = new TusUpload((file));
            upload.setMetadata(metadata);

            shouldFinish = false;
            isRunning = false;
        }

        protected void makeAttempt() throws ProtocolException, IOException {
            uploader = client.resumeOrCreateUpload(upload);
            uploader.setChunkSize(chunkSize);
            uploader.setRequestPayloadSize(requestPayloadSize);

            progressTicker = new Timer();

            progressTicker.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    sendProgressEvent(upload.getSize(), uploader.getOffset());
                }
            }, 0, 500);

            do {
            } while (uploader.uploadChunk() > -1 && !shouldFinish);

            progressTicker.cancel();
            sendProgressEvent(upload.getSize(), uploader.getOffset());
            uploader.finish();
        }

        private void sendProgressEvent(long bytesTotal, long bytesUploaded) {
            WritableMap params = Arguments.createMap();

            params.putString("uploadId", uploadId);
            params.putDouble("bytesWritten", bytesUploaded);
            params.putDouble("bytesTotal", bytesTotal);

            reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(ON_PROGRESS, params);
        }


        public void finish() throws ProtocolException, IOException {
            if (isRunning) {
                shouldFinish = true;
            } else {
                if (progressTicker != null) {
                    progressTicker.cancel();
                }
                if (uploader != null) {
                    uploader.finish();
                }
            }
        }

        @Override
        public void run() {
            isRunning = true;
            try {
                makeAttempts();
                String uploadUrl = uploader.getUploadURL().toString();
                executorsMap.remove(this.uploadId);
                WritableMap params = Arguments.createMap();
                params.putString("uploadId", uploadId);
                params.putString("uploadUrl", uploadUrl);
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit(ON_SUCCESS, params);
            } catch (ProtocolException | IOException e) {
                if (progressTicker != null) {
                    progressTicker.cancel();
                }
                WritableMap params = Arguments.createMap();
                params.putString("uploadId", uploadId);
                params.putString("error", e.toString());
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit(ON_ERROR, params);
            }
            isRunning = false;
        }
    }
}
