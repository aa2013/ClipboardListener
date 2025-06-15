#include "include/clipshare_clipboard_listener/clipshare_clipboard_listener_plugin.h"

#include <flutter_linux/flutter_linux.h>
#include <gtk/gtk.h>
#include <gdk-pixbuf/gdk-pixbuf.h>
#include <sys/utsname.h>

#include <cstring>
#include <string>
#include <regex>
#include <chrono>
#include <ctime>
#include <glib.h>

#include "clipshare_clipboard_listener_plugin_private.h"

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
static gchar *getCurrentTimeWithMilliseconds()
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
      g_print("Image found on clipboard\n");
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
      g_print("Failed to load image: %s\n", error->message);
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