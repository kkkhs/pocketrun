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
    boolean launchAppOnDisplay(int expectedDisplayId, String packageName);
    boolean injectAppTap(int expectedDisplayId, int x, int y);
    boolean injectAppSwipe(int expectedDisplayId, int x1, int y1, int x2, int y2, int durationMs);
    boolean injectAppKey(int expectedDisplayId, int keyCode);
    boolean injectAppText(int expectedDisplayId, String text);
}
