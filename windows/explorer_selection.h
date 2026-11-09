#ifndef FLUTTER_PLUGIN_CLIPSHARE_EXPLORER_SELECTION_H_
#define FLUTTER_PLUGIN_CLIPSHARE_EXPLORER_SELECTION_H_

#include <string>

namespace clipshare_clipboard_listener {

	// 获取前台资源管理器窗口（或桌面）中用户选中的文件完整路径，以 ";" 拼接
	bool GetSelectedFiles(std::string& fileList);

}  // namespace clipshare_clipboard_listener

#endif  // FLUTTER_PLUGIN_CLIPSHARE_EXPLORER_SELECTION_H_
