package top.coclyun.clipshare.clipboard_listener;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.IClipboard;
import android.os.Looper;
import android.system.Os;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.util.Arrays;

public class ClipboardListener {
    public static void main(String[] args) throws InvocationTargetException, IllegalAccessException {
        boolean registered = false;
        OnPrimaryClipChangedListenerAdapter adapter = null;
        try {
            Looper.prepare();
            System.out.println(new Event(EventEnum.comment, "uid = " + Os.getuid()));
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            System.out.println(new Event(EventEnum.comment, "activityThread = " + activityThread));
            var systemMain = activityThread.getMethod("systemMain").invoke(null);
            System.out.println(new Event(EventEnum.comment, "systemMain = " + systemMain));
            var method = activityThread.getMethod("getSystemContext");
            Context context = (Context) method.invoke(systemMain);
            System.out.println(new Event(EventEnum.comment, "getSystemContext = " + context));
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            System.out.println(new Event(EventEnum.comment, "ClipboardManager = " + cm));
            Field mServiceField = ClipboardManager.class.getDeclaredField("mService");
            System.out.println(new Event(EventEnum.comment, "mServiceField = " + mServiceField));
            mServiceField.setAccessible(true);
            var clipboard = (IClipboard) mServiceField.get(cm);
            System.out.println(new Event(EventEnum.comment, "IClipboard = " + clipboard));
            adapter = new OnPrimaryClipChangedListenerAdapter(clipboard) {
                @Override
                public void onPrimaryClipChanged() {
                    System.out.println(new Event(EventEnum.onChanged, LocalDateTime.now().toString()));
                }
            };
            registered = adapter.register();
            if (registered) {
                System.out.println(new Event(EventEnum.comment, "Clipboard listening started..."));
            } else {
                System.err.println(new Event(EventEnum.comment, "Clipboard listening startup failed!!"));
                System.out.println(new Event(EventEnum.eof, "finished"));
                System.exit(1);
            }
            Looper.loop();
        } catch (Exception e) {
            System.out.println(new Event(EventEnum.fatal, "exception: " + e.getMessage() + ". "));
            e.printStackTrace();
        } finally {
            System.out.println(new Event(EventEnum.eof, "finished"));
            if (adapter != null && registered) {
                adapter.remove();
            }
        }
    }
}
