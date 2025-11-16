#include <stdio.h>
#include <gdk/gdkx.h>
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include "include/clipshare_clipboard_listener/utils.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "include/clipshare_clipboard_listener/stb_image_write.h"

//获取app名称
gchar* get_app_name_by_wmclass(Display* display, Window window) {
    XClassHint class_hint;
    if (XGetClassHint(display, window, &class_hint)) {
        // res_class 通常是大写应用名，如 "AndroidStudio" 或 "Code"
        gchar* app_name = g_strdup(class_hint.res_class);
        if (class_hint.res_name) XFree(class_hint.res_name);
        if (class_hint.res_class) XFree(class_hint.res_class);
        return app_name;
    }
    return NULL;
}

// 获取应用信息
gboolean get_foreground_app(gchar** name, gchar** package, gchar** iconB64) {

    Display* display = XOpenDisplay(nullptr);
    if (!display) {
        debug_printf("can not open X11 Display\n");
        return FALSE;
    }

    // -------------------------
    // 1. 获取 _NET_ACTIVE_WINDOW
    // -------------------------
    Atom active_atom = XInternAtom(display, "_NET_ACTIVE_WINDOW", True);

    Atom type;
    int format;
    unsigned long nitems;
    unsigned long bytes_after;
    unsigned char* prop = nullptr;

    if (XGetWindowProperty(display,
                           DefaultRootWindow(display),
                           active_atom,
                           0, (~0L),
                           False,
                           AnyPropertyType,
                           &type, &format, &nitems,
                           &bytes_after, &prop) != Success || !prop) {

        debug_printf("get _NET_ACTIVE_WINDOW failed\n");
        XCloseDisplay(display);
        return FALSE;
    }

    Window focused = *(Window*)prop;
    XFree(prop);

    // -------------------------
    // 2. 获取窗口 PID
    // -------------------------

    Atom pid_atom = XInternAtom(display, "_NET_WM_PID", False);
    unsigned char* pid_prop = nullptr;

    if (XGetWindowProperty(display, focused, pid_atom, 0, 1, False,
                           XA_CARDINAL, &type, &format, &nitems,
                           &bytes_after, &pid_prop) != Success) {
        debug_printf("get _NET_WM_PID failed\n");
    }

    pid_t window_pid = -1;

    if (pid_prop && nitems > 0) {
        window_pid = *((pid_t*)pid_prop);
        XFree(pid_prop);
    } else {
        debug_printf("invalid PID prop\n");
        XCloseDisplay(display);
        return FALSE;
    }

    // -------------------------
    // 3. 获取进程执行路径
    // -------------------------
    gchar* exe_path = g_strdup_printf("/proc/%d/exe", window_pid);
    gchar real_path_buf[512];

    ssize_t len = readlink(exe_path, real_path_buf, sizeof(real_path_buf) - 1);
    g_free(exe_path);

    if (len <= 0) {
        debug_printf("readlink(%s) failed\n", exe_path);
        XCloseDisplay(display);
        return FALSE;
    }

    real_path_buf[len] = 0;
    gchar* real_path = g_strdup(real_path_buf);
    gchar* app_name = get_app_name_by_wmclass(display, focused);
    if (app_name) {
        *package = g_strdup(app_name);
        *name = g_strdup(app_name);
        g_free(app_name);
    } else {
        // 获取 basename
        gchar* base_name = g_path_get_basename(real_path);
        *package = g_strdup(base_name);
        *name = g_strdup(base_name);
        g_free(base_name);
    }


    // -------------------------
    // 4. 获取窗口图标 (_NET_WM_ICON)
    // -------------------------

    Atom icon_atom = XInternAtom(display, "_NET_WM_ICON", True);
    unsigned char* icon_data = nullptr;

    if (XGetWindowProperty(display, focused, icon_atom,
                           0, 1024 * 1024,
                           False, AnyPropertyType,
                           &type, &format, &nitems,
                           &bytes_after, &icon_data) == Success &&
        icon_data && nitems > 0) {
        unsigned long* data = (unsigned long*)icon_data;
        // 第一个两个 unsigned long 是图标的宽度和高度
        unsigned long w = data[0];
        unsigned long h = data[1];


        // 像素数据从 data[2] 开始
        unsigned long* pixels = data + 2;

        // ARGB → RGBA 转换
        unsigned char* rgba = (unsigned char*)malloc(w * h * 4);
        for (unsigned long i = 0; i < w * h; i++) {
            unsigned long argb = pixels[i];

            unsigned char A = (argb >> 24) & 0xFF;
            unsigned char R = (argb >> 16) & 0xFF;
            unsigned char G = (argb >> 8)  & 0xFF;
            unsigned char B =  argb        & 0xFF;

            rgba[i * 4 + 0] = R;
            rgba[i * 4 + 1] = G;
            rgba[i * 4 + 2] = B;
            rgba[i * 4 + 3] = A;
        }

        // 生成 PNG 文件
        gchar* png_path = g_strdup_printf("/tmp/%s_icon.png", *name);

        if (stbi_write_png(png_path, w, h, 4, rgba, w * 4)) {
            *iconB64 = png_file_to_base64(png_path);
            if (remove(png_path) != 0) {
                debug_printf("delete file failed: %s\n", png_path);
            }
        } else {
            *iconB64 = png_file_to_base64(g_strdup_printf("/usr/share/pixmaps/%s.png", *name));
        }

        free(rgba);
        XFree(icon_data);

    } else {
        *iconB64 = png_file_to_base64(g_strdup_printf("/usr/share/pixmaps/%s.png", *name));
    }

    // -------------------------
    // 5. 清理
    // -------------------------
    g_free(real_path);
    XCloseDisplay(display);

    return TRUE;
}