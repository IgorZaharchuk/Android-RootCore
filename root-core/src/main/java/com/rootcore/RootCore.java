package com.rootcore;

import android.content.Context;
import android.os.Looper;

import java.util.concurrent.TimeoutException;

public final class RootCore {

    private RootCore() {}

    /**
     * Opens a privileged RootCore session.
     *
     * @param timeoutMs total time allowed for root-manager authorization,
     *                  bootstrap startup and Binder connection. The first
     *                  invocation may display a root-grant confirmation.
     */
    public static RootCoreSession open(
            Context context,
            RootCorePolicy policy,
            long timeoutMs
    ) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("RootCore policy is null");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("RootCore.open() must not run on the main thread");
        }
        if (timeoutMs < 1_000L) {
            throw new IllegalArgumentException("Timeout must be at least 1000 ms");
        }

        return RootCoreSession.open(
                context.getApplicationContext(),
                timeoutMs,
                policy
        );
    }
}
