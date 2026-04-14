#include "wayland_clipboard_listener.h"

#include "ext-data-control-v1-client-protocol.h"
#include "include/clipshare_clipboard_listener/utils.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <string.h>
#include <unistd.h>
#include <wayland-client.h>

struct _WaylandClipboardListener {
  GObject *owner;
  WaylandClipboardChangedCallback callback;
  GMainContext *main_context;
  wl_display *display;
  wl_registry *registry;
  wl_seat *seat;
  ext_data_control_manager_v1 *manager;
  ext_data_control_device_v1 *device;
  GThread *thread;
  gint should_stop;
  gboolean skip_next_selection;
  gint64 ignore_events_until_us;
};

namespace {

constexpr gint kWaylandPollTimeoutMs = 250;
constexpr gint kClipboardReceiveTimeoutMs = 3000;
constexpr gint kInitialSelectionSilenceMs = 3000;
constexpr gsize kMaxClipboardBytes = 50 * 1024 * 1024;

struct ClipboardOffer;

struct ClipboardOffer {
  WaylandClipboardListener *listener;
  ext_data_control_offer_v1 *offer;
  GPtrArray *mime_types;
};

struct ClipboardChangedEvent {
  GObject *owner;
  WaylandClipboardChangedCallback callback;
  gchar *type;
  gchar *content;
};

gboolean deliver_clipboard_changed(gpointer user_data) {
  auto *event = static_cast<ClipboardChangedEvent *>(user_data);
  if (event->owner && event->callback) {
    event->callback(event->owner, event->type, event->content);
  }
  if (event->owner) {
    g_object_unref(event->owner);
  }
  g_free(event->type);
  g_free(event->content);
  g_free(event);
  return G_SOURCE_REMOVE;
}

void notify_clipboard_changed(WaylandClipboardListener *listener,
                              const gchar *type,
                              const gchar *content) {
  if (!listener || !listener->callback || !listener->owner) {
    return;
  }

  auto *event = g_new0(ClipboardChangedEvent, 1);
  event->owner = G_OBJECT(g_object_ref(listener->owner));
  event->callback = listener->callback;
  event->type = g_strdup(type);
  event->content = g_strdup(content);
  g_main_context_invoke(listener->main_context, deliver_clipboard_changed,
                        event);
}

void clipboard_offer_free(ClipboardOffer *offer) {
  if (!offer) {
    return;
  }
  if (offer->offer) {
    ext_data_control_offer_v1_destroy(offer->offer);
  }
  if (offer->mime_types) {
    g_ptr_array_unref(offer->mime_types);
  }
  g_free(offer);
}

const gchar *find_mime_type(ClipboardOffer *offer,
                            const gchar *const *candidates) {
  if (!offer || !offer->mime_types) {
    return nullptr;
  }
  for (const gchar *const *candidate = candidates; *candidate; ++candidate) {
    for (guint i = 0; i < offer->mime_types->len; ++i) {
      const gchar *mime =
          static_cast<const gchar *>(g_ptr_array_index(offer->mime_types, i));
      if (g_strcmp0(mime, *candidate) == 0) {
        return mime;
      }
    }
  }
  return nullptr;
}

gboolean flush_wayland_display(wl_display *display) {
  while (true) {
    if (wl_display_flush(display) >= 0) {
      return TRUE;
    }
    if (errno != EAGAIN) {
      debug_printf("Wayland display flush failed: %s", g_strerror(errno));
      return FALSE;
    }

    pollfd pfd = {wl_display_get_fd(display), POLLOUT, 0};
    int ret = poll(&pfd, 1, kClipboardReceiveTimeoutMs);
    if (ret <= 0) {
      debug_printf("Wayland display flush timed out or failed");
      return FALSE;
    }
  }
}

GBytes *receive_offer_bytes(WaylandClipboardListener *listener,
                            ClipboardOffer *offer,
                            const gchar *mime_type) {
  int pipe_fds[2];
  if (pipe(pipe_fds) != 0) {
    debug_printf("Can not create pipe for Wayland clipboard: %s",
                 g_strerror(errno));
    return nullptr;
  }

  int flags = fcntl(pipe_fds[0], F_GETFL, 0);
  if (flags >= 0) {
    fcntl(pipe_fds[0], F_SETFL, flags | O_NONBLOCK);
  }

  ext_data_control_offer_v1_receive(offer->offer, mime_type, pipe_fds[1]);
  close(pipe_fds[1]);

  if (!flush_wayland_display(listener->display)) {
    close(pipe_fds[0]);
    return nullptr;
  }

  GByteArray *data = g_byte_array_new();
  gint64 deadline =
      g_get_monotonic_time() + kClipboardReceiveTimeoutMs * G_TIME_SPAN_MILLISECOND;
  gboolean completed = FALSE;
  gboolean failed = FALSE;

  while (!completed && !failed) {
    gint64 remaining_us = deadline - g_get_monotonic_time();
    if (remaining_us <= 0) {
      debug_printf("Wayland clipboard receive timed out");
      failed = TRUE;
      break;
    }

    int timeout_ms = static_cast<int>((remaining_us + 999) / 1000);
    pollfd pfd = {pipe_fds[0], POLLIN | POLLHUP | POLLERR, 0};
    int ret = poll(&pfd, 1, timeout_ms);
    if (ret < 0) {
      if (errno == EINTR) {
        continue;
      }
      debug_printf("Wayland clipboard pipe poll failed: %s", g_strerror(errno));
      failed = TRUE;
      break;
    }
    if (ret == 0) {
      debug_printf("Wayland clipboard pipe poll timed out");
      failed = TRUE;
      break;
    }

    if (pfd.revents & (POLLERR | POLLNVAL)) {
      debug_printf("Wayland clipboard pipe returned an error");
      failed = TRUE;
      break;
    }

    while (true) {
      guint8 buffer[8192];
      ssize_t bytes_read = read(pipe_fds[0], buffer, sizeof(buffer));
      if (bytes_read > 0) {
        if (data->len + static_cast<gsize>(bytes_read) > kMaxClipboardBytes) {
          debug_printf("Wayland clipboard data is too large");
          failed = TRUE;
          break;
        }
        g_byte_array_append(data, buffer, static_cast<guint>(bytes_read));
        continue;
      }
      if (bytes_read == 0) {
        completed = TRUE;
        break;
      }
      if (errno == EAGAIN || errno == EWOULDBLOCK) {
        break;
      }
      if (errno == EINTR) {
        continue;
      }
      debug_printf("Wayland clipboard pipe read failed: %s", g_strerror(errno));
      failed = TRUE;
      break;
    }

    if (pfd.revents & POLLHUP) {
      completed = TRUE;
    }
  }

  close(pipe_fds[0]);

  if (failed) {
    g_byte_array_unref(data);
    return nullptr;
  }

  return g_byte_array_free_to_bytes(data);
}

const gchar *extension_for_image_mime(const gchar *mime_type) {
  if (g_strcmp0(mime_type, "image/jpeg") == 0 ||
      g_strcmp0(mime_type, "image/jpg") == 0) {
    return "jpg";
  }
  if (g_strcmp0(mime_type, "image/bmp") == 0) {
    return "bmp";
  }
  if (g_strcmp0(mime_type, "image/tiff") == 0) {
    return "tiff";
  }
  return "png";
}

gchar *save_image_bytes_to_temp_file(GBytes *bytes, const gchar *mime_type) {
  gsize length = 0;
  const gchar *data =
      static_cast<const gchar *>(g_bytes_get_data(bytes, &length));
  if (!data || length == 0) {
    return nullptr;
  }

  g_autofree gchar *current_time = getCurrentTimeWithMilliseconds();
  gchar *filename = g_strdup_printf("/tmp/%s.%s", current_time,
                                    extension_for_image_mime(mime_type));
  GError *error = nullptr;
  if (!g_file_set_contents(filename, data, static_cast<gssize>(length),
                           &error)) {
    debug_printf("Failed to save Wayland clipboard image: %s",
                 error ? error->message : "unknown error");
    if (error) {
      g_error_free(error);
    }
    g_free(filename);
    return nullptr;
  }
  return filename;
}

void handle_selection_offer(ClipboardOffer *offer) {
  if (!offer || !offer->listener) {
    clipboard_offer_free(offer);
    return;
  }

  static const gchar *const text_mimes[] = {
      "text/plain;charset=utf-8",
      "text/plain;charset=UTF-8",
      "text/plain",
      nullptr,
  };
  static const gchar *const image_mimes[] = {
      "image/png",
      "image/jpeg",
      "image/jpg",
      "image/bmp",
      "image/tiff",
      nullptr,
  };

  const gchar *text_mime = find_mime_type(offer, text_mimes);
  const gchar *image_mime = find_mime_type(offer, image_mimes);
  const gchar *mime_type = text_mime ? text_mime : image_mime;
  if (!mime_type) {
    clipboard_offer_free(offer);
    return;
  }

  g_autoptr(GBytes) bytes =
      receive_offer_bytes(offer->listener, offer, mime_type);
  if (!bytes) {
    clipboard_offer_free(offer);
    return;
  }

  if (text_mime) {
    gsize length = 0;
    const gchar *data =
        static_cast<const gchar *>(g_bytes_get_data(bytes, &length));
    g_autofree gchar *text = g_strndup(data ? data : "", length);
    notify_clipboard_changed(offer->listener, "Text", text);
  } else {
    g_autofree gchar *filename =
        save_image_bytes_to_temp_file(bytes, image_mime);
    if (filename) {
      notify_clipboard_changed(offer->listener, "Image", filename);
    }
  }

  clipboard_offer_free(offer);
}

void offer_offer(void *data,
                 ext_data_control_offer_v1 * /* ext_data_control_offer_v1 */,
                 const char *mime_type) {
  auto *offer = static_cast<ClipboardOffer *>(data);
  if (offer && offer->mime_types && mime_type) {
    g_ptr_array_add(offer->mime_types, g_strdup(mime_type));
  }
}

const ext_data_control_offer_v1_listener kOfferListener = {
    offer_offer,
};

void device_data_offer(void *data,
                       ext_data_control_device_v1 * /* device */,
                       ext_data_control_offer_v1 *id) {
  auto *listener = static_cast<WaylandClipboardListener *>(data);
  auto *offer = g_new0(ClipboardOffer, 1);
  offer->listener = listener;
  offer->offer = id;
  offer->mime_types = g_ptr_array_new_with_free_func(g_free);
  ext_data_control_offer_v1_set_user_data(id, offer);
  ext_data_control_offer_v1_add_listener(id, &kOfferListener, offer);
}

void device_selection(void *data,
                      ext_data_control_device_v1 * /* device */,
                      ext_data_control_offer_v1 *id) {
  if (!id) {
    return;
  }
  auto *listener = static_cast<WaylandClipboardListener *>(data);
  auto *offer = static_cast<ClipboardOffer *>(
      ext_data_control_offer_v1_get_user_data(id));
  if (listener) {
    if (listener->skip_next_selection) {
      listener->skip_next_selection = FALSE;
      clipboard_offer_free(offer);
      return;
    }
    if (listener->ignore_events_until_us > g_get_monotonic_time()) {
      clipboard_offer_free(offer);
      return;
    }
  }
  handle_selection_offer(offer);
}

void device_finished(void *data, ext_data_control_device_v1 * /* device */) {
  auto *listener = static_cast<WaylandClipboardListener *>(data);
  g_atomic_int_set(&listener->should_stop, TRUE);
}

void device_primary_selection(void * /* data */,
                              ext_data_control_device_v1 * /* device */,
                              ext_data_control_offer_v1 *id) {
  if (!id) {
    return;
  }
  auto *offer = static_cast<ClipboardOffer *>(
      ext_data_control_offer_v1_get_user_data(id));
  clipboard_offer_free(offer);
}

const ext_data_control_device_v1_listener kDeviceListener = {
    device_data_offer,
    device_selection,
    device_finished,
    device_primary_selection,
};

void registry_global(void *data,
                     wl_registry *registry,
                     uint32_t name,
                     const char *interface,
                     uint32_t version) {
  auto *listener = static_cast<WaylandClipboardListener *>(data);
  if (g_strcmp0(interface, wl_seat_interface.name) == 0 && !listener->seat) {
    listener->seat = static_cast<wl_seat *>(
        wl_registry_bind(registry, name, &wl_seat_interface, 1));
  } else if (g_strcmp0(interface, ext_data_control_manager_v1_interface.name) ==
                 0 &&
             !listener->manager) {
    listener->manager = static_cast<ext_data_control_manager_v1 *>(
        wl_registry_bind(registry, name, &ext_data_control_manager_v1_interface,
                         version < 1 ? version : 1));
  }
}

void registry_global_remove(void * /* data */,
                            wl_registry * /* registry */,
                            uint32_t /* name */) {}

const wl_registry_listener kRegistryListener = {
    registry_global,
    registry_global_remove,
};

gpointer wayland_dispatch_thread(gpointer user_data) {
  auto *listener = static_cast<WaylandClipboardListener *>(user_data);
  while (!g_atomic_int_get(&listener->should_stop)) {
    while (wl_display_prepare_read(listener->display) != 0) {
      if (wl_display_dispatch_pending(listener->display) < 0) {
        debug_printf("Wayland dispatch pending failed");
        return nullptr;
      }
    }

    if (wl_display_flush(listener->display) < 0 && errno != EAGAIN) {
      debug_printf("Wayland display flush failed in dispatch thread: %s",
                   g_strerror(errno));
      wl_display_cancel_read(listener->display);
      break;
    }

    pollfd pfd = {wl_display_get_fd(listener->display), POLLIN, 0};
    int ret = poll(&pfd, 1, kWaylandPollTimeoutMs);
    if (g_atomic_int_get(&listener->should_stop)) {
      wl_display_cancel_read(listener->display);
      break;
    }
    if (ret < 0) {
      wl_display_cancel_read(listener->display);
      if (errno == EINTR) {
        continue;
      }
      debug_printf("Wayland display poll failed: %s", g_strerror(errno));
      break;
    }
    if (ret == 0) {
      wl_display_cancel_read(listener->display);
      continue;
    }
    if (pfd.revents & POLLIN) {
      if (wl_display_read_events(listener->display) < 0) {
        debug_printf("Wayland display read events failed");
        break;
      }
      if (wl_display_dispatch_pending(listener->display) < 0) {
        debug_printf("Wayland dispatch pending failed");
        break;
      }
    } else {
      wl_display_cancel_read(listener->display);
      if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
        debug_printf("Wayland display poll returned an error");
        break;
      }
    }
  }
  g_atomic_int_set(&listener->should_stop, TRUE);
  return nullptr;
}

}  // namespace

WaylandClipboardListener *wayland_clipboard_listener_new(
    GObject *owner,
    WaylandClipboardChangedCallback callback) {
  if (!is_wayland()) {
    return nullptr;
  }

  auto *listener = g_new0(WaylandClipboardListener, 1);
  listener->owner = owner;
  listener->callback = callback;
  listener->main_context = g_main_context_ref(g_main_context_default());
  listener->display = wl_display_connect(nullptr);
  if (!listener->display) {
    debug_printf("Can not connect to Wayland display");
    wayland_clipboard_listener_free(listener);
    return nullptr;
  }

  listener->registry = wl_display_get_registry(listener->display);
  wl_registry_add_listener(listener->registry, &kRegistryListener, listener);
  if (wl_display_roundtrip(listener->display) < 0) {
    debug_printf("Wayland registry roundtrip failed");
    wayland_clipboard_listener_free(listener);
    return nullptr;
  }

  if (!listener->manager || !listener->seat) {
    debug_printf("Wayland ext-data-control is not available");
    wayland_clipboard_listener_free(listener);
    return nullptr;
  }

  listener->device =
      ext_data_control_manager_v1_get_data_device(listener->manager,
                                                  listener->seat);
  listener->skip_next_selection = TRUE;
  listener->ignore_events_until_us =
      g_get_monotonic_time() + kInitialSelectionSilenceMs * G_TIME_SPAN_MILLISECOND;
  ext_data_control_device_v1_add_listener(listener->device, &kDeviceListener,
                                          listener);
  if (wl_display_roundtrip(listener->display) < 0) {
    debug_printf("Wayland data-control roundtrip failed");
    wayland_clipboard_listener_free(listener);
    return nullptr;
  }

  listener->thread =
      g_thread_new("clipshare-wayland-clipboard", wayland_dispatch_thread,
                   listener);
  debug_printf("Wayland ext-data-control clipboard listener started");
  return listener;
}

void wayland_clipboard_listener_free(WaylandClipboardListener *listener) {
  if (!listener) {
    return;
  }

  g_atomic_int_set(&listener->should_stop, TRUE);
  if (listener->thread) {
    g_thread_join(listener->thread);
  }
  if (listener->device) {
    ext_data_control_device_v1_destroy(listener->device);
  }
  if (listener->manager) {
    ext_data_control_manager_v1_destroy(listener->manager);
  }
  if (listener->seat) {
    wl_seat_destroy(listener->seat);
  }
  if (listener->registry) {
    wl_registry_destroy(listener->registry);
  }
  if (listener->display) {
    wl_display_disconnect(listener->display);
  }
  if (listener->main_context) {
    g_main_context_unref(listener->main_context);
  }
  g_free(listener);
}

gboolean wayland_clipboard_listener_is_available(
    WaylandClipboardListener *listener) {
  return listener && listener->display && listener->manager &&
         listener->seat && listener->device;
}
