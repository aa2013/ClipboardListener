package top.coclyun.clipshare.clipboard_listener;
import top.coclyun.clipshare.clipboard_listener.IOnClipboardChanged;

interface IClipboardListenerService {
    void destroy() = 16777114; // Destroy method defined by Shizuku server
    void exit() = 1; // Exit method defined by user
    void startListening(IOnClipboardChanged callback, boolean useRoot, String filePath, boolean useHiddenApi) = 2;
    void stopListening() = 3;
}