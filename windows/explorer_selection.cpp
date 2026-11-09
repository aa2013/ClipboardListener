#include "explorer_selection.h"

// This must be included before many other Windows headers.
#include <windows.h>

#include <atlbase.h>  // CComPtr
#include <shellapi.h> // DragQueryFileW
#include <shlobj.h>   // SID_STopLevelBrowser / SVGIO_SELECTION
#include <exdisp.h>   // IShellWindows / SWC_DESKTOP / SWFO_NEEDDISPATCH
#include <servprov.h> // IServiceProvider
#include <vector>

#include "utils.h"

namespace clipshare_clipboard_listener {

	namespace {
		// 从 shell 视图取选区对象并解析为文件完整路径（CF_HDROP）
		bool PathsFromShellView(IShellView* view, std::vector<std::wstring>& paths) {
			CComPtr<IDataObject> items;
			HRESULT hr = view->GetItemObject(SVGIO_SELECTION, IID_PPV_ARGS(&items));
			if (!SUCCEEDED(hr)) {
				return false;
			}

			FORMATETC fmt = { CF_HDROP, NULL, DVASPECT_CONTENT, -1, TYMED_HGLOBAL };
			STGMEDIUM stg;
			hr = items->GetData(&fmt, &stg);
			if (!SUCCEEDED(hr)) {
				//选区含非文件系统项（如"此电脑/回收站"）时走这里
				return false;
			}

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
			return true;
		}

		//桌面视图不在 IShellWindows 常规枚举中，需用 FindWindowSW(SWC_DESKTOP) 获取
		bool GetSelectedFilesFromDesktop(std::vector<std::wstring>& paths) {
			if (!SUCCEEDED(CoInitialize(NULL))) {
				return false;
			}
			bool succeed = false;
			CComPtr<IShellWindows> shellWindows;
			if (SUCCEEDED(shellWindows.CoCreateInstance(CLSID_ShellWindows))) {
				VARIANT vEmpty;
				vEmpty.vt = VT_EMPTY;
				long desktopHwnd;
				CComPtr<IDispatch> dispatch;
				//S_FALSE 也是 success code，必须同时确认拿到 dispatch
				HRESULT hr = shellWindows->FindWindowSW(&vEmpty, &vEmpty, SWC_DESKTOP, &desktopHwnd, SWFO_NEEDDISPATCH, &dispatch);
				if (hr == S_OK && dispatch) {
					//参考：https://cloud.tencent.com/developer/ask/sof/114218481
					CComPtr<IServiceProvider> sp;
					CComPtr<IShellBrowser> browser;
					CComPtr<IShellView> sw;
					if (SUCCEEDED(dispatch->QueryInterface(IID_IServiceProvider, (void**)&sp)) &&
						SUCCEEDED(sp->QueryService(SID_STopLevelBrowser, IID_IShellBrowser, (void**)&browser)) &&
						SUCCEEDED(browser->QueryActiveShellView(&sw))) {
						succeed = PathsFromShellView(sw, paths);
					}
				}
			}
			CoUninitialize();
			return succeed;
		}

		bool GetSelectedFilesFromExplorer(std::vector<std::wstring>& paths) {
			if (!SUCCEEDED(CoInitialize(NULL))) {
				return false;
			}
			bool succeed = false;
			CComPtr<IShellWindows> shellWindows;
			if (SUCCEEDED(shellWindows.CoCreateInstance(CLSID_ShellWindows))) {
				long count;
				shellWindows->get_Count(&count);
				HWND foregroundWindow = GetForegroundWindow();
				for (long i = 0; i < count; ++i) {
					VARIANT index;
					index.vt = VT_I4;
					index.lVal = i;

					CComPtr<IDispatch> dispatch;
					if (!SUCCEEDED(shellWindows->Item(index, &dispatch)) || !dispatch) {
						continue;
					}
					//webBrowser用于获取shellwindow的hwnd
					CComPtr<IWebBrowserApp> webBrowser;
					if (!SUCCEEDED(dispatch->QueryInterface(IID_IWebBrowserApp, (void**)&webBrowser))) {
						continue;
					}
					HWND hwnd;
					HRESULT hr = webBrowser->get_HWND((SHANDLE_PTR*)&hwnd);
					if (!SUCCEEDED(hr) || foregroundWindow != hwnd) {
						//不在前台，跳过
						continue;
					}

					//参考：https://cloud.tencent.com/developer/ask/sof/114218481
					CComPtr<IServiceProvider> sp;
					if (!SUCCEEDED(dispatch->QueryInterface(IID_IServiceProvider, (void**)&sp))) {
						continue;
					}
					CComPtr<IShellBrowser> browser;
					if (!SUCCEEDED(sp->QueryService(SID_STopLevelBrowser, IID_IShellBrowser, (void**)&browser))) {
						continue;
					}
					CComPtr<IShellView> sw;
					if (!SUCCEEDED(browser->QueryActiveShellView(&sw))) {
						continue;
					}
					succeed = PathsFromShellView(sw, paths);
					break; //前台窗口已找到并处理
				}
			}
			CoUninitialize();
			return succeed;
		}
	}

	bool GetSelectedFiles(std::string& fileList) {
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
