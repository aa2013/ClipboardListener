#ifndef FLUTTER_PLUGIN_CLIPSHARE_UTILS_H_
#define FLUTTER_PLUGIN_CLIPSHARE_UTILS_H_

#include <string>

namespace clipshare_clipboard_listener {

	// UTF-16 -> UTF-8
	std::string Utf8FromUtf16(const wchar_t* utf16_string);
	// UTF-8 -> UTF-16
	std::wstring Utf16FromUtf8(const std::string& utf8_string);
	// wstring -> UTF-8
	std::string WStringToUtf8(const std::wstring& wstr);
	// 当前时间字符串（含毫秒）
	std::string GetCurrentTimeWithMilliseconds();
	// 当前进程 exe 所在目录（含结尾分隔符）
	std::wstring GetExecutableDir();

}  // namespace clipshare_clipboard_listener

#endif  // FLUTTER_PLUGIN_CLIPSHARE_UTILS_H_
