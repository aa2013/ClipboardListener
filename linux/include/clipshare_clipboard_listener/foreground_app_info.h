#include <gdk/gdkx.h>
#include <X11/Xlib.h>
#include <X11/Xatom.h>

gchar* get_app_name_by_wmclass(Display *display, Window window);

gboolean get_foreground_app(gchar **name, gchar **package, gchar **iconB64);