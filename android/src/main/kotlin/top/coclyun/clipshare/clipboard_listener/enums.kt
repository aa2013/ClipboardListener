package top.coclyun.clipshare.clipboard_listener

enum class EnvironmentType {
    shizuku,
    root,
    androidPre10
}

enum class EventEnum {
    onChanged, comment, fatal, eof
}

enum class ClipboardContentType {
    Text, Image;

    companion object {
        fun parse(name: String): ClipboardContentType {
            for (v in values()) {
                if (v.name.lowercase() == name.lowercase()) {
                    return v
                }
            }
            throw IllegalArgumentException("no such element $name")
        }
    }
}

enum class ClipboardListeningWay {
    logs, hiddenApi;
}