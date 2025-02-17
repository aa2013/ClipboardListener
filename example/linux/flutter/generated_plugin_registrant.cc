//
//  Generated file. Do not edit.
//

// clang-format off

#include "generated_plugin_registrant.h"

#include <clipboard_listener/clipboard_listener_plugin.h>
#include <desktop_multi_window/desktop_multi_window_plugin.h>
#include <hotkey_manager_linux/hotkey_manager_linux_plugin.h>

void fl_register_plugins(FlPluginRegistry* registry) {
  g_autoptr(FlPluginRegistrar) clipboard_listener_registrar =
      fl_plugin_registry_get_registrar_for_plugin(registry, "ClipboardListenerPlugin");
  clipboard_listener_plugin_register_with_registrar(clipboard_listener_registrar);
  g_autoptr(FlPluginRegistrar) desktop_multi_window_registrar =
      fl_plugin_registry_get_registrar_for_plugin(registry, "DesktopMultiWindowPlugin");
  desktop_multi_window_plugin_register_with_registrar(desktop_multi_window_registrar);
  g_autoptr(FlPluginRegistrar) hotkey_manager_linux_registrar =
      fl_plugin_registry_get_registrar_for_plugin(registry, "HotkeyManagerLinuxPlugin");
  hotkey_manager_linux_plugin_register_with_registrar(hotkey_manager_linux_registrar);
}
