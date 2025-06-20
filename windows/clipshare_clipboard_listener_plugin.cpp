#include "clipshare_clipboard_listener_plugin.h"

// This must be included before many other Windows headers.
#include <windows.h>

// For getPlatformVersion; remove unless needed for your plugin implementation.
#include <VersionHelpers.h>

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>
#include <memory>
#include <sstream>
#include <thread>
#include <iomanip>
#include <ctime>
#include <codecvt>
#include <flutter/encodable_value.h>
#include <atlenc.h>
#include <fstream>
#include <atlimage.h>
#include <chrono>
#include <filesystem>
#include <regex>
#include "listening_hidden_window.cpp"
#include <vector>
#include <shlobj.h>
#include <atlbase.h> // CComPtr
#include <gdiplus.h>
#include <wincrypt.h>
#include <comdef.h>
#include <winver.h>
#include <shellapi.h>
#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "version.lib")
#pragma comment(lib, "crypt32.lib")
#pragma warning(disable : 4334)  // 禁用警告：32 位移位的结果被隐式转换为 64 位(是否希望进行 64 位移位?) 
#pragma warning(disable : 4996)  // 禁用废弃警告
#pragma warning(disable : 4244)  // 禁用警告：参数从 UINT 转为 BYTE 可能丢失数据
//#pragma warning(disable : 6387)  // 禁用警告 
namespace fs = std::filesystem;
typedef unsigned char uchar;


#pragma region Ctrl C + V

void down(BYTE vk)
{
	keybd_event(vk, 0, KEYEVENTF_KEYUP, 0);
}
void up(BYTE vk)
{
	keybd_event(vk, 0, KEYEVENTF_KEYUP, 0);
}
void press(BYTE vk)
{
	down(vk);
	up(vk);
}
//模拟Ctrl+V
void ctrl_v()
{
	down(VK_CONTROL);//按下Ctrl键
	press(0x56);//按下V键，并放开
	up(VK_CONTROL);//放开V键
}

#pragma endregion

#pragma region GetWindowInfo

static ULONG_PTR gdiplusToken;

static void InitGDIPlus() {
	Gdiplus::GdiplusStartupInput gdiplusStartupInput;
	Gdiplus::GdiplusStartup(&gdiplusToken, &gdiplusStartupInput, nullptr);
}

static void ShutdownGDIPlus() {
	Gdiplus::GdiplusShutdown(gdiplusToken);
}

static std::wstring GetExecutablePathFromHWND(HWND hwnd) {
	DWORD pid = 0;
	GetWindowThreadProcessId(hwnd, &pid);

	HANDLE hProcess = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ, FALSE, pid);
	if (!hProcess) return L"";

	wchar_t path[MAX_PATH];
	DWORD size = MAX_PATH;
	if (QueryFullProcessImageNameW(hProcess, 0, path, &size)) {
		CloseHandle(hProcess);
		return std::wstring(path);
	}
	CloseHandle(hProcess);
	return L"";
}

static std::wstring GetProductNameFromExe(const std::wstring& exePath) {
	DWORD dummy;
	DWORD size = GetFileVersionInfoSizeW(exePath.c_str(), &dummy);
	if (size == 0) return L"";

	std::vector<BYTE> data(size);
	if (!GetFileVersionInfoW(exePath.c_str(), 0, size, data.data())) return L"";

	void* buffer = nullptr;
	UINT len = 0;
	if (VerQueryValueW(data.data(), L"\\StringFileInfo\\040904B0\\ProductName", &buffer, &len)) {
		return std::wstring(static_cast<wchar_t*>(buffer));
	}

	return L"";
}

static HICON GetIconFromExe(const std::wstring& exePath) {
	HICON hIconLarge;
	HICON hIconSmall;
	UINT count = ExtractIconExW(exePath.c_str(), 0, &hIconLarge, &hIconSmall, 1);
	if (count > 0) return hIconLarge;
	return nullptr;
}

static std::string HIconToBase64(HICON hIcon) {
	using namespace Gdiplus;

	IStream* pStream = nullptr;
	if (CreateStreamOnHGlobal(NULL, TRUE, &pStream) != S_OK) return "";

	Bitmap bitmap(hIcon);
	CLSID pngClsid;
	// 获取 PNG 编码器 CLSID
	UINT num = 0, imgSize = 0;
	GetImageEncodersSize(&num, &imgSize);
	std::vector<BYTE> buffer(imgSize);
	ImageCodecInfo* pImageCodecInfo = reinterpret_cast<ImageCodecInfo*>(buffer.data());
	GetImageEncoders(num, imgSize, pImageCodecInfo);
	for (UINT j = 0; j < num; ++j) {
		if (wcscmp(pImageCodecInfo[j].MimeType, L"image/png") == 0) {
			pngClsid = pImageCodecInfo[j].Clsid;
			break;
		}
	}

	if (bitmap.Save(pStream, &pngClsid, NULL) != Ok) {
		pStream->Release();
		return "";
	}

	// 获取内存数据
	HGLOBAL hGlobal = NULL;
	GetHGlobalFromStream(pStream, &hGlobal);
	SIZE_T size = GlobalSize(hGlobal);
	BYTE* pData = (BYTE*)GlobalLock(hGlobal);

	// base64 编码
	DWORD base64Len = 0;
	CryptBinaryToStringA(pData, static_cast<DWORD>(size), CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, NULL, &base64Len);
	std::string base64(base64Len, '\0');
	CryptBinaryToStringA(pData, static_cast<DWORD>(size), CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, &base64[0], &base64Len);

	// 去掉尾部可能存在的 null terminator（'\0'），但不要直接 resize
	if (!base64.empty() && base64.back() == '\0') {
		base64.pop_back();
	}

	GlobalUnlock(hGlobal);
	pStream->Release();
	return base64;
}

static void GetWindowInfo(HWND hwnd, std::wstring& execPath, std::wstring& name, std::string& iconB64) {
	execPath = GetExecutablePathFromHWND(hwnd);
	name = GetProductNameFromExe(execPath);
	HICON hIcon = GetIconFromExe(execPath);
	if (hIcon) {
		iconB64 = HIconToBase64(hIcon);
		DestroyIcon(hIcon);
	}
	//// 获取窗口标题
	//const int titleLength = 256;
	//wchar_t title[titleLength];
	//GetWindowText(hwnd, title, titleLength);

	//// 获取窗口的进程 ID
	//DWORD processId;
	//GetWindowThreadProcessId(hwnd, &processId);

	//// 打印信息
	//std::wcout << L"窗口标题: " << title << std::endl;
	//std::wcout << L"进程 ID: " << processId << std::endl;
}

#pragma endregion

namespace clipshare_clipboard_listener {

	// static
	ClipshareClipboardListenerPlugin* ClipshareClipboardListenerPlugin::instance = nullptr;
	void ClipshareClipboardListenerPlugin::RegisterWithRegistrar(
		flutter::PluginRegistrarWindows* registrar) {
		auto channel =
			std::make_unique < flutter::MethodChannel <
			flutter::EncodableValue >> (
				registrar->messenger(), std::string(ClipshareClipboardListenerPlugin::kChannelName),
				&flutter::StandardMethodCodec::GetInstance());
		auto plugin = std::make_unique<ClipshareClipboardListenerPlugin>(registrar);
		channel->SetMethodCallHandler(
			[plugin_pointer = plugin.get()](const auto& call, auto result) {
				plugin_pointer->HandleMethodCall(call, std::move(result));
			});
		plugin->channel_ = std::move(channel);
		registrar->AddPlugin(std::move(plugin));
	}

	ClipshareClipboardListenerPlugin::ClipshareClipboardListenerPlugin(flutter::PluginRegistrarWindows* registrar)
		:registrar_(registrar) {
		instance = this;
		previousWindowHwnd = NULL;
		listeningHiddenWindowHWND = NULL;
		InitGDIPlus();
	}

	ClipshareClipboardListenerPlugin::~ClipshareClipboardListenerPlugin() {
		ShutdownGDIPlus();
	}

	void ClipshareClipboardListenerPlugin::HandleMethodCall(
		const flutter::MethodCall <flutter::EncodableValue>& method_call,
		std::unique_ptr <flutter::MethodResult<flutter::EncodableValue>> result) {
		auto method_name = method_call.method_name();
		//开始监听
		if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kStartListening))) {
			try {
				StartListening();
				result->Success(flutter::EncodableValue(true));
			}
			catch (const std::exception& e) {
				std::cout << e.what() << std::endl;
				result->Success(flutter::EncodableValue(false));
			}
		}
		//检查是否已经在监听
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kCheckIsRunning))) {
			result->Success(flutter::EncodableValue(ClipshareClipboardListenerPlugin::running));
		}
		//复制内容
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kCopy))) {
			ignoreNextCopy = true;
			auto* arguments = std::get_if<flutter::EncodableMap>(method_call.arguments());
			auto type = std::get<std::string>(arguments->at(flutter::EncodableValue("type")));
			auto content = std::get<std::string>(arguments->at(flutter::EncodableValue("content")));
			bool res = CopyData(type, content);
			result->Success(flutter::EncodableValue(res));
		}
		//获取资源管理器中选择的文件
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kGetSelectedFiles))) {
			std::string fileList;
			bool succeed = GetSelectedFiles(fileList);
			// 构建要传递的参数
			flutter::EncodableMap map;
			map[flutter::EncodableValue("list")] = flutter::EncodableValue(fileList);
			map[flutter::EncodableValue("succeed")] = flutter::EncodableValue(succeed);
			result->Success(flutter::EncodableValue(map));
		}
		//停止监听
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kStopListening))) {
			DestroyWindow(listeningHiddenWindowHWND);
			running = false;
			result->Success();
		}
		//存储当前窗口句柄
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kStoreCurrentWindowHwnd))) {
			this->previousWindowHwnd = GetForegroundWindow();
			result->Success();
		}
		//设置临时文件夹
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kSetTempFileDir))) {
			auto* arguments = std::get_if<flutter::EncodableMap>(method_call.arguments());
			auto tempPath = std::get<std::string>(arguments->at(flutter::EncodableValue("tempFileDir")));
			this->tempFileDirPath = Utf16FromUtf8(tempPath).c_str();
			result->Success();
		}
		//粘贴到前一个窗口
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kPasteToPreviousWindow))) {
			if (this->previousWindowHwnd) {
				auto* arguments = std::get_if<flutter::EncodableMap>(method_call.arguments());
				auto keyDelayMs = arguments->at(flutter::EncodableValue("keyDelayMs")).LongValue();
				SetForegroundWindow(this->previousWindowHwnd);
				// 按下组合键
				keybd_event(VK_CONTROL, MapVirtualKey(VK_CONTROL, 0), 0, 0);
				Sleep(keyDelayMs);
				::SendMessage(this->previousWindowHwnd, WM_KEYDOWN, 0x56, 0);
				Sleep(keyDelayMs);
				::SendMessage(this->previousWindowHwnd, WM_KEYUP, 0x56, 0);
				keybd_event(VK_CONTROL, MapVirtualKey(VK_CONTROL, 0), KEYEVENTF_KEYUP, 0);
			}
			result->Success();
		}
		else {
			result->NotImplemented();
		}
	}

	void ClipshareClipboardListenerPlugin::StartListening() {
		if (running) {
			return;
		}
		std::thread([&]() {
			// 创建隐藏窗口并启动消息循环
			HINSTANCE hInstance = GetModuleHandle(nullptr);
			auto window = new ListeningHiddenWindow(hInstance, [&]() {
				this->OnClipboardChanged();
				});
			listeningHiddenWindowHWND = window->GetHWND();
			this->running = true;
			window->RunMessageLoop();
			}).detach();
	}

	void ClipshareClipboardListenerPlugin::OnClipboardChanged() {
		if (ignoreNextCopy) {
			ignoreNextCopy = false;
			return;
		}
		std::string type;
		// 处理剪贴板变化的代码可以放在这里
		std::wstring* text = GetClipboardDataCustom(type);
		if (text == nullptr)return;
		SendClipboardData(type, *text);
	}


	std::string ClipshareClipboardListenerPlugin::GetCurrentTimeWithMilliseconds()
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
	std::string ClipshareClipboardListenerPlugin::Utf8FromUtf16(const wchar_t* utf16_string) {
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
	std::wstring ClipshareClipboardListenerPlugin::Utf16FromUtf8(const std::string& string)
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
	static std::string WStringToUtf8(const std::wstring& wstr) {
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

	void ClipshareClipboardListenerPlugin::SendClipboardData(std::string& type, std::wstring& content)
	{
		std::string str = WStringToUtf8(content);
		// 构建要传递的参数
		flutter::EncodableMap args = flutter::EncodableMap();
		args[flutter::EncodableValue("content")] = flutter::EncodableValue(str.c_str());
		args[flutter::EncodableValue("type")] = flutter::EncodableValue(type.c_str());
		std::wstring execPath = L"";
		std::wstring name = L"";
		std::string iconB64 = "";
		GetWindowInfo(GetForegroundWindow(), execPath, name, iconB64);
		if (!execPath.empty()) {
			flutter::EncodableMap source = flutter::EncodableMap();
			auto pathStr = WStringToUtf8(execPath);
			auto nameStr = WStringToUtf8(name);
			source[flutter::EncodableValue("id")] = flutter::EncodableValue(pathStr.c_str());
			source[flutter::EncodableValue("name")] = flutter::EncodableValue(nameStr.c_str());
			source[flutter::EncodableValue("iconB64")] = flutter::EncodableValue(iconB64.c_str());
			args[flutter::EncodableValue("source")] = source;
		}
		auto method = std::string(ClipshareClipboardListenerPlugin::kOnClipboardChanged);
		// 调用Flutter方法
		channel_->InvokeMethod(method, std::make_unique<flutter::EncodableValue>(args));
	}

	//复制图片到剪贴板
	bool ClipshareClipboardListenerPlugin::BitmapToClipboard(HBITMAP hBM, HWND hWnd)
	{
		if (!::OpenClipboard(hWnd))
			return false;
		::EmptyClipboard();

		BITMAP bm;
		::GetObject(hBM, sizeof(bm), &bm);

		BITMAPINFOHEADER bi;
		::ZeroMemory(&bi, sizeof(BITMAPINFOHEADER));
		bi.biSize = sizeof(BITMAPINFOHEADER);
		bi.biWidth = bm.bmWidth;
		bi.biHeight = bm.bmHeight;
		bi.biPlanes = 1;
		bi.biBitCount = bm.bmBitsPixel;
		bi.biCompression = BI_RGB;
		if (bi.biBitCount <= 1)	// make sure bits per pixel is valid
			bi.biBitCount = 1;
		else if (bi.biBitCount <= 4)
			bi.biBitCount = 4;
		else if (bi.biBitCount <= 8)
			bi.biBitCount = 8;
		else // if greater than 8-bit, force to 24-bit
			bi.biBitCount = 24;

		// Get size of color table.
		SIZE_T dwColTableLen = (bi.biBitCount <= 8) ? (1 << bi.biBitCount) * sizeof(RGBQUAD) : 0;

		// Create a device context with palette
		HDC hDC = ::GetDC(nullptr);
		HPALETTE hPal = static_cast<HPALETTE>(::GetStockObject(DEFAULT_PALETTE));
		HPALETTE hOldPal = ::SelectPalette(hDC, hPal, FALSE);
		::RealizePalette(hDC);

		// Use GetDIBits to calculate the image size.
		::GetDIBits(hDC, hBM, 0, static_cast<UINT>(bi.biHeight), nullptr,
			reinterpret_cast<LPBITMAPINFO>(&bi), DIB_RGB_COLORS);
		// If the driver did not fill in the biSizeImage field, then compute it.
		// Each scan line of the image is aligned on a DWORD (32bit) boundary.
		if (0 == bi.biSizeImage)
			bi.biSizeImage = ((((bi.biWidth * bi.biBitCount) + 31) & ~31) / 8) * bi.biHeight;

		// Allocate memory
		HGLOBAL hDIB = ::GlobalAlloc(GMEM_MOVEABLE, sizeof(BITMAPINFOHEADER) + dwColTableLen + bi.biSizeImage);
		if (hDIB)
		{
			union tagHdr_u
			{
				LPVOID             p;
				LPBYTE             pByte;
				LPBITMAPINFOHEADER pHdr;
				LPBITMAPINFO       pInfo;
			} Hdr;

			Hdr.p = GlobalLock(hDIB);
			// Copy the header
			CopyMemory(Hdr.p, &bi, sizeof(BITMAPINFOHEADER));
			// Convert/copy the image bits and create the color table
			int nConv = ::GetDIBits(hDC, hBM, 0, static_cast<UINT>(bi.biHeight),
				Hdr.pByte + sizeof(BITMAPINFOHEADER) + dwColTableLen,
				Hdr.pInfo, DIB_RGB_COLORS);
			GlobalUnlock(hDIB);
			if (!nConv)
			{
				GlobalFree(hDIB);
				hDIB = nullptr;
			}
		}
		if (hDIB) {
			SetClipboardData(CF_DIB, hDIB);
		}
		CloseClipboard();
		SelectPalette(hDC, hOldPal, FALSE);
		ReleaseDC(nullptr, hDC);
		return nullptr != hDIB;
	}

	bool ClipshareClipboardListenerPlugin::CopyData(std::string type, std::string content, int retry)
	{
		// 尝试打开剪贴板
		bool isOpen = OpenClipboard(nullptr);
		if (!isOpen)
		{
			if (retry > 5)
			{
				return false;
			}
			Sleep(100);
			return CopyData(type, content, retry + 1);
		}

		// 清空剪贴板内容
		EmptyClipboard();
		std::wstring data = Utf16FromUtf8(content).c_str();
		HGLOBAL hClipboardData = nullptr;
		//文本类型
		if (type == "text")
		{
			// 分配全局内存，用于存放文本
			hClipboardData = GlobalAlloc(GMEM_MOVEABLE, (wcslen(data.c_str()) + 1) * sizeof(wchar_t));
			if (!hClipboardData)
			{
				CloseClipboard();
				std::cerr << "Failed to allocate memory for clipboard!" << std::endl;
				return false;
			}
			// 将文本复制到全局内存中
			auto const pchData = static_cast<wchar_t*>(GlobalLock(hClipboardData));
			if (!pchData)
			{
				CloseClipboard();
				std::cerr << "Failed to lock!" << std::endl;
				return false;
			}
			wcscpy_s(pchData, wcslen(data.c_str()) + 1, data.c_str());
			GlobalUnlock(hClipboardData);

			// 将全局内存放入剪贴板
			SetClipboardData(CF_UNICODETEXT, hClipboardData);
		}
		if (type == "image")
		{
			CImage image;
			if (FAILED(image.Load(std::wstring(content.begin(), content.end()).c_str()))) {
				return false;
			}
			HBITMAP hBitmap = image.Detach();
			if (hBitmap == nullptr) {
				std::cerr << "Failed to load image." << std::endl;
				return false;
			}
			BitmapToClipboard(hBitmap, nullptr);
			DeleteObject(hBitmap);
		}
		// 关闭剪贴板
		CloseClipboard();
		if (hClipboardData)
		{
			//释放GlobalAlloc分配的内存
			GlobalFree(hClipboardData);
		}
		return true;
	}

	std::wstring* ClipshareClipboardListenerPlugin::GetClipboardText()
	{
		// 尝试获取剪贴板中的数据
		const HANDLE hData = GetClipboardData(CF_UNICODETEXT);
		if (hData == nullptr)
		{
			CloseClipboard();
			return nullptr;
		}

		// 锁定内存并获取数据
		const wchar_t* pszText = static_cast<wchar_t*>(GlobalLock(hData));
		if (pszText == nullptr)
		{
			CloseClipboard();
			return nullptr;
		}

		// 释放内存和关闭剪贴板
		GlobalUnlock(hData);
		// 复制数据到字符串
		return new std::wstring(pszText);
	}

	bool ClipshareClipboardListenerPlugin::DIBToPNG(const HBITMAP hbtmip, const std::wstring* outputPath)
	{
		CImage image;
		// 从 HBITMAP 创建图像
		image.Attach(hbtmip);
		auto path = outputPath->c_str();
		std::string folderPath = fs::path(path).parent_path().string();
		if (!fs::exists(folderPath))
		{
			fs::create_directories(folderPath);
		}
		// 保存为 PNG 格式
		if (image.Save(path, Gdiplus::ImageFormatPNG) != S_OK)
		{
			return false;
		}
		return true;
	}

	std::wstring ClipshareClipboardListenerPlugin::GetExecutableDir() {
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

	std::wstring* ClipshareClipboardListenerPlugin::GetClipboardImg()
	{
		// 尝试获取剪贴板中的数据
		const HANDLE hData = GetClipboardData(CF_DIB);
		if (hData == nullptr)
		{
			CloseClipboard();
			return nullptr;
		}

		// 锁定内存并获取数据
		void* pData = GlobalLock(hData);
		if (pData == nullptr)
		{
			CloseClipboard();
			return nullptr;
		}
		// 将 CF_DIB 数据转换为 HBITMAP
		const HDC hdc = GetDC(nullptr);
		const auto info = static_cast<BITMAPINFO*>(pData);
		const auto header = static_cast<const BITMAPINFOHEADER*>(pData);
		//创建位图，位图数据从 header + 1 开始
		const HBITMAP hBitmap = CreateDIBitmap(hdc, header, CBM_INIT, header + 1, info, DIB_RGB_COLORS);
		if (hBitmap == nullptr)
			return nullptr;
		auto currentTime = GetCurrentTimeWithMilliseconds();
		// 使用正则表达式替换所有冒号和空格
		std::regex reg("[:.]");
		currentTime = std::regex_replace(currentTime, reg, "-");
		reg = std::regex(" +");
		currentTime = std::regex_replace(currentTime, reg, "_");
		auto timeStr = std::wstring(currentTime.begin(), currentTime.end());
		std::wstring tmpDir = this->tempFileDirPath;
		if (tmpDir.empty()) {
			auto execDir = GetExecutableDir();
			tmpDir = execDir + L"tmp/";
		}
		auto fileName = timeStr + L".png";
		// 拼接图片临时存储路径
		const auto path = new std::wstring(tmpDir + fileName);
		//位图转PNG存储
		auto res = DIBToPNG(hBitmap, path);
		ReleaseDC(nullptr, hdc);
		//获取存储的绝对路径
		auto absolutePath = fs::absolute(path->c_str()).string();
		return res ? new std::wstring(absolutePath.begin(), absolutePath.end()) : nullptr;
	}

	std::wstring* ClipshareClipboardListenerPlugin::GetClipboardDataCustom(std::string& type, int retry)
	{
		std::wstring* data = nullptr;
		// 尝试打开剪贴板
		bool isOpen = OpenClipboard(nullptr);
		if (!isOpen)
		{
			if (retry > 5)
				return nullptr;
			Sleep(100);
			return GetClipboardDataCustom(type, retry + 1);
		}

		// 尝试获取剪贴板中的数据，存在文本则直接返回
		if (IsClipboardFormatAvailable(CF_UNICODETEXT))
		{
			data = GetClipboardText();
			type = "Text";
			CloseClipboard();
			return data;
		}
		if (IsClipboardFormatAvailable(CF_DIB))
		{
			data = GetClipboardImg();
			type = "Image";
		}

		CloseClipboard();
		return data;
	}


	bool ClipshareClipboardListenerPlugin::GetSelectedFilesFromDesktop(std::vector<std::wstring>& paths) {
		return false;
	}

	bool ClipshareClipboardListenerPlugin::GetSelectedFilesFromExplorer(std::vector<std::wstring>& paths)
	{
		HRESULT hr;
		hr = CoInitialize(NULL);
		if (!SUCCEEDED(hr))
			return false;

		CComPtr<IShellWindows> shellWindows;
		hr = shellWindows.CoCreateInstance(CLSID_ShellWindows);
		if (!SUCCEEDED(hr))
			return false;

		long count;
		shellWindows->get_Count(&count);
		HWND foregroundWindow = GetForegroundWindow();
		//HWND handle = FindWindow(L"CabinetWClass", nullptr);
		for (long i = 0; i < count; ++i)
		{
			VARIANT index;
			index.vt = VT_I4;
			index.lVal = i;

			CComPtr<IDispatch> dispatch;
			shellWindows->Item(index, &dispatch);
			//参考：https://cloud.tencent.com/developer/ask/sof/114218481
			CComPtr<IServiceProvider> sp;
			hr = dispatch->QueryInterface(IID_IServiceProvider, (void**)&sp);
			if (!SUCCEEDED(hr))
				return false;

			CComPtr<IShellBrowser> browser;
			hr = sp->QueryService(SID_STopLevelBrowser, IID_IShellBrowser, (void**)&browser);
			if (!SUCCEEDED(hr))
				return false;

			//webBrowser用于获取shellwindow的hwnd
			CComPtr<IWebBrowserApp> webBrowser;
			hr = dispatch->QueryInterface(IID_IWebBrowserApp, (void**)&webBrowser);
			if (!SUCCEEDED(hr))
				return false;

			HWND hwnd;
			hr = webBrowser->get_HWND((SHANDLE_PTR*)&hwnd);
			if (!SUCCEEDED(hr) || foregroundWindow != hwnd) {
				//不在前台，跳过
				continue;
			}

			CComPtr<IShellView > sw;
			hr = browser->QueryActiveShellView(&sw);
			if (!SUCCEEDED(hr))
				return false;

			CComPtr<IDataObject > items;
			hr = sw->GetItemObject(SVGIO_SELECTION, IID_PPV_ARGS(&items));
			if (!SUCCEEDED(hr))
				return false;

			FORMATETC fmt = { CF_HDROP, NULL, DVASPECT_CONTENT, -1, TYMED_HGLOBAL };
			STGMEDIUM stg;
			hr = items->GetData(&fmt, &stg);
			if (!SUCCEEDED(hr))
				return false;

			HDROP hDrop = static_cast<HDROP>(GlobalLock(stg.hGlobal));
			if (hDrop != NULL) {
				UINT numPaths = DragQueryFileW(hDrop, 0xFFFFFFFF, NULL, 0);

				for (UINT j = 0; j < numPaths; ++j) {
					UINT bufferSize = DragQueryFileW(hDrop, j, NULL, 0) + 1;
					std::wstring path(bufferSize, L'\0');
					DragQueryFileW(hDrop, j, &path[0], bufferSize);
					path.resize(bufferSize - 1);
					paths.push_back(path);
				}
				GlobalUnlock(stg.hGlobal);
				ReleaseStgMedium(&stg);
			}
		}

		//// Clean up and exit
		CoUninitialize();
		return true;
	}

	bool ClipshareClipboardListenerPlugin::GetSelectedFiles(std::string& fileList) {
		std::vector<std::wstring> paths;
		HWND foregroundHwnd = GetForegroundWindow();
		bool isdeskTop = false;
		// 获取前台窗口的类名
		wchar_t className[256];
		GetClassNameW(foregroundHwnd, className, sizeof(className) / sizeof(className[0]));
		// 判断窗口类名是否为桌面窗口的类名
		if (wcscmp(className, L"Progman") == 0 || wcscmp(className, L"WorkerW") == 0) {
			isdeskTop = true;
		}
		bool succeed = isdeskTop ? GetSelectedFilesFromDesktop(paths) : GetSelectedFilesFromExplorer(paths);
		for (const auto& path : paths) {
			fileList += Utf8FromUtf16(path.c_str()) + ";";
		}
		return succeed;
	}
}  // namespace clipshare_clipboard_listener
