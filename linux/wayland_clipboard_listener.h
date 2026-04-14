#ifndef CLIPSHARE_WAYLAND_CLIPBOARD_LISTENER_H_
#define CLIPSHARE_WAYLAND_CLIPBOARD_LISTENER_H_

#include <glib-object.h>
#include <glib.h>

G_BEGIN_DECLS

typedef struct _WaylandClipboardListener WaylandClipboardListener;

typedef void (*WaylandClipboardChangedCallback)(GObject *owner,
                                                const gchar *type,
                                                const gchar *content);

WaylandClipboardListener *wayland_clipboard_listener_new(
    GObject *owner,
    WaylandClipboardChangedCallback callback);

void wayland_clipboard_listener_free(WaylandClipboardListener *listener);

gboolean wayland_clipboard_listener_is_available(
    WaylandClipboardListener *listener);

G_END_DECLS

#endif  // CLIPSHARE_WAYLAND_CLIPBOARD_LISTENER_H_
