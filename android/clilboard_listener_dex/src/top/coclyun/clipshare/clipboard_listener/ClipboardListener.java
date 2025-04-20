package top.coclyun.clipshare.clipboard_listener;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.IClipboard;
import android.os.Looper;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;

public class ClipboardListener {
    public static void main(String[] args) {
        try {
            Looper.prepare();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            System.out.println(new Event(EventEnum.comment, "activityThread = " + activityThread));
            var systemMain = activityThread.getMethod("systemMain").invoke(null);
            System.out.println(new Event(EventEnum.comment, "systemMain = " + systemMain));
            var method = activityThread.getMethod("getSystemContext");
            Context context = (Context) method.invoke(systemMain);
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            Field mServiceField = ClipboardManager.class.getDeclaredField("mService");
            mServiceField.setAccessible(true);
            var clipboard = (IClipboard) mServiceField.get(cm);
            var adapter = new OnPrimaryClipChangedListenerAdapter(clipboard) {
                @Override
                public void onPrimaryClipChanged() {
                    System.out.println(new Event(EventEnum.onChanged, LocalDateTime.now().toString()));
                }
            };
            boolean success = adapter.register();
            if (success) {
                System.out.println(new Event(EventEnum.comment, "Clipboard listening started..."));
            } else {
                System.err.println(new Event(EventEnum.comment, "Clipboard listening startup failed!!"));
                System.exit(1);
            }
            Looper.loop();
        } catch (Exception e) {
            System.out.println(new Event(EventEnum.fatal, "exception: " + e.getMessage() + ". "));
            e.printStackTrace();
        }finally {
            System.out.println(new Event(EventEnum.eof, "finished"));
        }
    }
}
