package io.github.kkkhs.pocketrun;

interface IPrivilegedBackend {
    int getUid();
    int getPid();
    String ping();
    int createDemoDisplay();
    int getDisplayId();
    void releaseDisplay();
    void shutdown();
}
