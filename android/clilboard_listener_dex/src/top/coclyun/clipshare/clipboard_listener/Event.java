package top.coclyun.clipshare.clipboard_listener;

public record Event(EventEnum event, String content) {
    @Override
    public String toString() {
        return event.name() + ":" + content;
    }
}
