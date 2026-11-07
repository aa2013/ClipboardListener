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
#include "dib_utils.h"
#include <vector>
#include <shlobj.h>
#include <atlbase.h> // CComPtr
#include <gdiplus.h>
#include <wincrypt.h>
#include <comdef.h>
#include <winver.h>
#include <shellapi.h>
//#include <windows.h>
#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "version.lib")
#pragma comment(lib, "crypt32.lib")
#pragma warning(disable : 4334)  // 禁用警告：32 位移位的结果被隐式转换为 64 位(是否希望进行 64 位移位?) 
#pragma warning(disable : 4996)  // 禁用废弃警告
#pragma warning(disable : 4244)  // 禁用警告：参数从 UINT 转为 BYTE 可能丢失数据
//#pragma warning(disable : 6387)  // 禁用警告 
namespace fs = std::filesystem;
typedef unsigned char uchar;


#pragma region GetWindowInfo

static ULONG_PTR gdiplusToken;

static void InitGDIPlus() {
	Gdiplus::GdiplusStartupInput gdiplusStartupInput;
	Gdiplus::GdiplusStartup(&gdiplusToken, &gdiplusStartupInput, nullptr);
}

static void ShutdownGDIPlus() {
	Gdiplus::GdiplusShutdown(gdiplusToken);
}

namespace {
	/// 粘贴结果枚举。
	enum class PasteResult {
		/// Paste was injected successfully.
		kSuccess,

		/// No eligible target window was found to receive the paste action.
		kNoTargetWindow,

		/// The target process could not be opened for inspection.
		kOpenTargetProcessFailed,

		/// The target process was found, but its integrity level could not be inspected.
		kInspectTargetFailed,

		/// The current process integrity level could not be inspected.
		kInspectSelfFailed,

		/// The target window is running at a higher integrity level than this app.
		kTargetIntegrityHigher,
	};

	/// 完整性级别查询状态。
	/// 需要区分打开 token 失败、查询失败和 SID 无效，避免把所有场景都误判成“高权限窗口”。
	enum class IntegrityLevelQueryStatus {
		kOk,
		kAccessDenied,
		kOpenTokenFailed,
		kQueryTokenFailed,
		kInvalidSid,
	};

	/// 封装完整性级别查询结果，便于将失败原因和 Win32 错误码上报给 Dart 层。
	struct IntegrityLevelQueryResult {
		IntegrityLevelQueryStatus status = IntegrityLevelQueryStatus::kQueryTokenFailed;
		int level = -1;
		DWORD lastError = ERROR_SUCCESS;
	};

	/// 枚举转换
	static flutter::EncodableValue BuildPasteResultValue(PasteResult result) {
		switch (result) {
		case PasteResult::kSuccess:
			return flutter::EncodableValue("success");
		case PasteResult::kNoTargetWindow:
			return flutter::EncodableValue("noTargetWindow");
		case PasteResult::kOpenTargetProcessFailed:
			return flutter::EncodableValue("openTargetProcessFailed");
		case PasteResult::kInspectTargetFailed:
			return flutter::EncodableValue("inspectTargetFailed");
		case PasteResult::kInspectSelfFailed:
			return flutter::EncodableValue("inspectSelfFailed");
		case PasteResult::kTargetIntegrityHigher:
			return flutter::EncodableValue("targetIntegrityHigher");
		default:
			return flutter::EncodableValue("unknown");
		}
	}
}

/// 读取窗口所属进程的可执行文件路径。
/// QueryFullProcessImageNameW 只需要 PROCESS_QUERY_LIMITED_INFORMATION，避免额外申请 VM_READ 导致管理员进程更容易拒绝访问。
static std::wstring GetExecutablePathFromHWND(HWND hwnd) {
	DWORD pid = 0;
	GetWindowThreadProcessId(hwnd, &pid);

	HANDLE hProcess = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
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
		currentWindowHwnd = NULL;
		previousWindowHwnd = NULL;
		foregroundWindowHook = NULL;
		m_excludeFormat = RegisterClipboardFormatW(L"ExcludeClipboardContentFromMonitorProcessing");
		listeningHiddenWindowHWND = NULL;
		InitGDIPlus();
	}

	ClipshareClipboardListenerPlugin::~ClipshareClipboardListenerPlugin() {
		StopForegroundWindowListener();
		ShutdownGDIPlus();
	}

	/// 获取进程完整性级别：Low=1, Medium=3, High=4, System=5。
	/// UIPI 规定 SendInput 只能注入到完整性级别相同或更低的目标进程，
	/// 因此需要在注入前区分“目标更高权限”和“当前无法判断目标权限”这两类场景。
	static IntegrityLevelQueryResult QueryIntegrityLevel(HANDLE process) {
		IntegrityLevelQueryResult queryResult;
		HANDLE token = NULL;
		if (!OpenProcessToken(process, TOKEN_QUERY, &token)) {
			queryResult.lastError = GetLastError();
			queryResult.status = queryResult.lastError == ERROR_ACCESS_DENIED
				? IntegrityLevelQueryStatus::kAccessDenied
				: IntegrityLevelQueryStatus::kOpenTokenFailed;
			return queryResult;
		}
		DWORD size = 0;
		SetLastError(ERROR_SUCCESS);
		GetTokenInformation(token, TokenIntegrityLevel, NULL, 0, &size);
		const DWORD probeError = GetLastError();
		if (size == 0 || probeError != ERROR_INSUFFICIENT_BUFFER) {
			CloseHandle(token);
			queryResult.status = IntegrityLevelQueryStatus::kQueryTokenFailed;
			queryResult.lastError = probeError;
			return queryResult;
		}
		std::vector<BYTE> buffer(size);
		auto* label = reinterpret_cast<TOKEN_MANDATORY_LABEL*>(buffer.data());
		if (!GetTokenInformation(token, TokenIntegrityLevel, label, size, &size)) {
			queryResult.lastError = GetLastError();
			CloseHandle(token);
			queryResult.status = IntegrityLevelQueryStatus::kQueryTokenFailed;
			return queryResult;
		}
		CloseHandle(token);
		if (label->Label.Sid == NULL) {
			queryResult.status = IntegrityLevelQueryStatus::kInvalidSid;
			return queryResult;
		}
		const DWORD subAuthCount = *GetSidSubAuthorityCount(label->Label.Sid);
		if (subAuthCount == 0) {
			queryResult.status = IntegrityLevelQueryStatus::kInvalidSid;
			return queryResult;
		}
		// 完整性级别 SID 最后一个子权威值：4096=Low, 12288=Medium, 16384=High, 20480=System
		const DWORD level = *GetSidSubAuthority(label->Label.Sid, subAuthCount - 1);
		queryResult.status = IntegrityLevelQueryStatus::kOk;
		queryResult.level = static_cast<int>(level / 4096);
		return queryResult;
	}

	void ClipshareClipboardListenerPlugin::HandleMethodCall(
		const flutter::MethodCall <flutter::EncodableValue>& method_call,
		std::unique_ptr <flutter::MethodResult<flutter::EncodableValue>> result) {
		auto method_name = method_call.method_name();
		auto* arguments = std::get_if<flutter::EncodableMap>(method_call.arguments());
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
			StopForegroundWindowListener();
			running = false;
			result->Success();
		}
		//设置临时文件夹
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kSetTempFileDir))) {
			auto tempPath = std::get<std::string>(arguments->at(flutter::EncodableValue("tempFileDir")));
			this->tempFileDirPath = Utf16FromUtf8(tempPath).c_str();
			result->Success();
		}
		//粘贴到前一个窗口
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kPasteToPreviousWindow))) {
			// 优先使用当前前台窗口；若前台窗口是插件自身弹窗，则回退到之前记录的外部窗口。
			HWND targetWindow = GetForegroundWindow();
			DWORD targetProcessId = 0;
			if (targetWindow) {
				GetWindowThreadProcessId(targetWindow, &targetProcessId);
			}
			if (!targetWindow || !IsWindow(targetWindow) || targetProcessId == GetCurrentProcessId()) {
				targetWindow = this->currentWindowHwnd;
			}
			// currentWindowHwnd 在本进程窗口变为前台时会被置空（见 UpdateForegroundWindow），
			// 这里回退到更早记录的外部窗口，保证从弹窗/辅助窗口触发的粘贴仍能找到目标。
			if (!targetWindow || !IsWindow(targetWindow)) {
				targetWindow = this->previousWindowHwnd;
			}
			if (!targetWindow || !IsWindow(targetWindow)) {
				result->Success(BuildPasteResultValue(PasteResult::kNoTargetWindow));
				return;
			}

			// UIPI 会阻止普通权限进程向更高完整性级别窗口注入输入。
			// 权限探测失败同样作为业务结果返回，交由 Dart 层决定如何提示或降级处理。
			DWORD targetPid = 0;
			GetWindowThreadProcessId(targetWindow, &targetPid);
			HANDLE targetProcess = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, targetPid);
			if (!targetProcess) {
				result->Success(BuildPasteResultValue(PasteResult::kOpenTargetProcessFailed));
				return;
			}

			const IntegrityLevelQueryResult targetIntegrity = QueryIntegrityLevel(targetProcess);
			CloseHandle(targetProcess);
			if (targetIntegrity.status != IntegrityLevelQueryStatus::kOk) {
				result->Success(BuildPasteResultValue(PasteResult::kInspectTargetFailed));
				return;
			}

			const IntegrityLevelQueryResult selfIntegrity = QueryIntegrityLevel(GetCurrentProcess());
			if (selfIntegrity.status != IntegrityLevelQueryStatus::kOk) {
				result->Success(BuildPasteResultValue(PasteResult::kInspectSelfFailed));
				return;
			}

			if (targetIntegrity.level > selfIntegrity.level) {
				result->Success(BuildPasteResultValue(PasteResult::kTargetIntegrityHigher));
				return;
			}

			SetForegroundWindow(targetWindow);
			// 等待焦点切换完成，避免组合键仍然落到旧窗口。
			Sleep(30);
			// 注入一次 Ctrl+V 组合键：Ctrl down -> V down -> V up -> Ctrl up。
			INPUT inputs[4] = {};
			inputs[0].type = INPUT_KEYBOARD;
			inputs[0].ki.wVk = VK_CONTROL;
			inputs[1].type = INPUT_KEYBOARD;
			inputs[1].ki.wVk = 0x56;
			inputs[2].type = INPUT_KEYBOARD;
			inputs[2].ki.wVk = 0x56;
			inputs[2].ki.dwFlags = KEYEVENTF_KEYUP;
			inputs[3].type = INPUT_KEYBOARD;
			inputs[3].ki.wVk = VK_CONTROL;
			inputs[3].ki.dwFlags = KEYEVENTF_KEYUP;
			::SendInput(ARRAYSIZE(inputs), inputs, sizeof(INPUT));
			result->Success(BuildPasteResultValue(PasteResult::kSuccess));
		}
		//启用/关闭格式排除
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kEnableExcludeFormat))) {
			auto enable = std::get<bool>(arguments->at(flutter::EncodableValue("enable")));
			this->enableExcludeFormat = enable;
			result->Success();
		}
		//返回是否启用格式排除
		else if (0 == method_name.compare(std::string(ClipshareClipboardListenerPlugin::kIsEnableExcludeFormat))) {
			result->Success(flutter::EncodableValue(this->enableExcludeFormat));
		}
		else {
			result->NotImplemented();
		}
	}

	void ClipshareClipboardListenerPlugin::StartListening() {
		if (running) {
			return;
		}
		UpdateForegroundWindow(GetForegroundWindow());
		foregroundWindowHook = SetWinEventHook(EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_FOREGROUND, nullptr, ForegroundWindowChanged, 0, 0, WINEVENT_OUTOFCONTEXT);
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

	void ClipshareClipboardListenerPlugin::StopForegroundWindowListener() {
		if (foregroundWindowHook) {
			UnhookWinEvent(foregroundWindowHook);
			foregroundWindowHook = NULL;
		}
	}

	void ClipshareClipboardListenerPlugin::UpdateForegroundWindow(HWND hwnd) {
		if (!hwnd || !IsWindow(hwnd) || !IsWindowVisible(hwnd) || hwnd == listeningHiddenWindowHWND) {
			return;
		}
		DWORD processId = 0;
		GetWindowThreadProcessId(hwnd, &processId);
		if (processId == GetCurrentProcessId()) {
			if (currentWindowHwnd && IsWindow(currentWindowHwnd)) {
				previousWindowHwnd = currentWindowHwnd;
			}
			currentWindowHwnd = NULL;
			return;
		}
		if (hwnd == currentWindowHwnd) {
			return;
		}
		if (currentWindowHwnd && IsWindow(currentWindowHwnd)) {
			previousWindowHwnd = currentWindowHwnd;
		}
		currentWindowHwnd = hwnd;
	}

	void CALLBACK ClipshareClipboardListenerPlugin::ForegroundWindowChanged(HWINEVENTHOOK hook, DWORD event, HWND hwnd, LONG objectId, LONG childId, DWORD eventThread, DWORD eventTime) {
		if (event != EVENT_SYSTEM_FOREGROUND || objectId != OBJID_WINDOW || childId != CHILDID_SELF || instance == nullptr) {
			return;
		}
		instance->UpdateForegroundWindow(hwnd);
		// Notify Dart when the foreground window changes so the app can react
		// (e.g. auto-close the history popup when the user focuses another app).
		// isSelf indicates whether the new foreground window belongs to this process.
		if (instance->channel_ && instance->running) {
			DWORD processId = 0;
			if (hwnd) {
				GetWindowThreadProcessId(hwnd, &processId);
			}
			flutter::EncodableMap args = {
				{flutter::EncodableValue("hwnd"), flutter::EncodableValue(reinterpret_cast<int64_t>(hwnd))},
				{flutter::EncodableValue("isSelf"), flutter::EncodableValue(processId == GetCurrentProcessId())},
			};
			instance->channel_->InvokeMethod(
				std::string(ClipshareClipboardListenerPlugin::kOnForegroundChanged),
				std::make_unique<flutter::EncodableValue>(args));
		}
	}

	void ClipshareClipboardListenerPlugin::OnClipboardChanged() {
		if (ignoreNextCopy) {
			ignoreNextCopy = false;
			return;
		}
		std::string type;
		// 处理剪贴板变化的代码可以放在这里
		std::wstring* text = GetClipboardDataCustom(type);
		if (text == nullptr){
		    if(type == "NotSupported"){
		        return;
		    }
		    //todo
//            MessageBoxW(
//                NULL,
//                L"剪贴板内容获取失败！已重试5次，考虑检查是否有冲突程序占用了剪贴板锁未释放",
//                L"提示",
//                MB_OK | MB_ICONWARNING
//            );
		    return;
		}
		SendClipboardData(type, *text);
		delete text;
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
		// SetClipboardData 的结果：成功后内存归系统所有，失败时仍归本进程需要自行释放
		HANDLE hDataSetResult = nullptr;
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
				GlobalFree(hClipboardData);
				std::cerr << "Failed to lock!" << std::endl;
				return false;
			}
			wcscpy_s(pchData, wcslen(data.c_str()) + 1, data.c_str());
			GlobalUnlock(hClipboardData);

			// 将全局内存放入剪贴板
			hDataSetResult = SetClipboardData(CF_UNICODETEXT, hClipboardData);
		}
		if (type == "image")
		{
			CImage image;
			if (FAILED(image.Load(std::wstring(content.begin(), content.end()).c_str()))) {
				// 剪贴板已打开并清空，必须关闭后才能返回，否则系统级剪贴板锁会被本线程永久持有
				CloseClipboard();
				return false;
			}
			HBITMAP hBitmap = image.Detach();
			if (hBitmap == nullptr) {
				std::cerr << "Failed to load image." << std::endl;
				CloseClipboard();
				return false;
			}
			BitmapToClipboard(hBitmap, nullptr);
			DeleteObject(hBitmap);
		}
		// 关闭剪贴板
		CloseClipboard();
		if (hClipboardData && hDataSetResult == nullptr)
		{
			//SetClipboardData 失败时内存未被系统接管，需要自行释放；成功后释放会破坏剪贴板内容
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
			// 剪贴板的关闭由 GetClipboardDataCustom 统一负责
			return nullptr;
		}

		// 锁定内存并获取数据
		const wchar_t* pszText = static_cast<wchar_t*>(GlobalLock(hData));
		if (pszText == nullptr)
		{
			return nullptr;
		}

		// 释放内存；剪贴板的关闭由 GetClipboardDataCustom 统一负责
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
		// 使用 error_code 重载，避免创建目录失败抛异常导致上层跳过 CloseClipboard
		std::error_code ec;
		if (!fs::exists(folderPath, ec) && !fs::create_directories(folderPath, ec))
		{
			return false;
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
			// 剪贴板的关闭由 GetClipboardDataCustom 统一负责
			return nullptr;
		}

		// 锁定内存并获取数据
		void* pData = GlobalLock(hData);
		if (pData == nullptr)
		{
			return nullptr;
		}
		// 将 CF_DIB 数据转换为 HBITMAP
		const auto info = static_cast<BITMAPINFO*>(pData);
		const auto header = static_cast<const BITMAPINFOHEADER*>(pData);
		// 像素数据位于头部、颜色掩码和调色板之后，不能直接用 header + 1
		const SIZE_T pixelOffset = GetDibPixelOffset(header);
		if (pixelOffset >= GlobalSize(hData))
		{
			GlobalUnlock(hData);
			return nullptr;
		}
		const HDC hdc = GetDC(nullptr);
		const HBITMAP hBitmap = CreateDIBitmap(hdc, header, CBM_INIT,
			static_cast<const BYTE*>(pData) + pixelOffset, info, DIB_RGB_COLORS);
		// DIB 数据已消费完毕，无论成败都要解锁；失败时还需释放屏幕 DC
		GlobalUnlock(hData);
		if (hBitmap == nullptr)
		{
			ReleaseDC(nullptr, hdc);
			return nullptr;
		}
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
		const std::wstring path = tmpDir + fileName;
		//位图转PNG存储
		auto res = DIBToPNG(hBitmap, &path);
		ReleaseDC(nullptr, hdc);
		if (!res) {
			return nullptr;
		}
		//获取存储的绝对路径，使用 error_code 重载避免异常逃逸
		std::error_code ec;
		auto absolutePath = fs::absolute(path.c_str(), ec).string();
		if (ec) {
			return nullptr;
		}
		return new std::wstring(absolutePath.begin(), absolutePath.end());
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
		type = "NotSupported";
		UINT format = 0;
		bool hasExcludeFormat = false;
		if (this->enableExcludeFormat) {
			while ((format = EnumClipboardFormats(format)) != 0) {
				if (format == m_excludeFormat) {
					hasExcludeFormat = true;
					break;
				}
			}
			if (hasExcludeFormat) {
				CloseClipboard();
				return nullptr;
			}
		}

		// 尝试获取剪贴板中的数据，存在文本则直接返回
		if (IsClipboardFormatAvailable(CF_UNICODETEXT))
		{
			data = GetClipboardText();
			type = "Text";
		}
		else if (IsClipboardFormatAvailable(CF_DIB))
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
