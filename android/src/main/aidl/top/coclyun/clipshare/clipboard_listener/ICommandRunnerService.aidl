// ILatestWriteClipboardPkgService.aidl
package top.coclyun.clipshare.clipboard_listener;

interface ICommandRunnerService {
    void destroy() = 16777114; // Destroy method defined by Shizuku server
    void exit() = 1;
    String run(String command, boolean useRoot) = 2;
}