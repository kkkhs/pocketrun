package io.github.kkkhs.pocketrun;

import android.os.SharedMemory;

interface IPrivilegedBackend {
    int getUid();
    int getPid();
    String ping();
    int createDemoDisplay();
    int getDisplayId();
    SharedMemory captureScreenshot(int expectedDisplayId);
    byte[] dumpUiTree(int expectedDisplayId);
    boolean injectTap(int expectedDisplayId, int x, int y);
    boolean injectText(int expectedDisplayId, String text);
    void releaseDisplay();
    void shutdown();
}
