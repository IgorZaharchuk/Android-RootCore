package com.rootcore.ipc;

import android.os.Bundle;
import android.os.IBinder;

interface IRootCoreManager {
    int ping();
    void connect(IBinder client);
    Bundle execute(in Bundle request);
    oneway void close();
}
