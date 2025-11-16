#include <gtk/gtk.h>
#include <regex>
#include <chrono>
#include <glib.h>
#include <gdk/gdkx.h>
#include <X11/Xlib.h>
#include <X11/extensions/XTest.h>

void debug_printf(const char* format, ...) {
    FILE* f = fopen("/tmp/clipshare.log", "a");
    if (f) {
        va_list args;
        va_start(args, format);
        vfprintf(f, format, args);
        fprintf(f, "\n");
        va_end(args);
        fclose(f);
    }
}

// 读取文件并返回 base64 字符串
gchar* png_file_to_base64(const gchar* file_path) {
    if (!file_path) return NULL;

    FILE* f = fopen(file_path, "rb");
    if (!f) {
        debug_printf("can not open file: %s", file_path);
        return NULL;
    }

    // 获取文件大小
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);

    if (size <= 0) {
        fclose(f);
        debug_printf("file is empty: %s", file_path);
        return NULL;
    }

    // 读取文件内容
    unsigned char* buffer = (unsigned char*)malloc(size);
    if (!buffer) {
        fclose(f);
        g_warning("malloc failed!");
        return NULL;
    }

    fread(buffer, 1, size, f);
    fclose(f);

    // Base64 编码
    gchar* base64 = g_base64_encode(buffer, size);
    free(buffer);

    return base64;  // 使用完记得 g_free()
}

gchar *getCurrentTimeWithMilliseconds()
{
    // 获取当前系统时间（精确到毫秒）
    auto now = std::chrono::system_clock::now();
    auto now_ms = std::chrono::time_point_cast<std::chrono::milliseconds>(now);
    auto epoch = now_ms.time_since_epoch();
    auto value = std::chrono::duration_cast<std::chrono::milliseconds>(epoch);

    // 转换成当前时间的字符串表示形式
    auto time = std::chrono::system_clock::to_time_t(now);
    struct tm tm;
    localtime_r(&time, &tm);

    // 生成当前时间字符串
    char buffer[30];
    strftime(buffer, sizeof(buffer), "%Y-%m-%d %H:%M:%S", &tm);

    // 获取当前毫秒部分
    int milliseconds = value.count() % 1000;
    std::string currentTime(buffer);
    currentTime += "." + std::to_string(milliseconds);

    // 使用正则表达式替换字符
    std::regex reg("[:.]");
    currentTime = std::regex_replace(currentTime, reg, "-");
    reg = std::regex(" +");
    currentTime = std::regex_replace(currentTime, reg, "_");

    // 将 std::string 转换为 gchar* 并返回
    return g_strdup(currentTime.c_str());
}

void send_ctrl_v(Display* display) {
    // 获取 keycode
    KeyCode ctrl = XKeysymToKeycode(display, XK_Control_L);
    KeyCode v = XKeysymToKeycode(display, XK_V);
    // 按下 Ctrl
    XTestFakeKeyEvent(display, ctrl, True, CurrentTime);
    // 按下 V
    XTestFakeKeyEvent(display, v, True, CurrentTime);
    // 松开 V
    XTestFakeKeyEvent(display, v, False, CurrentTime);
    // 松开 Ctrl
    XTestFakeKeyEvent(display, ctrl, False, CurrentTime);
    XFlush(display);
}
Window getWindowId(){
    Display* display = XOpenDisplay(nullptr);
    Atom active_atom = XInternAtom(display, "_NET_ACTIVE_WINDOW", True);
    Atom type;
    int format;
    unsigned long nitems, bytes_after;
    unsigned char* prop = nullptr;

    if (XGetWindowProperty(display, DefaultRootWindow(display), active_atom,
                           0, (~0L), False, AnyPropertyType,
                           &type, &format, &nitems, &bytes_after, &prop) != Success || !prop) {
        XCloseDisplay(display);
        return FALSE;
    }

    Window window = *(Window*)prop;
    XFree(prop);
    XCloseDisplay(display);
    return window;
}

gboolean activeWindow(Window window, Display** outDisplay){

    if (window == 0) return false;

    Display* display = XOpenDisplay(nullptr);
    *outDisplay = display;
    // 激活窗口
    Atom atom = XInternAtom(display, "_NET_ACTIVE_WINDOW", False);
    XEvent xev;
    memset(&xev, 0, sizeof(xev));
    xev.xclient.type = ClientMessage;
    xev.xclient.window = window;
    xev.xclient.message_type = atom;
    xev.xclient.format = 32;
    xev.xclient.data.l[0] = 1;  // 1 = normal application request
    xev.xclient.data.l[1] = CurrentTime;
    xev.xclient.data.l[2] = 0;
    xev.xclient.data.l[3] = 0;
    xev.xclient.data.l[4] = 0;

    XSendEvent(display, DefaultRootWindow(display), False,
               SubstructureRedirectMask | SubstructureNotifyMask,
               &xev);
    XFlush(display);
    return true;
}