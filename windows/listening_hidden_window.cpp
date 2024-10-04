
#include <windows.h>
#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>

#pragma warning(disable : 4334)  // 禁用警告：32 位移位的结果被隐式转换为 64 位(是否希望进行 64 位移位?) 

namespace {
	class ListeningHiddenWindow {
	public:
		ListeningHiddenWindow(HINSTANCE hInstance,
			std::function<void()> onClipboardChnaged);

		virtual ~ListeningHiddenWindow();

		void RunMessageLoop();

	private:
		HWND hwnd_;
		HWND hWndNextViewer_;
		std::function<void()> onClipboardChnaged;

		LRESULT MessageHandler(HWND hwnd, UINT const uMsg, WPARAM const wParam, LPARAM const lParam)noexcept;

		//static
		static LRESULT CALLBACK ListeningHiddenWindowProc(HWND hwnd, UINT uMsg, WPARAM wParam, LPARAM lParam);
	};
	ListeningHiddenWindow::ListeningHiddenWindow(
		HINSTANCE hInstance,
		std::function<void()> onClipboardChnaged) {
		// 定义窗口类
		WNDCLASS wc = { 0 };
		wc.lpfnWndProc = ListeningHiddenWindow::ListeningHiddenWindowProc;
		wc.hInstance = hInstance;
		wc.lpszClassName = L"ListeningHiddenWindowClass";  // 窗口类名

		// 注册窗口类
		RegisterClass(&wc);

		// 创建窗口，不显示
		hwnd_ = CreateWindowEx(
			0,                                  // 扩展样式
			L"ListeningHiddenWindowClass",               // 窗口类名
			NULL,                               // 窗口标题
			0,                                  // 窗口样式（无）
			0, 0,                               // 窗口位置（不需要显示）
			0, 0,                               // 窗口大小（无）
			NULL,                               // 父窗口句柄
			NULL,                               // 菜单
			hInstance,                          // 实例句柄
			this                                // 额外参数
		);
		hWndNextViewer_ = SetClipboardViewer(hwnd_);
		this->onClipboardChnaged = onClipboardChnaged;
	}

	ListeningHiddenWindow::~ListeningHiddenWindow() {
		// 移除剪贴板查看器
		ChangeClipboardChain(hwnd_, hWndNextViewer_);
	}

	// 开始消息循环的函数
	void ListeningHiddenWindow::RunMessageLoop() {
		MSG msg;
		while (GetMessage(&msg, NULL, 0, 0)) {
			TranslateMessage(&msg);
			DispatchMessage(&msg);
			//MessageHandler(msg.hwnd, msg.message, msg.wParam, msg.lParam);
		}
	}

	// 窗口过程函数
	LRESULT ListeningHiddenWindow::ListeningHiddenWindowProc(HWND hwnd, UINT uMsg, WPARAM wParam,
		LPARAM lParam) {
		ListeningHiddenWindow* pThis = nullptr;

		if (uMsg == WM_NCCREATE) {
			// 在创建时，将 `this` 指针与窗口句柄绑定
			CREATESTRUCT* pCreate = reinterpret_cast<CREATESTRUCT*>(lParam);
			pThis = reinterpret_cast<ListeningHiddenWindow*>(pCreate->lpCreateParams);
			SetWindowLongPtr(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(pCreate->lpCreateParams));
		}
		else {
			// 从窗口的用户数据中获取 `this` 指针
			pThis = reinterpret_cast<ListeningHiddenWindow*>(GetWindowLongPtr(hwnd, GWLP_USERDATA));
		}

		if (pThis) {
			return pThis->MessageHandler(hwnd, uMsg, wParam, lParam);
		}

		return DefWindowProc(hwnd, uMsg, wParam, lParam);
	}

	LRESULT ListeningHiddenWindow::MessageHandler(HWND hwnd, UINT const uMsg, WPARAM const wParam,
		LPARAM const lParam)

		noexcept {
		// hwnd: 表示窗口的句柄（handle），是消息发送或接收的窗口的标识符。在函数内，您可以使用这个句柄与窗口进行交互。
		//
		// uMsg : 表示窗口消息的标识符。它指定了要处理的消息类型。例如，WM_DRAWCLIPBOARD 表示剪贴板内容发生变化的消息。
		// WM_DRAWCLIPBOARD 中，wParam 和 lParam 分别表示剪贴板触发事件的原因和相关的句柄。
		//
		// 	wParam : 通常用于传递一些消息相关的信息，如通知消息的原因、按下的键值等。
		//
		// 	lParam : 通常用于传递指针或句柄，指向消息相关的数据结构或对象。
		switch (uMsg) {
			//776
		case WM_DRAWCLIPBOARD: {
			if (!onClipboardChnaged) {
				break;
			}
			onClipboardChnaged();
			// 转发消息给下一个剪贴板查看器
			SendMessage(hWndNextViewer_, WM_DRAWCLIPBOARD, wParam, lParam);
			break;
		}
							 //781
		case WM_CHANGECBCHAIN:
		{
			// 处理剪贴板链的变化
			if ((HWND)wParam == hWndNextViewer_)
				hWndNextViewer_ = (HWND)lParam;
			else if (hWndNextViewer_ != nullptr)
				SendMessage(hWndNextViewer_, uMsg, wParam, lParam);
			break;
		}
		}

		return DefWindowProc(hwnd, uMsg, wParam, lParam);
	}
}