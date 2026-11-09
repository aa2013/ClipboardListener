#include "utils.h"

// This must be included before many other Windows headers.
#include <windows.h>

#include <chrono>
#include <ctime>
#include <iomanip>
#include <sstream>

// localtime 的 4996 安全警告按插件主文件的惯例在此关闭
#pragma warning(disable : 4996)

namespace clipshare_clipboard_listener {

	std::string Utf8FromUtf16(const wchar_t* utf16_string) {
		if (utf16_string == nullptr) {
			return std::string();
		}
		int target_length = ::WideCharToMultiByte(
			CP_UTF8, WC_ERR_INVALID_CHARS, utf16_string,
			-1, nullptr, 0, nullptr, nullptr)
			- 1; // remove the trailing null character
		int input_length = (int)wcslen(utf16_string);
		std::string utf8_string;
		if (target_length <= 0 || target_length > utf8_string.max_size()) {
			return utf8_string;
		}
		utf8_string.resize(target_length);
		int converted_length = ::WideCharToMultiByte(
			CP_UTF8, WC_ERR_INVALID_CHARS, utf16_string,
			input_length, utf8_string.data(), target_length, nullptr, nullptr);
		if (converted_length == 0) {
			return std::string();
		}
		return utf8_string;
	}

	std::wstring Utf16FromUtf8(const std::string& string)
	{
		int size_needed = MultiByteToWideChar(CP_UTF8, 0, string.c_str(), -1, nullptr, 0);
		if (size_needed == 0)
		{
			return {};
		}
		std::wstring wstrTo(size_needed, 0);
		int converted_length = MultiByteToWideChar(CP_UTF8, 0, string.c_str(), -1, &wstrTo[0], size_needed);
		if (converted_length == 0)
		{
			return {};
		}
		return wstrTo;
	}

	// wstring -> UTF-8
	std::string WStringToUtf8(const std::wstring& wstr) {
		if (wstr.empty()) return {};
		int size_needed = ::WideCharToMultiByte(
			CP_UTF8, 0,
			wstr.c_str(), (int)wstr.size(),
			nullptr, 0,
			nullptr, nullptr
		);
		if (size_needed <= 0) return {};
		std::string utf8;
		utf8.resize(size_needed);
		::WideCharToMultiByte(
			CP_UTF8, 0,
			wstr.c_str(), (int)wstr.size(),
			&utf8[0], size_needed,
			nullptr, nullptr
		);
		return utf8;
	}

	std::string GetCurrentTimeWithMilliseconds()
	{
		// 获取当前时间点
		auto now = std::chrono::system_clock::now();

		// 将时间点转换为 time_t 类型
		std::time_t time = std::chrono::system_clock::to_time_t(now);

		// 获取时间结构体
		std::tm tm_time = *std::localtime(&time);

		// 获取毫秒数
		auto milliseconds = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()) % 1000;

		// 格式化时间字符串
		std::ostringstream oss;
		oss << std::put_time(&tm_time, "%Y-%m-%d %H:%M:%S") << '.' << std::setw(3) << std::setfill('0') << milliseconds.
			count();

		return oss.str();
	}

	std::wstring GetExecutableDir() {
		wchar_t buffer[MAX_PATH];
		DWORD length = GetModuleFileNameW(NULL, buffer, MAX_PATH);
		if (length == 0) {
			// Handle error
			return L"";
		}
		std::wstring path(buffer, length);
		size_t pos = path.find_last_of(L"\\/");
		return (std::wstring::npos == pos) ? L"" : path.substr(0, pos + 1);
	}

}  // namespace clipshare_clipboard_listener
