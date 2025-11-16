#include "include/clipshare_clipboard_listener/clipshare_clipboard_listener_plugin.h"
#include "clipshare_clipboard_listener_plugin_private.h"
#include "include/clipshare_clipboard_listener/utils.h"
#include "include/clipshare_clipboard_listener/foreground_app_info.h"

#include <flutter_linux/flutter_linux.h>
#include <gtk/gtk.h>
#include <gdk-pixbuf/gdk-pixbuf.h>
#include <sys/utsname.h>
#include <cstring>
#include <string>
#include <glib.h>

#define CLIPSHARE_CLIPBOARD_LISTENER_PLUGIN(obj)                                     \
  (G_TYPE_CHECK_INSTANCE_CAST((obj), clipshare_clipboard_listener_plugin_get_type(), \
                              ClipshareClipboardListenerPlugin))

struct _ClipshareClipboardListenerPlugin
{
  GObject parent_instance;
  FlMethodChannel *channel;
  bool running = false;
  bool ignoreNextCopy = false;
};

G_DEFINE_TYPE(ClipshareClipboardListenerPlugin, clipshare_clipboard_listener_plugin, g_object_get_type())

// Called when a method call is received from Flutter.
static void clipshare_clipboard_listener_plugin_handle_method_call(
    ClipshareClipboardListenerPlugin *self,
    FlMethodCall *method_call)
{
  g_autoptr(FlMethodResponse) response = nullptr;

  const gchar *method = fl_method_call_get_name(method_call);
  FlValue *args = fl_method_call_get_args(method_call);
  // printf("%s",*method);
  if (strcmp(method, kStartListening) == 0)
  {
    response = startListening(self, args);
  }
  else if (strcmp(method, kStopListening) == 0)
  {
    response = stopListening(self, args);
  }
  else if (strcmp(method, kCheckIsRunning) == 0)
  {
    response = checkIsRunning(self, args);
  }
  else if (strcmp(method, kCopy) == 0)
  {
    FlValue *type_value = fl_value_lookup_string(args, "type");
    FlValue *content_value = fl_value_lookup_string(args, "content");
    const gchar *type = fl_value_get_string(type_value);
    const gchar *content = fl_value_get_string(content_value);
    response = copyData(self, type, content);
  }
  else if(strcmp(method, kStoreCurrentWindowHwnd) == 0)
  {
      response = storeCurrentWindowHwnd(self);
  }
  else if(strcmp(method, kPasteToPreviousWindow) == 0)
  {
      FlValue *key_delay_ms_value = fl_value_lookup_string(args, "keyDelayMs");
      int64_t delay_ms = fl_value_get_int(key_delay_ms_value);
      response = pasteToPreviousWindow(self, delay_ms);
  }
  else
  {
    response = FL_METHOD_RESPONSE(fl_method_not_implemented_response_new());
  }

  fl_method_call_respond(method_call, response, nullptr);
}

static void clipshare_clipboard_listener_plugin_dispose(GObject *object)
{
  G_OBJECT_CLASS(clipshare_clipboard_listener_plugin_parent_class)->dispose(object);
}

static void clipshare_clipboard_listener_plugin_class_init(ClipshareClipboardListenerPluginClass *klass)
{
  G_OBJECT_CLASS(klass)->dispose = clipshare_clipboard_listener_plugin_dispose;
}

static void clipshare_clipboard_listener_plugin_init(ClipshareClipboardListenerPlugin *self) {}

static void method_call_cb(FlMethodChannel *channel, FlMethodCall *method_call,
                           gpointer user_data)
{
  ClipshareClipboardListenerPlugin *plugin = CLIPSHARE_CLIPBOARD_LISTENER_PLUGIN(user_data);
  clipshare_clipboard_listener_plugin_handle_method_call(plugin, method_call);
}

void clipshare_clipboard_listener_plugin_register_with_registrar(FlPluginRegistrar *registrar)
{
  ClipshareClipboardListenerPlugin *plugin = CLIPSHARE_CLIPBOARD_LISTENER_PLUGIN(
      g_object_new(clipshare_clipboard_listener_plugin_get_type(), nullptr));

  g_autoptr(FlStandardMethodCodec) codec = fl_standard_method_codec_new();
  g_autoptr(FlMethodChannel) channel =
      fl_method_channel_new(fl_plugin_registrar_get_messenger(registrar),
                            kChannelName,
                            FL_METHOD_CODEC(codec));
  plugin->channel = channel;
  fl_method_channel_set_method_call_handler(channel, method_call_cb,
                                            g_object_ref(plugin),
                                            g_object_unref);

  GtkClipboard *clipboard = gtk_clipboard_get(GDK_SELECTION_CLIPBOARD);
  g_signal_connect(clipboard, "owner-change", G_CALLBACK(onClipboardChanged),
                   plugin);
  g_object_unref(plugin);
}

static void onClipboardChanged(GtkClipboard *clipboard, GdkEvent *event, gpointer data)
{
  ClipshareClipboardListenerPlugin *plugin = CLIPSHARE_CLIPBOARD_LISTENER_PLUGIN(data);
  if (plugin->running != true)
  {
    return;
  }
  if (plugin->ignoreNextCopy)
  {
    plugin->ignoreNextCopy = false;
    return;
  }
  g_autoptr(FlValue) result_data = fl_value_new_map();
  g_autoptr(FlValue) content_key = fl_value_new_string("content");
  g_autoptr(FlValue) type_key = fl_value_new_string("type");
  g_autoptr(FlValue) content_value = fl_value_new_string("");
  g_autoptr(FlValue) type_value = fl_value_new_string("");

  const gchar *text = gtk_clipboard_wait_for_text(clipboard);
  if (text != NULL)
  {
    type_value = fl_value_new_string("Text");
    content_value = fl_value_new_string(text);
  }

  else
  {
    GdkPixbuf *pixbuf = gtk_clipboard_wait_for_image(clipboard);
    if (pixbuf != NULL)
    {
      debug_printf("Image found on clipboard\n");
      GError *error = NULL;

      gchar *currentTime = getCurrentTimeWithMilliseconds();
      // 使用 gdk_pixbuf_save 将 GdkPixbuf 保存为文件
      gchar *filename = g_strconcat("/tmp/", g_strconcat(currentTime, ".png", NULL), NULL);
      gboolean result = gdk_pixbuf_save(pixbuf, filename, "png", &error, NULL);
      if (!result)
      {
        FL_METHOD_RESPONSE(fl_method_error_response_new(error->message, error->message, NULL));
        g_error_free(error);
        return;
      }

      type_value = fl_value_new_string("Image");
      content_value = fl_value_new_string(filename);
    }
    else
    {
      FL_METHOD_RESPONSE(fl_method_error_response_new("Unsupported clipboard format", "Unsupported clipboard format", NULL));
      return;
    }
  }
  fl_value_set(result_data, content_key, content_value);
  fl_value_set(result_data, type_key, type_value);

  // 声明变量来接收结果
  gchar *app_name = NULL;
  gchar *package_name = NULL;
  gchar *iconB64 = NULL;
  // 调用函数
  gboolean success = get_foreground_app(&app_name, &package_name, &iconB64);
  if (success) {
      g_autoptr(FlValue) source_key = fl_value_new_string("source");
      g_autoptr(FlValue) id_key = fl_value_new_string("id");
      g_autoptr(FlValue) name_key = fl_value_new_string("name");
      g_autoptr(FlValue) iconB64_key = fl_value_new_string("iconB64");

      g_autoptr(FlValue) source = fl_value_new_map();

      if (app_name){
          g_autoptr(FlValue) source_app_name = fl_value_new_string(app_name);
          fl_value_set(source, name_key, source_app_name);
      }
      if (package_name){
          g_autoptr(FlValue) source_package_name = fl_value_new_string(package_name);
          fl_value_set(source, id_key, source_package_name);
      }
      if(iconB64){
          g_autoptr(FlValue) source_icon_b64 = fl_value_new_string(iconB64);
          fl_value_set(source, iconB64_key, source_icon_b64);
      }
      fl_value_set(result_data, source_key, source);

      g_free(app_name);
      g_free(package_name);
      g_free(iconB64);
  } else {
      debug_printf("获取前台应用失败\n");
  }
  fl_method_channel_invoke_method(plugin->channel, kOnClipboardChanged, result_data, nullptr, nullptr, nullptr);
}

static FlMethodResponse *startListening(ClipshareClipboardListenerPlugin *self, FlValue *args)
{
  self->running = true;
  g_autoptr(FlValue) result = fl_value_new_bool(true);
  return FL_METHOD_RESPONSE(fl_method_success_response_new(result));
}

static FlMethodResponse *stopListening(ClipshareClipboardListenerPlugin *self, FlValue *args)
{
  self->running = false;
  g_autoptr(FlValue) result = fl_value_new_bool(true);
  return FL_METHOD_RESPONSE(fl_method_success_response_new(result));
}

static FlMethodResponse *checkIsRunning(ClipshareClipboardListenerPlugin *self, FlValue *args)
{

  g_autoptr(FlValue) result = fl_value_new_bool(self->running);
  return FL_METHOD_RESPONSE(fl_method_success_response_new(result));
}

static FlMethodResponse *copyData(ClipshareClipboardListenerPlugin *self, const gchar *type, const gchar *content)
{
  bool success = false;
  GtkClipboard *clipboard = gtk_clipboard_get(GDK_SELECTION_CLIPBOARD);
  self->ignoreNextCopy = true;
  if (strcmp(type, "text") == 0)
  {
    // 将文本设置到剪贴板
    gtk_clipboard_set_text(clipboard, content, -1);
    // 保持剪贴板内容
    gtk_clipboard_store(clipboard);
    success = true;
  }
  else if (strcmp(type, "image") == 0)
  {

    // 从文件路径加载图片为 GdkPixbuf
    GError *error = NULL;
    GdkPixbuf *pixbuf = gdk_pixbuf_new_from_file(content, &error);

    if (pixbuf == NULL)
    {
      debug_printf("Failed to load image: %s\n", error->message);
      g_error_free(error);
    }
    else
    {
      // 将图片设置到剪贴板
      gtk_clipboard_set_image(clipboard, pixbuf);
      // 保持剪贴板内容
      gtk_clipboard_store(clipboard);
      // 释放 GdkPixbuf
      g_object_unref(pixbuf);
      success = true;
    }
  }
  g_autoptr(FlValue) result = fl_value_new_bool(success);
  return FL_METHOD_RESPONSE(fl_method_success_response_new(result));
}
static Window previousWindow = 0;

static FlMethodResponse *storeCurrentWindowHwnd(ClipshareClipboardListenerPlugin *self)
{
    previousWindow = getWindowId();
    g_autoptr(FlValue) result = fl_value_new_bool(previousWindow != 0);
    return FL_METHOD_RESPONSE(fl_method_success_response_new(result));
}

static gboolean activateAndPaste(int64_t dealyMs) {
    Display* display;
    if(!activeWindow(previousWindow, &display)){
        return false;
    }
    // 延迟等待窗口真正获得焦点
    usleep(dealyMs * 1000);
    send_ctrl_v(display);
    XCloseDisplay(display);
    return true;
}
static FlMethodResponse *pasteToPreviousWindow(ClipshareClipboardListenerPlugin *self, int64_t delayMs)
{
    g_autoptr(FlValue) result = fl_value_new_bool(activateAndPaste(delayMs));
    return FL_METHOD_RESPONSE(fl_method_success_response_new(result));
}