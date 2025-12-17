package top.coclyun.clipshare.clipboard_listener.utils

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImageTypeDetector {

    companion object {
        /**
         * 通过文件头魔数判断是否为图片
         */
        @JvmStatic
        fun isImageStream(inputStream: InputStream): Boolean {
            return try {
                val buffer = ByteArray(12)
                val bytesRead = inputStream.read(buffer, 0, buffer.size)

                if (bytesRead >= 12) {
                    isImageByMagicNumber(buffer)
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            } finally {
                inputStream.close()
            }
        }

        /**
         * 通过字节数组判断是否为图片
         */
        @JvmStatic
        fun isImageData(data: ByteArray): Boolean {
            if (data.size < 12) return false
            return isImageByMagicNumber(data)
        }

        /**
         * 通过文件头魔数检测
         */
        private fun isImageByMagicNumber(data: ByteArray): Boolean {
            return isPng(data) || isJpeg(data) || isGif(data) ||
                    isWebp(data) || isBmp(data) || isHeif(data) || isTiff(data)
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        private fun isPng(data: ByteArray): Boolean {
            return data.size >= 8 &&
                    data[0] == 0x89.toByte() &&
                    data[1] == 0x50.toByte() &&
                    data[2] == 0x4E.toByte() &&
                    data[3] == 0x47.toByte() &&
                    data[4] == 0x0D.toByte() &&
                    data[5] == 0x0A.toByte() &&
                    data[6] == 0x1A.toByte() &&
                    data[7] == 0x0A.toByte()
        }

        // JPEG: FF D8 FF
        private fun isJpeg(data: ByteArray): Boolean {
            return data.size >= 3 &&
                    data[0] == 0xFF.toByte() &&
                    data[1] == 0xD8.toByte() &&
                    data[2] == 0xFF.toByte()
        }

        // GIF: GIF87a 或 GIF89a
        private fun isGif(data: ByteArray): Boolean {
            if (data.size < 6) return false
            val header = String(data, 0, 6, Charsets.US_ASCII)
            return header == "GIF87a" || header == "GIF89a"
        }

        // WebP: RIFF
        private fun isWebp(data: ByteArray): Boolean {
            if (data.size < 12) return false
            val riff = String(data, 0, 4, Charsets.US_ASCII)
            val webp = String(data, 8, 4, Charsets.US_ASCII)
            return riff == "RIFF" && webp == "WEBP"
        }

        // BMP: BM
        private fun isBmp(data: ByteArray): Boolean {
            return data.size >= 2 &&
                    data[0] == 0x42.toByte() && // B
                    data[1] == 0x4D.toByte()    // M
        }

        // HEIF/HEIC: ftyp
        private fun isHeif(data: ByteArray): Boolean {
            if (data.size < 12) return false
            val ftyp = String(data, 4, 4, Charsets.US_ASCII)
            return ftyp == "ftyp" && (
                    String(data, 8, 4, Charsets.US_ASCII) == "heic" ||
                            String(data, 8, 4, Charsets.US_ASCII) == "mif1" ||
                            String(data, 8, 4, Charsets.US_ASCII) == "msf1" ||
                            String(data, 8, 4, Charsets.US_ASCII) == "heix" ||
                            String(data, 8, 4, Charsets.US_ASCII) == "hevc"
                    )
        }

        // TIFF: II 或 MM
        private fun isTiff(data: ByteArray): Boolean {
            if (data.size < 4) return false
            val byteOrder = if (data[0] == 0x49.toByte() && data[1] == 0x49.toByte()) {
                ByteOrder.LITTLE_ENDIAN
            } else if (data[0] == 0x4D.toByte() && data[1] == 0x4D.toByte()) {
                ByteOrder.BIG_ENDIAN
            } else {
                return false
            }

            val buffer = ByteBuffer.wrap(data, 2, 2).order(byteOrder)
            val magicNumber = buffer.short.toInt() and 0xFFFF
            return magicNumber == 42
        }
    }
}