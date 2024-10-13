#ifndef FLUTTER_PLUGIN_CLIPBOARD_LISTENER_PLUGIN_H_
#define FLUTTER_PLUGIN_CLIPBOARD_LISTENER_PLUGIN_H_

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>

#include <memory>

namespace clipboard_listener {

	class ClipboardListenerPlugin : public flutter::Plugin {
	public:
		static void RegisterWithRegistrar(flutter::PluginRegistrarWindows* registrar);
		static constexpr std::string_view kChannelName = "top.coclyun.clipshare/clipboard_listener";
		static constexpr std::string_view kOnClipboardChanged = "onClipboardChanged";
		static constexpr std::string_view kStartListening = "startListening"; 
		static constexpr std::string_view kCheckIsRunning = "checkIsRunning";
		static constexpr std::string_view kCopy = "copy";
		static constexpr std::string_view kGetSelectedFiles = "getSelectedFiles";
		static constexpr std::string_view kStopListening = "stopListening";
		ClipboardListenerPlugin(flutter::PluginRegistrarWindows* registrar);

		virtual ~ClipboardListenerPlugin();

		// Disallow copy and assign.
		ClipboardListenerPlugin(const ClipboardListenerPlugin&) = delete;
		ClipboardListenerPlugin& operator=(const ClipboardListenerPlugin&) = delete;

		// Called when a method is called on this plugin's channel from Dart.
		void HandleMethodCall(
			const flutter::MethodCall<flutter::EncodableValue>& method_call,
			std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
		std::wstring Utf16FromUtf8(const std::string& string);
		std::string Utf8FromUtf16(const wchar_t* utf16_string);
		std::string GetCurrentTimeWithMilliseconds();
	private:
		static ClipboardListenerPlugin* instance;
		std::unique_ptr<flutter::MethodChannel<flutter::EncodableValue>> channel_;
		flutter::PluginRegistrarWindows* registrar_;
		bool running = false;
		bool ignoreNextCopy = false;
		HWND listeningHiddenWindowHWND;
		void StartListening();
		void OnClipboardChanged();
		std::wstring* GetClipboardDataCustom(std::string& type, int retry = 0);
		std::wstring* GetClipboardImg();
		std::wstring* GetClipboardText();
		std::wstring GetExecutableDir();
		bool DIBToPNG(const HBITMAP hbtmip, const std::wstring* outputPath);
		bool CopyData(std::string type, std::string content, int retry = 0);
		bool BitmapToClipboard(HBITMAP hBM, HWND hWnd);
		void SendClipboardData(std::string& type, std::wstring& content);
		bool GetSelectedFilesFromDesktop(std::vector<std::wstring>& paths);
		bool GetSelectedFilesFromExplorer(std::vector<std::wstring>& paths);
		bool GetSelectedFiles(std::string& fileList);
	};

}  // namespace clipboard_listener

#endif  // FLUTTER_PLUGIN_CLIPBOARD_LISTENER_PLUGIN_H_
