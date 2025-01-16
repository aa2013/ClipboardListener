#include "include/clipboard_listener/clipboard_listener_plugin_c_api.h"

#include <flutter/plugin_registrar_windows.h>

#include "clipboard_listener_plugin.h"

void ClipboardListenerPluginCApiRegisterWithRegistrar(
    FlutterDesktopPluginRegistrarRef registrar) {
  clipboard_listener::ClipboardListenerPlugin::RegisterWithRegistrar(
      flutter::PluginRegistrarManager::GetInstance()
          ->GetRegistrar<flutter::PluginRegistrarWindows>(registrar));
}
