
#include <windows.h>
#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>

#pragma warning(disable : 4334)  // ���þ��棺32 λ��λ�Ľ������ʽת��Ϊ 64 λ(�Ƿ�ϣ������ 64 λ��λ?) 

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
		// ���崰����
		WNDCLASS wc = { 0 };
		wc.lpfnWndProc = ListeningHiddenWindow::ListeningHiddenWindowProc;
		wc.hInstance = hInstance;
		wc.lpszClassName = L"ListeningHiddenWindowClass";  // ��������

		// ע�ᴰ����
		RegisterClass(&wc);

		// �������ڣ�����ʾ
		hwnd_ = CreateWindowEx(
			0,                                  // ��չ��ʽ
			L"ListeningHiddenWindowClass",               // ��������
			NULL,                               // ���ڱ���
			0,                                  // ������ʽ���ޣ�
			0, 0,                               // ����λ�ã�����Ҫ��ʾ��
			0, 0,                               // ���ڴ�С���ޣ�
			NULL,                               // �����ھ��
			NULL,                               // �˵�
			hInstance,                          // ʵ�����
			this                                // �������
		);
		hWndNextViewer_ = SetClipboardViewer(hwnd_);
		this->onClipboardChnaged = onClipboardChnaged;
	}

	ListeningHiddenWindow::~ListeningHiddenWindow() {
		// �Ƴ�������鿴��
		ChangeClipboardChain(hwnd_, hWndNextViewer_);
	}

	// ��ʼ��Ϣѭ���ĺ���
	void ListeningHiddenWindow::RunMessageLoop() {
		MSG msg;
		while (GetMessage(&msg, NULL, 0, 0)) {
			TranslateMessage(&msg);
			DispatchMessage(&msg);
			//MessageHandler(msg.hwnd, msg.message, msg.wParam, msg.lParam);
		}
	}

	// ���ڹ��̺���
	LRESULT ListeningHiddenWindow::ListeningHiddenWindowProc(HWND hwnd, UINT uMsg, WPARAM wParam,
		LPARAM lParam) {
		ListeningHiddenWindow* pThis = nullptr;

		if (uMsg == WM_NCCREATE) {
			// �ڴ���ʱ���� `this` ָ���봰�ھ����
			CREATESTRUCT* pCreate = reinterpret_cast<CREATESTRUCT*>(lParam);
			pThis = reinterpret_cast<ListeningHiddenWindow*>(pCreate->lpCreateParams);
			SetWindowLongPtr(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(pCreate->lpCreateParams));
		}
		else {
			// �Ӵ��ڵ��û������л�ȡ `this` ָ��
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
		// hwnd: ��ʾ���ڵľ����handle��������Ϣ���ͻ���յĴ��ڵı�ʶ�����ں����ڣ�������ʹ���������봰�ڽ��н�����
		//
		// uMsg : ��ʾ������Ϣ�ı�ʶ������ָ����Ҫ�������Ϣ���͡����磬WM_DRAWCLIPBOARD ��ʾ���������ݷ����仯����Ϣ��
		// WM_DRAWCLIPBOARD �У�wParam �� lParam �ֱ��ʾ�����崥���¼���ԭ�����صľ����
		//
		// 	wParam : ͨ�����ڴ���һЩ��Ϣ��ص���Ϣ����֪ͨ��Ϣ��ԭ�򡢰��µļ�ֵ�ȡ�
		//
		// 	lParam : ͨ�����ڴ���ָ�������ָ����Ϣ��ص����ݽṹ�����
		switch (uMsg) {
			//776
		case WM_DRAWCLIPBOARD: {
			if (!onClipboardChnaged) {
				break;
			}
			onClipboardChnaged();
			// ת����Ϣ����һ��������鿴��
			SendMessage(hWndNextViewer_, WM_DRAWCLIPBOARD, wParam, lParam);
			break;
		}
							 //781
		case WM_CHANGECBCHAIN:
		{
			// ������������ı仯
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