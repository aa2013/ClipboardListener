#include <flutter_linux/flutter_linux.h>

#include "include/clipshare_clipboard_listener/clipshare_clipboard_listener_plugin.h"

// This file exposes some plugin internals for unit testing. See
// https://github.com/flutter/flutter/issues/88724 for current limitations
// in the unit-testable API.

// Handles the getPlatformVersion method call.

const char *kChannelName = "top.coclyun.clipshare/clipboard_listener";
const char *kOnClipboardChanged = "onClipboardChanged";
const char *kStartListening = "startListening";
const char *kStopListening = "stopListening";
const char *kCheckIsRunning = "checkIsRunning";
const char *kCopy = "copy";

static FlMethodResponse *startListening(ClipshareClipboardListenerPlugin *self, FlValue *args);
static FlMethodResponse *stopListening(ClipshareClipboardListenerPlugin *self, FlValue *args);
static FlMethodResponse *checkIsRunning(ClipshareClipboardListenerPlugin *self, FlValue *args);
static FlMethodResponse *copyData(ClipshareClipboardListenerPlugin *self, const gchar *type, const gchar *content);
static void onClipboardChanged(GtkClipboard *clipboard, GdkEvent *event, gpointer data);
static gchar *getCurrentTimeWithMilliseconds();