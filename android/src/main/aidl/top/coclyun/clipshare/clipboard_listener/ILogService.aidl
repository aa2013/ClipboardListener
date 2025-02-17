// ILogService.aidl
package top.coclyun.clipshare.clipboard_listener;
import top.coclyun.clipshare.clipboard_listener.ILogCallback;

interface ILogService {
    void startReadLogs(ILogCallback callback, boolean useRoot);
    void stopReadLogs();
}