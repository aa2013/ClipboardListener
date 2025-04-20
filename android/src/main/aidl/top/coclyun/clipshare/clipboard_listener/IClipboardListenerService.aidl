package top.coclyun.clipshare.clipboard_listener;
import top.coclyun.clipshare.clipboard_listener.IOnClipboardChanged;

interface IClipboardListenerService {
    void startListening(IOnClipboardChanged callback, boolean useRoot, String filePath);
    void stopListening();
}