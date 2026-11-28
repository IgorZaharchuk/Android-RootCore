package com.rootcore;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;

import com.rootcore.RootCoreRequest;
import com.rootcore.RootCoreResponse;
import com.rootcore.ipc.IRootCoreManager;
import com.rootcore.ipc.IRootCoreClient;
import com.rootcore.internal.RootCoreCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;

public final class RootCoreSession implements AutoCloseable {

    private final Context context;
    private final java.lang.Process rootProcess;
    private final String sessionId;
    private final BroadcastReceiver receiver;
    private final HandlerThread receiverThread;
    private final IRootCoreManager remote;
    private final IBinder localToken;
    private final IBinder.DeathRecipient deathRecipient;
    private final AtomicBoolean binderAlive;

    private volatile boolean closed;
    private final Object stateLock = new Object();
    private final ExecutorService binderExecutor;

    private RootCoreSession(
            Context context,
            java.lang.Process rootProcess,
            String sessionId,
            BroadcastReceiver receiver,
            HandlerThread receiverThread,
            IRootCoreManager remote,
            IBinder localToken,
            IBinder.DeathRecipient deathRecipient,
            AtomicBoolean binderAlive
    ) {
        this.context = context;
        this.rootProcess = rootProcess;
        this.sessionId = sessionId;
        this.receiver = receiver;
        this.receiverThread = receiverThread;
        this.remote = remote;
        this.localToken = localToken;
        this.deathRecipient = deathRecipient;
        this.binderAlive = binderAlive;
        this.binderExecutor = new ThreadPoolExecutor(BINDER_EXECUTOR_THREADS, BINDER_EXECUTOR_THREADS, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(BINDER_EXECUTOR_QUEUE_CAPACITY),
                task -> {
                    Thread thread = new Thread(
                            task,
                            "RootCoreSession-" + sessionId
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    static RootCoreSession open(
            Context context,
            long timeoutMs,
            RootCorePolicy policy
    ) throws Exception {
        if (policy == null) {
            throw new IllegalArgumentException("RootCore policy is null");
        }
        String encodedPolicy = policy.encode();
        File jar = installBootstrapAsset(context);

        int uid = Process.myUid();
        int userId = uid / 100000;
        String packageName = context.getPackageName();
        String sessionId = UUID.randomUUID().toString();
        String action = packageName + ".ROOTCORE_BIND." + sessionId;
        File configFile = createSessionConfig(
                context, sessionId, encodedPolicy
        );

        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<IRootCoreManager> remoteRef = new AtomicReference<>();
        AtomicReference<IBinder> tokenRef = new AtomicReference<>();
        AtomicReference<IBinder.DeathRecipient> deathRef = new AtomicReference<>();
        AtomicReference<Throwable> connectionError = new AtomicReference<>();
        AtomicBoolean binderAlive = new AtomicBoolean(false);

        HandlerThread receiverThread = new HandlerThread(
                "RootCoreReceiver-" + sessionId
        );
        receiverThread.start();
        Handler receiverHandler = new Handler(receiverThread.getLooper());

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                android.util.Log.i(
                        "RootCore",
                        "ROOTCORE_BROADCAST_RECEIVED session=" + sessionId.substring(0, 8)
                                + " thread=" + Thread.currentThread().getName()
                );

                if (!action.equals(intent.getAction())) {
                    android.util.Log.w(
                            "RootCore",
                            "Ignoring Binder broadcast with unexpected action"
                    );
                    return;
                }

                if (Build.VERSION.SDK_INT >= 34) {
                    int senderUid = getSentFromUid();
                    if (senderUid != 0) {
                        android.util.Log.w(
                                "RootCore",
                                "Ignoring Binder broadcast: expected root UID 0, got "
                                        + senderUid
                        );
                        return;
                    }
                }

                if (!sessionId.equals(intent.getStringExtra("extra.session_id"))) {
                    android.util.Log.w(
                            "RootCore",
                            "Ignoring Binder broadcast for another session"
                    );
                    return;
                }
                boolean accepted = false;
                try {
                    Bundle extras = intent.getBundleExtra("extra.bundle");
                    IBinder binder = extras == null ? null : extras.getBinder("binder");
                    if (binder == null) {
                        throw new RemoteException("Root Core broadcast has no Binder");
                    }

                    IRootCoreManager manager =
                            IRootCoreManager.Stub.asInterface(binder);
                    if (manager == null) {
                        throw new RemoteException("Root Core Binder interface is unavailable");
                    }

                    remoteRef.set(manager);

                    int rootUid = manager.ping();
                    if (rootUid != 0) {
                        throw new SecurityException(
                                "Root Core Binder UID is " + rootUid
                        );
                    }

                    AtomicInteger verifiedRootUid = new AtomicInteger(-1);
                    IRootCoreClient verifier = new IRootCoreClient.Stub() {
                        @Override
                        public int verifyRootPeer() {
                            int callingUid = Binder.getCallingUid();
                            verifiedRootUid.set(callingUid);
                            return callingUid;
                        }
                    };

                    IBinder token = verifier.asBinder();
                    tokenRef.set(token);
                    manager.connect(verifier);

                    if (verifiedRootUid.get() != 0) {
                        throw new SecurityException(
                                "Root Core callback was not authenticated as UID 0"
                        );
                    }

                    android.util.Log.i(
                            "RootCore",
                            "ROOTCORE_BROADCAST_ACCEPTED session="
                                    + sessionId.substring(0, 8)
                    );

                    IBinder.DeathRecipient deathRecipient = () -> {
                        binderAlive.set(false);
                        remoteRef.set(null);
                        connectionError.compareAndSet(
                                null,
                                new RemoteException("Root Core Binder died during connection")
                        );
                        connected.countDown();
                    };

                    remoteRef.set(manager);
                    tokenRef.set(token);
                    deathRef.set(deathRecipient);

                    binderAlive.set(true);
                    binder.linkToDeath(deathRecipient, 0);

                    if (!binder.isBinderAlive()) {
                        throw new RemoteException(
                                "Root Core Binder died before connection completed"
                        );
                    }

                    accepted = true;
                } catch (Throwable broadcastError) {
                    binderAlive.set(false);
                    remoteRef.set(null);
                    tokenRef.set(null);
                    deathRef.set(null);
                    connectionError.compareAndSet(null, broadcastError);
                    connected.countDown();

                    android.util.Log.e(
                            "RootCore",
                            "Failed to accept Root Core Binder broadcast",
                            broadcastError
                    );
                } finally {
                    if (accepted) {
                        connected.countDown();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(action);
        java.lang.Process process = null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                        receiver, filter, null, receiverHandler,
                        Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(receiver, filter, null, receiverHandler);
            }
            String command =
                    "CLASSPATH=" + shellQuote(jar.getAbsolutePath()) +
                    " exec app_process /system/bin " +
                    "com.rootcore.bootstrap.RootServerMain " +
                    shellQuote(packageName) + " " +
                    uid + " " + userId + " " +
                    shellQuote(configFile.getAbsolutePath()) +
                    " >/dev/null 2>&1";

            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .redirectOutput(new File("/dev/null"))
                    .start();
            process.getOutputStream().close();

            boolean arrived = connected.await(timeoutMs, TimeUnit.MILLISECONDS);

            if (!arrived) {
                Throwable cause = connectionError.get();
                if (cause != null) {
                    throw new IOException(
                            "Failed to accept Root Core Binder broadcast before timeout",
                            cause
                    );
                }
                throw new TimeoutException("Timed out waiting for Root Core Binder");
            }

            IRootCoreManager manager = remoteRef.get();
            IBinder token = tokenRef.get();
            IBinder.DeathRecipient death = deathRef.get();

            if (manager == null || token == null || death == null || !process.isAlive()) {
                Throwable cause = connectionError.get();
                if (cause != null) {
                    throw new IOException(
                            "Failed to establish Root Core Binder connection", cause
                    );
                }
                throw new IOException("Root Core process exited before session became ready");
            }

            return new RootCoreSession(
                    context, process, sessionId, receiver, receiverThread,
                    manager, token, death, binderAlive
            );
        } catch (Throwable error) {
            IRootCoreManager candidate = remoteRef.getAndSet(null);
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (Throwable closeError) {
                    error.addSuppressed(closeError);
                }
            }

            tokenRef.set(null);
            deathRef.set(null);

            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }

            if (configFile.exists() && !configFile.delete()) {
                android.util.Log.w(
                        "RootCore", "Could not delete bootstrap config"
                );
            }

            if (process != null) terminateProcess(process);
            receiverThread.quitSafely();
            throw error;
        }
    }

    private static final int BINDER_EXECUTOR_THREADS = 4;
    private static final int BINDER_EXECUTOR_QUEUE_CAPACITY = 64;
    private static final long BINDER_CALL_TIMEOUT_MS = 25_000L;

    public RootCoreResponse execute(RootCoreRequest request)
            throws RemoteException {
        if (request == null) throw new IllegalArgumentException("Request is null");
        Bundle encoded = RootCoreCodec.encodeRequest(request);
        Bundle raw = callRemote("execute", () -> remote.execute(encoded));
        RootCoreResponse response = RootCoreCodec.decodeResponse(raw);
        if (!request.getRequestId().equals(response.getRequestId())) {
            throw new RemoteException("RootCore response requestId mismatch");
        }
        return response;
    }

    public int ping() throws RemoteException {
        return callRemote("ping", () -> remote.ping());
    }

    /**
     * Creates an instance of a client application class in the root process.
     * The returned object is a remote handle, not a local Java instance.
     */
    public <T> RootObject<T> createObject(
            Class<T> type,
            RootCoreArguments arguments
    ) throws RemoteException {
        if (type == null) throw new IllegalArgumentException("Class is null");
        RootCoreArguments actual = arguments == null
                ? RootCoreArguments.empty() : arguments;

        Map<String, RootCoreValue> args = new LinkedHashMap<>();
        args.put("className", RootCoreValue.of(
                RootCoreArguments.typeName(type)
        ));
        args.put("parameterTypes", RootCoreValue.array(
                encodeTypeNames(actual.parameterTypes())
        ));
        args.put("arguments", RootCoreValue.array(
                actual.encodedValues(sessionId)
        ));

        RootCoreValue result = executeApiOperation("CREATE_OBJECT", args);
        return new RootObject<>(this, result);
    }

    public <T> RootObject<T> createObject(Class<T> type)
            throws RemoteException {
        return createObject(type, RootCoreArguments.empty());
    }

    private List<RootCoreValue> encodeTypeNames(List<Class<?>> types) {
        List<RootCoreValue> result = new ArrayList<>();
        for (Class<?> type : types) {
            result.add(RootCoreValue.of(RootCoreArguments.typeName(type)));
        }
        return result;
    }

    RootCoreValue invokeObject(
            RootCoreValue object,
            String methodName,
            RootCoreArguments arguments
    ) throws RemoteException {
        Map<String, RootCoreValue> args = new LinkedHashMap<>();
        args.put("object", object);
        args.put("methodName", RootCoreValue.of(methodName));
        args.put("parameterTypes", RootCoreValue.array(
                encodeTypeNames(arguments.parameterTypes())
        ));
        args.put("arguments", RootCoreValue.array(
                arguments.encodedValues(sessionId)
        ));
        return executeApiOperation("INVOKE_METHOD", args);
    }

    RootCoreValue getObjectField(
            RootCoreValue object, String fieldName
    ) throws RemoteException {
        Map<String, RootCoreValue> args = new LinkedHashMap<>();
        args.put("object", object);
        args.put("fieldName", RootCoreValue.of(fieldName));
        return executeApiOperation("GET_FIELD", args);
    }

    void setObjectField(
            RootCoreValue object, String fieldName, Object value
    ) throws RemoteException {
        Map<String, RootCoreValue> args = new LinkedHashMap<>();
        args.put("object", object);
        args.put("fieldName", RootCoreValue.of(fieldName));
        args.put("value", RootCoreArguments.encode(value, sessionId));
        executeApiOperation("SET_FIELD", args);
    }

    void releaseObject(RootCoreValue object) throws RemoteException {
        Map<String, RootCoreValue> args = new LinkedHashMap<>();
        args.put("object", object);
        executeApiOperation("RELEASE_OBJECT", args);
    }

    private RootCoreValue executeApiOperation(
            String operation, Map<String, RootCoreValue> arguments
    ) throws RemoteException {
        RootCoreRequest request = new RootCoreRequest(
                UUID.randomUUID().toString(), operation, arguments
        );
        RootCoreResponse response = execute(request);
        if (!response.isSuccess()) {
            throw new RootCoreException(response.getError());
        }
        return response.getResult();
    }

    Object decodeApiValue(RootCoreValue value) {
        if (value == null) return null;
        switch (value.getKind()) {
            case NULL:
            case VOID:
                return null;
            case BOOLEAN: return value.asBoolean();
            case BYTE: return value.asByte();
            case SHORT: return value.asShort();
            case INT: return value.asInt();
            case LONG: return value.asLong();
            case FLOAT: return value.asFloat();
            case DOUBLE: return value.asDouble();
            case STRING: return value.asString();
            case BYTE_ARRAY: return value.asByteArray();
            case OBJECT_REF:
                return new RootObject<>(this, value);
            case ARRAY:
            case LIST: {
                List<Object> result = new ArrayList<>();
                for (RootCoreValue item : value.asSequence()) {
                    result.add(decodeApiValue(item));
                }
                return result;
            }
            case MAP:
            case STRUCT: {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<String, RootCoreValue> entry
                        : value.asFields().entrySet()) {
                    result.put(entry.getKey(), decodeApiValue(entry.getValue()));
                }
                return result;
            }
            case EXTENSION:
                return value;
            default:
                throw new IllegalArgumentException(
                        "Unsupported RootCore result kind: " + value.getKind()
                );
        }
    }














    private static RemoteException remoteException(
            String message,
            Throwable cause
    ) {
        RemoteException exception = new RemoteException(message);
        exception.initCause(cause);
        return exception;
    }

    private <T> T callRemote(
            String operation,
            Callable<T> call
    ) throws RemoteException {
        return callRemote(operation, BINDER_CALL_TIMEOUT_MS, call);
    }

    private <T> T callRemote(
            String operation,
            long timeoutMs,
            Callable<T> call
    ) throws RemoteException {
        Future<T> future;
        synchronized (stateLock) {
            ensureOpen();
            try {
                future = binderExecutor.submit(() -> {
                String tag = "RootCore";
                String prefix = "CLIENT_BINDER_CALL ";
                android.util.Log.i(
                        tag,
                        prefix + "BEGIN operation=" + operation
                                + " session=" + sessionId.substring(0, 8)
                                + " thread=" + Thread.currentThread().getName()
                );
                try {
                    T result = call.call();
                    android.util.Log.i(
                            tag,
                            prefix + "RETURN operation=" + operation
                                    + " session=" + sessionId.substring(0, 8)
                                    + " resultNull=" + (result == null)
                    );
                    return result;
                } catch (Exception error) {
                    android.util.Log.e(
                            tag,
                            prefix + "THROW operation=" + operation
                                    + " session=" + sessionId.substring(0, 8)
                                    + " type=" + error.getClass().getName()
                                    + " message=" + error.getMessage(),
                            error
                    );
                    throw error;
                } catch (Error error) {
                    android.util.Log.e(
                            tag,
                            prefix + "ERROR operation=" + operation
                                    + " session=" + sessionId.substring(0, 8)
                                    + " type=" + error.getClass().getName(),
                            error
                    );
                    throw error;
                }
                });
            } catch (RejectedExecutionException error) {
                close();
                throw remoteException(
                        "Root Core executor is full or unavailable; session closed: "
                                + operation,
                        error
                );
            }
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);

            // Binder transactions may ignore thread interruption. Closing the
            // session asks the server to exit and starts the root-process reaper.
            close();

            throw new RemoteException(
                    "Root Core Binder call timed out after "
                            + timeoutMs + " ms; session was closed: " + operation
            );
        } catch (InterruptedException error) {
            future.cancel(true);

            // Binder может игнорировать interruption. Закрываем сессию,
            // чтобы сервер и root-процесс не остались бесхозными.
            close();

            Thread.currentThread().interrupt();
            throw remoteException(
                    "Interrupted while waiting for Root Core Binder; "
                            + "session was closed: " + operation,
                    error
            );
        } catch (CancellationException error) {
            throw remoteException(
                    "Root Core Binder call was cancelled: " + operation,
                    error
            );
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            android.util.Log.e(
                    "RootCore",
                    "CLIENT_BINDER_EXECUTION_EXCEPTION operation=" + operation
                            + " session=" + sessionId.substring(0, 8)
                            + " causeType=" + (cause == null
                                    ? "null" : cause.getClass().getName())
                            + " causeMessage=" + (cause == null
                                    ? "null" : cause.getMessage()),
                    cause == null ? error : cause
            );
            if (cause instanceof RemoteException) {
                throw (RemoteException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw remoteException(
                    "Root Core Binder call failed: " + operation,
                    cause
            );
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Root Core session is closed");
        }
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
        }

        // AIDL close() is oneway: send it directly, even after a Binder timeout.
        // Do not queue it behind a possibly blocked synchronous Binder call.
        try {
            remote.close();
        } catch (Throwable ignored) {
        }
        for (Runnable pending : binderExecutor.shutdownNow()) {
            if (pending instanceof Future<?>) {
                ((Future<?>) pending).cancel(false);
            }
        }

        try {
            if (deathRecipient != null) {
                remote.asBinder().unlinkToDeath(deathRecipient, 0);
            }
        } catch (Throwable ignored) {
        }

        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        receiverThread.quitSafely();

        Thread processReaper = new Thread(() -> {
            try {
                if (!rootProcess.waitFor(12, TimeUnit.SECONDS)) {
                    android.util.Log.w(
                            "RootCore",
                            "Root process did not exit after session close; forcing termination"
                    );
                    rootProcess.destroyForcibly();
                    if (!rootProcess.waitFor(2, TimeUnit.SECONDS)) {
                        android.util.Log.e(
                                "RootCore",
                                "Root process is still alive after forced termination"
                        );
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, "RootCoreProcessReaper-" + sessionId.substring(0, 8));
        processReaper.setDaemon(true);
        processReaper.start();
    }

    public boolean awaitTermination(long timeoutMs) throws InterruptedException {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        return rootProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean isRootProcessAlive() {
        return rootProcess.isAlive();
    }

    /**
     * Returns whether this session is open and its remote Binder and root process are alive.
     * This is a local state check; a subsequent Binder call can still fail if the process dies.
     */
    public boolean isConnected() {
        return !closed
                && binderAlive.get()
                && remote.asBinder().isBinderAlive()
                && rootProcess.isAlive();
    }

    boolean isOpen() {
        return !closed;
    }

    public String getSessionId() {
        return sessionId;
    }

    private static void terminateProcess(java.lang.Process process) {
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static File createSessionConfig(
            Context context,
            String sessionId,
            String encodedPolicy
    ) throws IOException {
        File directory = new File(context.getFilesDir(), "rootcore");
        if (!directory.isDirectory() && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IOException("Cannot create Root Core directory");
        }

        File config = File.createTempFile("session-", ".cfg", directory);
        try (FileOutputStream output = new FileOutputStream(config)) {
            byte[] data = (sessionId + '\n' + encodedPolicy + '\n')
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            output.write(data);
            output.getFD().sync();
        } catch (Throwable error) {
            config.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Cannot write Root Core bootstrap config", error);
        }

        // The parent directory is app-private; root can read the file.
        config.setReadable(false, false);
        config.setWritable(false, false);
        config.setReadable(true, true);
        config.setWritable(true, true);
        return config;
    }

    private static synchronized File installBootstrapAsset(Context context)
            throws IOException {
        File directory = new File(context.getFilesDir(), "rootcore");
        if (!directory.isDirectory() && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IOException("Cannot create Root Core directory");
        }

        File target = new File(directory, "main.jar");

        byte[] assetHash;
        try (InputStream input = context.getAssets().open("main.jar")) {
            assetHash = sha256(input);
        }

        if (target.isFile()) {
            try (InputStream input = new java.io.FileInputStream(target)) {
                if (MessageDigest.isEqual(assetHash, sha256(input))) {
                    return target;
                }
            }
        }

        if (target.exists() && !target.delete()) {
            throw new IOException("Cannot replace outdated Root Core bootstrap asset");
        }

        File temporary = File.createTempFile("main-", ".jar.tmp", directory);

        try {
            try (InputStream input = context.getAssets().open("main.jar");
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }

            if (!temporary.renameTo(target)) {
                throw new IOException("Cannot install Root Core bootstrap asset");
            }

            return target;
        } finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private static byte[] sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }

            return digest.digest();
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError("SHA-256 is unavailable", error);
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
