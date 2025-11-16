#include <gtk/gtk.h>
#include <X11/Xlib.h>

void debug_printf(const char *format, ...);

gchar* png_file_to_base64(const gchar *file_path);

gchar *getCurrentTimeWithMilliseconds();

void send_ctrl_v(Display* display);

Window getWindowId();

gboolean activeWindow(Window window, Display** outDisplay);