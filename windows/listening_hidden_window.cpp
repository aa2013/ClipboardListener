#include <windows.h>
#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>

#pragma warning(disable : 4334)  // 禁用警告：32 位移位的结果被隐式转换为 64 位(是否希望进行 64 位移位?) 

namespace {
	class ListeningHiddenWindow {
	public:
		ListeningHiddenWindow(HINSTANCE hInstance,
			std::function<void()> onClipboardChanged);

		virtual ~ListeningHiddenWindow();

		void RunMessageLoop();

		HWND GetHWND() {
			return hwnd_;
		}
	private:
		HWND hwnd_;
		HWND hWndNextViewer_;
		std::function<void()> onClipboardChanged;

		LRESULT MessageHandler(HWND hwnd, UINT const uMsg, WPARAM const wParam, LPARAM const lParam)noexcept;

		//static
		static LRESULT CALLBACK ListeningHiddenWindowProc(HWND hwnd, UINT uMsg, WPARAM wParam, LPARAM lParam);
	};
	ListeningHiddenWindow::ListeningHiddenWindow(
        HINSTANCE hInstance,
        std::function<void()> onClipboardChanged) {

        WNDCLASS wc = { 0 };
        wc.lpfnWndProc = ListeningHiddenWindow::ListeningHiddenWindowProc;
        wc.hInstance = hInstance;
        wc.lpszClassName = L"ListeningHiddenWindowClass";

        RegisterClass(&wc);

        hwnd_ = CreateWindowEx(
            0,
            L"ListeningHiddenWindowClass",
            NULL,
            0,
            0, 0,
            0, 0,
            NULL,
            NULL,
            hInstance,
            this
        );

        this->onClipboardChanged = onClipboardChanged;

        AddClipboardFormatListener(hwnd_);
    }

	ListeningHiddenWindow::~ListeningHiddenWindow() {
        RemoveClipboardFormatListener(hwnd_);
        DestroyWindow(hwnd_);
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

	LRESULT ListeningHiddenWindow::MessageHandler(
        HWND hwnd,
        UINT const uMsg,
        WPARAM const wParam,
        LPARAM const lParam) noexcept {
    
        switch (uMsg) {
    
        case WM_CLIPBOARDUPDATE:
        {
            SetTimer(hwnd_, 1, 50, NULL);
            break;
        }
    
        case WM_TIMER:
        {
            if (wParam == 1) {
                KillTimer(hwnd_, 1);
    
                if (onClipboardChanged) {
                    onClipboardChanged();
                }
            }
            break;
        }
        }
    
        return DefWindowProc(hwnd, uMsg, wParam, lParam);
    }
}