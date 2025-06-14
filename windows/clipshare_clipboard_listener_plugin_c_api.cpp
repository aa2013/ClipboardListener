#include "include/clipshare_clipboard_listener/clipshare_clipboard_listener_plugin_c_api.h"

#include <flutter/plugin_registrar_windows.h>

#include "clipshare_clipboard_listener_plugin.h"

void ClipshareClipboardListenerPluginCApiRegisterWithRegistrar(
    FlutterDesktopPluginRegistrarRef registrar) {
  clipshare_clipboard_listener::ClipshareClipboardListenerPlugin::RegisterWithRegistrar(
      flutter::PluginRegistrarManager::GetInstance()
          ->GetRegistrar<flutter::PluginRegistrarWindows>(registrar));
}
