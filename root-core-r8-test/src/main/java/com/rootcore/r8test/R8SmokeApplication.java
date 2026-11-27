package com.rootcore.r8test;

import android.app.Application;
import android.content.Context;

import com.rootcore.RootCore;
import com.rootcore.RootCorePolicy;
import com.rootcore.RootCoreSession;

public final class R8SmokeApplication extends Application {

    public RootCoreSession openSession(Context context) throws Exception {
        return RootCore.open(
                context,
                RootCorePolicy.denyAll(),
                30_000L
        );
    }
}
