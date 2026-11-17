package io.github.kkkhs.pocketrun;

interface IPrivilegedBackend {
    int getUid();
    int getPid();
    String ping();
    void shutdown();
}
