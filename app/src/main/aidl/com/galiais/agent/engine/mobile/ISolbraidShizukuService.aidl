package com.galiais.agent.engine.mobile;

import android.os.ParcelFileDescriptor;

interface ISolbraidShizukuService {
    void destroy() = 16777114;
    String exec(String command, int timeoutSec) = 1;
    int getUid() = 2;
    int execToFd(String command, int timeoutSec, in ParcelFileDescriptor outFd) = 3;
    void cancelCurrent() = 4;
}
