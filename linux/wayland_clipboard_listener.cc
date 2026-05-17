#include "wayland_clipboard_listener.h"

// Wayland ext-data-control 协议头文件。
// 该协议允许程序监听/设置 Wayland 剪贴板。
#include "ext-data-control-v1-client-protocol.h"
#include "include/clipshare_clipboard_listener/utils.h"

#include <errno.h>
#include <fcntl.h>
#include <gdk-pixbuf/gdk-pixbuf.h>
#include <poll.h>
#include <string.h>
#include <unistd.h>
#include <wayland-client.h>

// =========================
// WaylandClipboardListener
// =========================
// 这是整个监听器对象的核心结构体。
// 它保存：
// - Wayland 连接
// - 剪贴板设备
// - 后台线程
// - 命令队列
// - 回调函数
// 等所有运行时状态。
struct _WaylandClipboardListener {
  // 外部拥有者对象（通常是某个 GObject）
  GObject *owner;
  // 剪贴板变化时触发的回调
  WaylandClipboardChangedCallback callback;
  // 主线程上下文。
  // 用于把后台线程中的事件安全投递回 UI / 主线程。
  GMainContext *main_context;
  // Wayland display 连接
  wl_display *display;
  // Wayland registry
  // 用于发现 compositor 暴露的全局接口。
  wl_registry *registry;
  // 当前 seat（键盘/鼠标/输入设备集合）
  wl_seat *seat;
  // ext-data-control 管理器
  ext_data_control_manager_v1 *manager;
  // 当前 seat 对应的数据设备
  ext_data_control_device_v1 *device;
  // 后台事件线程
  GThread *thread;
  // 异步命令队列。
  // 主线程把“设置剪贴板”的命令压入队列，
  // 后台线程消费。
  GAsyncQueue *commands;
  // 当前由本程序“拥有”的 clipboard source。
  // 当 Wayland compositor 不再需要 source 时会自动释放。
  GPtrArray *owned_sources;
  // 用于唤醒 poll 的 pipe。
  // command_fds[0] -> read
  // command_fds[1] -> write
  int command_fds[2];
  // 是否停止线程。
  // 使用原子变量避免竞态。
  gint should_stop;
  // 跳过 selection 事件次数。
  // 当程序自己设置剪贴板时，
  // Wayland 也会回调 selection changed。
  // 这里用于避免把“自己设置的内容”当成外部更新。
  guint skip_selection_count;
  // 忽略事件截止时间。
  // 程序启动初期会收到一堆历史/初始化事件，
  // 这里用于静默一段时间。
  gint64 ignore_events_until_us;
};

namespace {

// poll 超时时间
constexpr gint kWaylandPollTimeoutMs = 250;
// 接收剪贴板内容超时时间
constexpr gint kClipboardReceiveTimeoutMs = 3000;
// 启动后初始静默时间
constexpr gint kInitialSelectionSilenceMs = 3000;
// 最大允许剪贴板大小（50MB）
constexpr gsize kMaxClipboardBytes = 50 * 1024 * 1024;

struct ClipboardOffer;

// =========================
// ClipboardOffer
// =========================
// 表示一次来自 Wayland 的 clipboard offer。
// offer 中会包含：
// - MIME 类型
// - 数据读取接口
struct ClipboardOffer {
  WaylandClipboardListener *listener;
  ext_data_control_offer_v1 *offer;
  GPtrArray *mime_types;
};

// =========================
// ClipboardChangedEvent
// =========================
// 用于跨线程投递事件。
struct ClipboardChangedEvent {
  GObject *owner;
  WaylandClipboardChangedCallback callback;

  // 类型：Text / Image
  gchar *type;

  // 内容：
  // - 文本内容
  // - 图片文件路径
  gchar *content;
};

// =========================
// ClipboardSource
// =========================
// 表示当前程序“提供”的剪贴板数据源。
// 当其它应用请求读取剪贴板时，
// Wayland 会通过它把数据发送出去。
struct ClipboardSource {
  WaylandClipboardListener *listener;
  // Wayland source 对象
  ext_data_control_source_v1 *source;
  // 实际数据
  GBytes *bytes;
  // 支持的 MIME 类型
  GPtrArray *mime_types;
};

// =========================
// SetSelectionCommand
// =========================
// “设置剪贴板”的异步命令。
// 主线程创建 -> 后台线程消费。
struct SetSelectionCommand {
  GBytes *bytes;
  GPtrArray *mime_types;
};

// 释放 ClipboardSource
void clipboard_source_free(gpointer data) {
  auto *source = static_cast<ClipboardSource *>(data);
  if (!source) {
    return;
  }
  // 销毁 Wayland source
  if (source->source) {
    ext_data_control_source_v1_destroy(source->source);
  }
  // 释放数据引用
  if (source->bytes) {
    g_bytes_unref(source->bytes);
  }
  // 释放 MIME 列表
  if (source->mime_types) {
    g_ptr_array_unref(source->mime_types);
  }
  g_free(source);
}

// 释放异步命令
void set_selection_command_free(SetSelectionCommand *command) {
  if (!command) {
    return;
  }
  if (command->bytes) {
    g_bytes_unref(command->bytes);
  }
  if (command->mime_types) {
    g_ptr_array_unref(command->mime_types);
  }
  g_free(command);
}

// 创建 MIME 类型数组
GPtrArray *new_mime_type_array() {
  // free_func = g_free
  // 数组中的字符串会自动释放
  return g_ptr_array_new_with_free_func(g_free);
}

// 向 MIME 数组添加 MIME 类型
void add_mime_type(GPtrArray *mime_types, const gchar *mime_type) {
  g_ptr_array_add(mime_types, g_strdup(mime_type));
}

// 创建“设置剪贴板”命令
SetSelectionCommand *set_selection_command_new(GBytes *bytes,
                                               GPtrArray *mime_types) {
  auto *command = g_new0(SetSelectionCommand, 1);
  // 增加引用计数
  command->bytes = static_cast<GBytes *>(g_bytes_ref(bytes));
  command->mime_types = g_ptr_array_ref(mime_types);
  return command;
}

// 判断 MIME 类型数组中是否包含指定 MIME
// 例如：
// text/plain
// image/png
// 等。
gboolean mime_types_contains(GPtrArray *mime_types, const gchar *mime_type) {
  if (!mime_types || !mime_type) {
    return FALSE;
  }
  for (guint i = 0; i < mime_types->len; ++i) {
    if (g_strcmp0(static_cast<const gchar *>(g_ptr_array_index(mime_types, i)),
                  mime_type) == 0) {
      return TRUE;
    }
  }
  return FALSE;
}

// =========================
// write_all
// =========================
// 保证完整写入 fd。
// write() 可能部分写入，因此需要循环。
gboolean write_all(int fd, const guint8 *data, gsize length) {
  gsize written = 0;
  while (written < length) {
    ssize_t ret = write(fd, data + written, length - written);
    if (ret > 0) {
      written += static_cast<gsize>(ret);
      continue;
    }
    // EINTR = 被信号中断
    // 重试即可。
    if (ret < 0 && errno == EINTR) {
      continue;
    }
    debug_printf("Wayland clipboard source write failed: %s",
                 ret < 0 ? g_strerror(errno) : "zero bytes written");
    return FALSE;
  }
  return TRUE;
}

// =========================
// deliver_clipboard_changed
// =========================
// 真正执行 callback。
// 该函数运行于主线程。
gboolean deliver_clipboard_changed(gpointer user_data) {
  auto *event = static_cast<ClipboardChangedEvent *>(user_data);
  // 调用外部回调
  if (event->owner && event->callback) {
    event->callback(event->owner, event->type, event->content);
  }
  // 清理资源
  if (event->owner) {
    g_object_unref(event->owner);
  }
  g_free(event->type);
  g_free(event->content);
  g_free(event);
  return G_SOURCE_REMOVE;
}

// 向主线程投递剪贴板变化事件
void notify_clipboard_changed(WaylandClipboardListener *listener,
                              const gchar *type,
                              const gchar *content) {
  if (!listener || !listener->callback || !listener->owner) {
    return;
  }

  auto *event = g_new0(ClipboardChangedEvent, 1);
  // 增加 owner 引用，防止事件投递期间对象被释放
  event->owner = G_OBJECT(g_object_ref(listener->owner));
  event->callback = listener->callback;
  event->type = g_strdup(type);
  event->content = g_strdup(content);
  // 投递到主线程执行
  g_main_context_invoke(listener->main_context, deliver_clipboard_changed,
                        event);
}

// 释放 clipboard offer
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

// =========================
// clipboard_source_send
// =========================
// 当其它程序请求读取我们的剪贴板时，
// Wayland 会回调这里。
void clipboard_source_send(void *data,
                           ext_data_control_source_v1 * /* source */,
                           const char *mime_type,
                           int32_t fd) {
  auto *source = static_cast<ClipboardSource *>(data);
  // 不支持该 MIME 时直接关闭 fd
  if (!source || !source->bytes ||
      !mime_types_contains(source->mime_types, mime_type)) {
    close(fd);
    return;
  }

  // 读取 bytes 数据
  gsize length = 0;
  const guint8 *bytes =
      static_cast<const guint8 *>(g_bytes_get_data(source->bytes, &length));
  // 写入到 Wayland 提供的 fd
  if (bytes && length > 0) {
    write_all(fd, bytes, length);
  }
  close(fd);
}

// source 被 compositor 取消时调用
void clipboard_source_cancelled(void *data,
                                ext_data_control_source_v1 * /* source */) {
  auto *source = static_cast<ClipboardSource *>(data);
  if (!source) {
    return;
  }
  // 从 owned_sources 中移除
  if (source->listener && source->listener->owned_sources) {
    if (g_ptr_array_remove(source->listener->owned_sources, source)) {
      return;
    }
  }
  clipboard_source_free(source);
}

// Wayland source listener
const ext_data_control_source_v1_listener kSourceListener = {
    clipboard_source_send,
    clipboard_source_cancelled,
};

// 从 offer 中查找支持的 MIME
const gchar *find_mime_type(ClipboardOffer *offer,
                            const gchar *const *candidates) {
  if (!offer || !offer->mime_types) {
    return nullptr;
  }
  // 遍历候选 MIME
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

// 唤醒命令 pipe
// 用于唤醒 poll()。
void wake_command_pipe(WaylandClipboardListener *listener) {
  if (!listener || listener->command_fds[1] < 0) {
    return;
  }

  char byte = 1;
  while (write(listener->command_fds[1], &byte, 1) < 0) {
    if (errno == EINTR) {
      continue;
    }
    if (errno != EAGAIN && errno != EWOULDBLOCK) {
      debug_printf("Wayland clipboard command wake failed: %s",
                   g_strerror(errno));
    }
    return;
  }
}

// 清空 pipe 数据
void drain_command_pipe(WaylandClipboardListener *listener) {
  if (!listener || listener->command_fds[0] < 0) {
    return;
  }

  char buffer[64];
  // 持续读取直到 pipe 空
  while (read(listener->command_fds[0], buffer, sizeof(buffer)) > 0) {
  }
}

// 把“设置剪贴板”命令压入队列
gboolean enqueue_set_selection_command(WaylandClipboardListener *listener,
                                       SetSelectionCommand *command) {
  if (!wayland_clipboard_listener_is_available(listener) ||
      !listener->commands || !command) {
    set_selection_command_free(command);
    return FALSE;
  }

  // 放入异步队列
  g_async_queue_push(listener->commands, command);
  // 唤醒后台线程
  wake_command_pipe(listener);
  return TRUE;
}

// 真正执行设置剪贴板操作
void apply_set_selection_command(WaylandClipboardListener *listener,
                                 SetSelectionCommand *command) {
  if (!wayland_clipboard_listener_is_available(listener) || !command ||
      !command->bytes || !command->mime_types || command->mime_types->len == 0) {
    return;
  }

  // 创建新的 clipboard source
  auto *source = g_new0(ClipboardSource, 1);
  source->listener = listener;
  source->source =
      ext_data_control_manager_v1_create_data_source(listener->manager);
  if (!source->source) {
    clipboard_source_free(source);
    debug_printf("Wayland data-control can not create clipboard source");
    return;
  }

  // 保存数据引用
  source->bytes = static_cast<GBytes *>(g_bytes_ref(command->bytes));
  source->mime_types = g_ptr_array_ref(command->mime_types);
  // 注册 Wayland 回调
  ext_data_control_source_v1_add_listener(source->source, &kSourceListener, source);
  // 告诉 Wayland 支持哪些 MIME
  for (guint i = 0; i < source->mime_types->len; ++i) {
    ext_data_control_source_v1_offer(
        source->source,
        static_cast<const gchar *>(g_ptr_array_index(source->mime_types, i)));
  }

  // 保存 source
  g_ptr_array_add(listener->owned_sources, source);
  // 避免自己设置的 selection 再触发监听
  listener->skip_selection_count++;
  // 设置系统剪贴板
  ext_data_control_device_v1_set_selection(listener->device, source->source);
}

// 处理所有待执行命令
void process_pending_commands(WaylandClipboardListener *listener) {
  if (!listener || !listener->commands) {
    return;
  }

  while (true) {
    auto *command = static_cast<SetSelectionCommand *>(
        g_async_queue_try_pop(listener->commands));
    if (!command) {
      return;
    }
    apply_set_selection_command(listener, command);
    set_selection_command_free(command);
  }
}

// 刷新 Wayland display 输出缓冲
// Wayland 使用异步 socket 通信，
// flush 才会真正发送请求。
gboolean flush_wayland_display(wl_display *display) {
  while (true) {
    if (wl_display_flush(display) >= 0) {
      return TRUE;
    }
    if (errno != EAGAIN) {
      debug_printf("Wayland display flush failed: %s", g_strerror(errno));
      return FALSE;
    }

    // socket 不可写时 poll 等待
    pollfd pfd = {wl_display_get_fd(display), POLLOUT, 0};
    int ret = poll(&pfd, 1, kClipboardReceiveTimeoutMs);
    if (ret <= 0) {
      debug_printf("Wayland display flush timed out or failed");
      return FALSE;
    }
  }
}

// =========================
// receive_offer_bytes
// =========================
// 从 Wayland offer 中读取实际剪贴板数据。
GBytes *receive_offer_bytes(WaylandClipboardListener *listener,
                            ClipboardOffer *offer,
                            const gchar *mime_type) {
  // 创建匿名 pipe
  // Wayland 会把数据写入 pipe_fds[1]
  // 我们从 pipe_fds[0] 读取
  int pipe_fds[2];
  if (pipe(pipe_fds) != 0) {
    debug_printf("Can not create pipe for Wayland clipboard: %s",
                 g_strerror(errno));
    return nullptr;
  }

  // 设置为非阻塞读取
  int flags = fcntl(pipe_fds[0], F_GETFL, 0);
  if (flags >= 0) {
    fcntl(pipe_fds[0], F_SETFL, flags | O_NONBLOCK);
  }

  // 请求 Wayland 把数据写入 fd
  ext_data_control_offer_v1_receive(offer->offer, mime_type, pipe_fds[1]);
  close(pipe_fds[1]);

  // 强制发送 Wayland 请求
  if (!flush_wayland_display(listener->display)) {
    close(pipe_fds[0]);
    return nullptr;
  }

  // 用于动态累积读取的数据
  GByteArray *data = g_byte_array_new();
  // 超时截止时间
  gint64 deadline =
      g_get_monotonic_time() + kClipboardReceiveTimeoutMs * G_TIME_SPAN_MILLISECOND;
  gboolean completed = FALSE;
  gboolean failed = FALSE;

  while (!completed && !failed) {
    // 计算剩余时间
    gint64 remaining_us = deadline - g_get_monotonic_time();
    if (remaining_us <= 0) {
      debug_printf("Wayland clipboard receive timed out");
      failed = TRUE;
      break;
    }

    int timeout_ms = static_cast<int>((remaining_us + 999) / 1000);
    // poll 等待 pipe 可读
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

    // 持续读取数据
    while (true) {
      guint8 buffer[8192];
      ssize_t bytes_read = read(pipe_fds[0], buffer, sizeof(buffer));
      if (bytes_read > 0) {
        // 防止超大数据占满内存
        if (data->len + static_cast<gsize>(bytes_read) > kMaxClipboardBytes) {
          debug_printf("Wayland clipboard data is too large");
          failed = TRUE;
          break;
        }
        g_byte_array_append(data, buffer, static_cast<guint>(bytes_read));
        continue;
      }
      // EOF
      if (bytes_read == 0) {
        completed = TRUE;
        break;
      }
      // 非阻塞无数据
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

    // 对端关闭
    if (pfd.revents & POLLHUP) {
      completed = TRUE;
    }
  }

  close(pipe_fds[0]);

  if (failed) {
    g_byte_array_unref(data);
    return nullptr;
  }

  // 转换成 GBytes 返回
  return g_byte_array_free_to_bytes(data);
}

// 根据 MIME 类型返回文件扩展名
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

// 把图片 bytes 保存为临时文件
// 返回文件路径。
gchar *save_image_bytes_to_temp_file(GBytes *bytes, const gchar *mime_type) {
  gsize length = 0;
  const gchar *data =
      static_cast<const gchar *>(g_bytes_get_data(bytes, &length));
  if (!data || length == 0) {
    return nullptr;
  }

  // 用时间戳生成文件名
  g_autofree gchar *current_time = getCurrentTimeWithMilliseconds();
  gchar *filename = g_strdup_printf("/tmp/%s.%s", current_time,
                                    extension_for_image_mime(mime_type));
  GError *error = nullptr;
  // 写入文件
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

// 处理收到的剪贴板 selection
void handle_selection_offer(ClipboardOffer *offer) {
  if (!offer || !offer->listener) {
    clipboard_offer_free(offer);
    return;
  }

  // 支持的文本 MIME
  static const gchar *const text_mimes[] = {
      "text/plain;charset=utf-8",
      "text/plain;charset=UTF-8",
      "text/plain",
      nullptr,
  };
  // 支持的图片 MIME
  static const gchar *const image_mimes[] = {
      "image/png",
      "image/jpeg",
      "image/jpg",
      "image/bmp",
      "image/tiff",
      nullptr,
  };

  // 优先文本，其次图片
  const gchar *text_mime = find_mime_type(offer, text_mimes);
  const gchar *image_mime = find_mime_type(offer, image_mimes);
  const gchar *mime_type = text_mime ? text_mime : image_mime;
  // 不支持的 MIME 直接忽略
  if (!mime_type) {
    clipboard_offer_free(offer);
    return;
  }

  // 读取实际数据
  g_autoptr(GBytes) bytes =
      receive_offer_bytes(offer->listener, offer, mime_type);
  if (!bytes) {
    clipboard_offer_free(offer);
    return;
  }

  // 文本处理
  if (text_mime) {
    gsize length = 0;
    const gchar *data =
        static_cast<const gchar *>(g_bytes_get_data(bytes, &length));
    g_autofree gchar *text = g_strndup(data ? data : "", length);
    notify_clipboard_changed(offer->listener, "Text", text);
  } else {
    // 图片处理
    g_autofree gchar *filename =
        save_image_bytes_to_temp_file(bytes, image_mime);
    if (filename) {
      notify_clipboard_changed(offer->listener, "Image", filename);
    }
  }

  clipboard_offer_free(offer);
}

// Wayland offer 回调：收到 MIME 类型
void offer_offer(void *data,
                 ext_data_control_offer_v1 * /* ext_data_control_offer_v1 */,
                 const char *mime_type) {
  auto *offer = static_cast<ClipboardOffer *>(data);
  if (offer && offer->mime_types && mime_type) {
    g_ptr_array_add(offer->mime_types, g_strdup(mime_type));
  }
}

// offer listener
const ext_data_control_offer_v1_listener kOfferListener = {
    offer_offer,
};

// 收到新的 data offer
void device_data_offer(void *data,
                       ext_data_control_device_v1 * /* device */,
                       ext_data_control_offer_v1 *id) {
  auto *listener = static_cast<WaylandClipboardListener *>(data);
  auto *offer = g_new0(ClipboardOffer, 1);
  offer->listener = listener;
  offer->offer = id;
  // 保存 MIME 列表
  offer->mime_types = g_ptr_array_new_with_free_func(g_free);
  // 绑定用户数据
  ext_data_control_offer_v1_set_user_data(id, offer);
  // 注册 listener
  ext_data_control_offer_v1_add_listener(id, &kOfferListener, offer);
}

// selection 改变时调用
void device_selection(void *data,
                      ext_data_control_device_v1 * /* device */,
                      ext_data_control_offer_v1 *id) {
  auto *listener = static_cast<WaylandClipboardListener *>(data);
  // selection 被清空
  if (!id) {
    if (listener && listener->skip_selection_count > 0) {
      listener->skip_selection_count--;
    }
    return;
  }
  auto *offer = static_cast<ClipboardOffer *>(
      ext_data_control_offer_v1_get_user_data(id));
  if (listener) {
    // 跳过自己触发的事件
    if (listener->skip_selection_count > 0) {
      listener->skip_selection_count--;
      clipboard_offer_free(offer);
      return;
    }
    // 启动初期静默
    if (listener->ignore_events_until_us > g_get_monotonic_time()) {
      clipboard_offer_free(offer);
      return;
    }
  }
  handle_selection_offer(offer);
}

// device 结束
void device_finished(void *data, ext_data_control_device_v1 * /* device */) {
  auto *listener = static_cast<WaylandClipboardListener *>(data);
  g_atomic_int_set(&listener->should_stop, TRUE);
}

// primary selection（通常是鼠标选中）
// 当前实现直接忽略。
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

// device listener
const ext_data_control_device_v1_listener kDeviceListener = {
    device_data_offer,
    device_selection,
    device_finished,
    device_primary_selection,
};

// registry 新增全局对象
void registry_global(void *data,
                     wl_registry *registry,
                     uint32_t name,
                     const char *interface,
                     uint32_t version) {
  auto *listener = static_cast<WaylandClipboardListener *>(data);
  // 获取 seat
  if (g_strcmp0(interface, wl_seat_interface.name) == 0 && !listener->seat) {
    listener->seat = static_cast<wl_seat *>(
        wl_registry_bind(registry, name, &wl_seat_interface, 1));
  // 获取 ext-data-control manager
  } else if (g_strcmp0(interface, ext_data_control_manager_v1_interface.name) ==
                 0 &&
             !listener->manager) {
    listener->manager = static_cast<ext_data_control_manager_v1 *>(
        wl_registry_bind(registry, name, &ext_data_control_manager_v1_interface,
                         version < 1 ? version : 1));
  }
}

// 全局对象移除
// 当前未处理。
void registry_global_remove(void * /* data */,
                            wl_registry * /* registry */,
                            uint32_t /* name */) {}

const wl_registry_listener kRegistryListener = {
    registry_global,
    registry_global_remove,
};

// =========================
// Wayland 后台事件线程
// =========================
gpointer wayland_dispatch_thread(gpointer user_data) {
  auto *listener = static_cast<WaylandClipboardListener *>(user_data);
  while (!g_atomic_int_get(&listener->should_stop)) {
    // 先处理待执行命令
    process_pending_commands(listener);

    // prepare_read 是 Wayland 推荐的线程安全读流程
    while (wl_display_prepare_read(listener->display) != 0) {
      if (wl_display_dispatch_pending(listener->display) < 0) {
        debug_printf("Wayland dispatch pending failed");
        return nullptr;
      }
    }

    // flush 输出缓冲
    if (wl_display_flush(listener->display) < 0 && errno != EAGAIN) {
      debug_printf("Wayland display flush failed in dispatch thread: %s",
                   g_strerror(errno));
      wl_display_cancel_read(listener->display);
      break;
    }

    // 同时监听：
    // 1. Wayland socket
    // 2. command pipe
    pollfd pfds[2] = {
        {wl_display_get_fd(listener->display), POLLIN, 0},
        {listener->command_fds[0], POLLIN, 0},
    };
    nfds_t fd_count = listener->command_fds[0] >= 0 ? 2 : 1;
    int ret = poll(pfds, fd_count, kWaylandPollTimeoutMs);
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
    // 超时
    if (ret == 0) {
      wl_display_cancel_read(listener->display);
      continue;
    }
    gboolean display_has_data = pfds[0].revents & POLLIN;
    gboolean command_has_data =
        fd_count > 1 && (pfds[1].revents & (POLLIN | POLLERR | POLLHUP));
    // Wayland socket 有数据
    if (display_has_data) {
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
      if (pfds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
        debug_printf("Wayland display poll returned an error");
        break;
      }
    }
    // command pipe 有数据
    if (command_has_data) {
      drain_command_pipe(listener);
      process_pending_commands(listener);
    }
  }
  g_atomic_int_set(&listener->should_stop, TRUE);
  return nullptr;
}

}  // namespace

// =========================
// 创建监听器
// =========================
WaylandClipboardListener *wayland_clipboard_listener_new(
    GObject *owner,
    WaylandClipboardChangedCallback callback) {
  // 非 Wayland 环境直接失败
  if (!is_wayland()) {
    return nullptr;
  }

  auto *listener = g_new0(WaylandClipboardListener, 1);
  listener->command_fds[0] = -1;
  listener->command_fds[1] = -1;
  listener->owner = owner;
  listener->callback = callback;
  // 保存主线程 context
  listener->main_context = g_main_context_ref(g_main_context_default());
  // 创建异步命令队列
  listener->commands = g_async_queue_new();
  // 保存当前拥有的 source
  listener->owned_sources =
      g_ptr_array_new_with_free_func(clipboard_source_free);
  // 创建唤醒 pipe
  if (pipe(listener->command_fds) != 0) {
    debug_printf("Can not create Wayland command pipe: %s", g_strerror(errno));
    wayland_clipboard_listener_free(listener);
    return nullptr;
  }
  // 设置 pipe 为非阻塞
  int flags = fcntl(listener->command_fds[0], F_GETFL, 0);
  if (flags >= 0) {
    fcntl(listener->command_fds[0], F_SETFL, flags | O_NONBLOCK);
  }
  flags = fcntl(listener->command_fds[1], F_GETFL, 0);
  if (flags >= 0) {
    fcntl(listener->command_fds[1], F_SETFL, flags | O_NONBLOCK);
  }

  // 连接 Wayland server
  listener->display = wl_display_connect(nullptr);
  if (!listener->display) {
    debug_printf("Can not connect to Wayland display");
    wayland_clipboard_listener_free(listener);
    return nullptr;
  }

  // 获取 registry
  listener->registry = wl_display_get_registry(listener->display);
  wl_registry_add_listener(listener->registry, &kRegistryListener, listener);
  // roundtrip 强制同步，等待 registry 回调
  if (wl_display_roundtrip(listener->display) < 0) {
    debug_printf("Wayland registry roundtrip failed");
    wayland_clipboard_listener_free(listener);
    return nullptr;
  }

  // 必须拿到 manager 和 seat
  if (!listener->manager || !listener->seat) {
    debug_printf("Wayland ext-data-control is not available");
    wayland_clipboard_listener_free(listener);
    return nullptr;
  }

  // 创建数据设备
  listener->device =
      ext_data_control_manager_v1_get_data_device(listener->manager,
                                                  listener->seat);
  // 初始跳过一次 selection
  listener->skip_selection_count = 1;
  // 启动后静默一段时间
  listener->ignore_events_until_us =
      g_get_monotonic_time() + kInitialSelectionSilenceMs * G_TIME_SPAN_MILLISECOND;
  // 注册 device listener
  ext_data_control_device_v1_add_listener(listener->device, &kDeviceListener,
                                          listener);
  // 再次同步
  if (wl_display_roundtrip(listener->display) < 0) {
    debug_printf("Wayland data-control roundtrip failed");
    wayland_clipboard_listener_free(listener);
    return nullptr;
  }

  // 创建后台线程
  listener->thread =
      g_thread_new("clipshare-wayland-clipboard", wayland_dispatch_thread,
                   listener);
  debug_printf("Wayland ext-data-control clipboard listener started");
  return listener;
}

// =========================
// 销毁监听器
// =========================
void wayland_clipboard_listener_free(WaylandClipboardListener *listener) {
  if (!listener) {
    return;
  }

  // 通知线程退出
  g_atomic_int_set(&listener->should_stop, TRUE);
  // 唤醒 poll
  wake_command_pipe(listener);
  // 等待线程结束
  if (listener->thread) {
    g_thread_join(listener->thread);
  }
  // 清理命令队列
  if (listener->commands) {
    while (true) {
      auto *command = static_cast<SetSelectionCommand *>(
          g_async_queue_try_pop(listener->commands));
      if (!command) {
        break;
      }
      set_selection_command_free(command);
    }
    g_async_queue_unref(listener->commands);
  }
  // 清理 source
  if (listener->owned_sources) {
    g_ptr_array_unref(listener->owned_sources);
  }
  // 关闭 pipe
  if (listener->command_fds[0] >= 0) {
    close(listener->command_fds[0]);
  }
  if (listener->command_fds[1] >= 0) {
    close(listener->command_fds[1]);
  }
  // 销毁 Wayland 对象
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

// 判断监听器是否可用
gboolean wayland_clipboard_listener_is_available(
    WaylandClipboardListener *listener) {
  return listener && listener->display && listener->manager &&
         listener->seat && listener->device;
}

// =========================
// 设置文本剪贴板
// =========================
gboolean wayland_clipboard_listener_set_text(
    WaylandClipboardListener *listener,
    const gchar *text) {
  if (!wayland_clipboard_listener_is_available(listener)) {
    return FALSE;
  }

  const gchar *safe_text = text ? text : "";
  // 创建字节对象
  g_autoptr(GBytes) bytes = g_bytes_new(safe_text, strlen(safe_text));
  // 声明 MIME
  g_autoptr(GPtrArray) mime_types = new_mime_type_array();
  add_mime_type(mime_types, "text/plain;charset=utf-8");
  add_mime_type(mime_types, "text/plain");

  // 异步提交
  return enqueue_set_selection_command(
      listener, set_selection_command_new(bytes, mime_types));
}

// =========================
// 设置图片剪贴板
// =========================
gboolean wayland_clipboard_listener_set_image(
    WaylandClipboardListener *listener,
    const gchar *path) {
  if (!wayland_clipboard_listener_is_available(listener) || !path) {
    return FALSE;
  }

  GError *error = nullptr;
  // 加载图片
  GdkPixbuf *pixbuf = gdk_pixbuf_new_from_file(path, &error);
  if (!pixbuf) {
    debug_printf("Failed to load Wayland clipboard image: %s",
                 error ? error->message : "unknown error");
    if (error) {
      g_error_free(error);
    }
    return FALSE;
  }

  gchar *buffer = nullptr;
  gsize buffer_size = 0;
  // 编码为 PNG
  gboolean saved =
      gdk_pixbuf_save_to_buffer(pixbuf, &buffer, &buffer_size, "png", &error,
                                NULL);
  g_object_unref(pixbuf);
  if (!saved) {
    debug_printf("Failed to encode Wayland clipboard image: %s",
                 error ? error->message : "unknown error");
    if (error) {
      g_error_free(error);
    }
    return FALSE;
  }

  // 防止超大图片
  if (buffer_size > kMaxClipboardBytes) {
    debug_printf("Wayland clipboard image is too large");
    g_free(buffer);
    return FALSE;
  }

  // 接管 buffer 所有权
  g_autoptr(GBytes) bytes = g_bytes_new_take(buffer, buffer_size);
  g_autoptr(GPtrArray) mime_types = new_mime_type_array();
  add_mime_type(mime_types, "image/png");

  // 异步提交
  return enqueue_set_selection_command(
      listener, set_selection_command_new(bytes, mime_types));
}
