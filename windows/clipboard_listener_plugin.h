#ifndef FLUTTER_PLUGIN_CLIPBOARD_LISTENER_PLUGIN_H_
#define FLUTTER_PLUGIN_CLIPBOARD_LISTENER_PLUGIN_H_

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>

#include <memory>

namespace clipboard_listener {

class ClipboardListenerPlugin : public flutter::Plugin {
 public:
  static void RegisterWithRegistrar(flutter::PluginRegistrarWindows *registrar);

  ClipboardListenerPlugin();

  virtual ~ClipboardListenerPlugin();

  // Disallow copy and assign.
  ClipboardListenerPlugin(const ClipboardListenerPlugin&) = delete;
  ClipboardListenerPlugin& operator=(const ClipboardListenerPlugin&) = delete;

  // Called when a method is called on this plugin's channel from Dart.
  void HandleMethodCall(
      const flutter::MethodCall<flutter::EncodableValue> &method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
};

}  // namespace clipboard_listener

#endif  // FLUTTER_PLUGIN_CLIPBOARD_LISTENER_PLUGIN_H_
