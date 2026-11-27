package com.rootcore.ipc;

import android.os.Bundle;
import com.rootcore.ipc.IRootCoreClient;

interface IRootCoreManager {
    int ping();
    void connect(IRootCoreClient client);
    Bundle execute(in Bundle request);
    oneway void close();
}
