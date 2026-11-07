#pragma once

#include <windows.h>

#ifndef BI_ALPHABITFIELDS
#define BI_ALPHABITFIELDS 6
#endif

// 计算 CF_DIB 中像素数据相对头部起点的偏移。
// BITMAPINFOHEADER 后面可能跟着颜色掩码（BI_BITFIELDS 为 3 个 DWORD，
// BI_ALPHABITFIELDS 为 4 个）和调色板，像素数据位于它们之后；
// V4/V5 头已把掩码包含在 biSize 内。
constexpr SIZE_T GetDibPixelOffset(const BITMAPINFOHEADER* header)
{
	SIZE_T offset = header->biSize;
	if (header->biSize == sizeof(BITMAPINFOHEADER))
	{
		if (header->biCompression == BI_BITFIELDS)
			offset += 3 * sizeof(DWORD);
		else if (header->biCompression == BI_ALPHABITFIELDS)
			offset += 4 * sizeof(DWORD);
	}
	SIZE_T colors = header->biClrUsed;
	if (colors == 0 && header->biBitCount <= 8)
		colors = static_cast<SIZE_T>(1) << header->biBitCount;
	return offset + colors * sizeof(RGBQUAD);
}
