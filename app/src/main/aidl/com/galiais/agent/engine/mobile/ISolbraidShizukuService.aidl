package com.galiais.agent.engine.mobile;

interface ISolbraidShizukuService {
    void destroy() = 16777114;
    String exec(String command, int timeoutSec) = 1;
    int getUid() = 2;
}
