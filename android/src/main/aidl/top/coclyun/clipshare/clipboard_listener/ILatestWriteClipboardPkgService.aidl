// ILatestWriteClipboardPkgService.aidl
package top.coclyun.clipshare.clipboard_listener;

interface ILatestWriteClipboardPkgService {
    void destroy() = 16777114; // Destroy method defined by Shizuku server
    void exit() = 1;
    void start(String scriptPath, boolean useRoot) = 2;
    String getLatestWriteClipbaordAppSource() = 3;
}