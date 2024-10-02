package top.coclyun.clipshare.clipboard_listener

enum class EnvironmentType {
    shizuku,
    root,
    androidPre10
}

enum class ClipboardContentType {
    Text, Image;

    companion object {
        fun parse(name: String): ClipboardContentType {
            for (v in values()) {
                if (v.name.uppercase() == name.uppercase()) {
                    return v
                }
            }
            throw IllegalArgumentException("no such element $name")
        }
    }
}