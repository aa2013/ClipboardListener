package top.coclyun.clipshare.clipboard_listener.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class ActivityChangedService : AccessibilityService() {
    companion object {
        @JvmField
        var topPkgName: String? = null
    }

    private val TAG = "ActivityChangedService"

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        Log.d(TAG, "onAccessibilityEvent, event: ${event.eventType}")
        if (event.eventType == AccessibilityEvent.WINDOWS_CHANGE_ACTIVE) {
            topPkgName = event.packageName.toString()
            Log.d(TAG, "onAccessibilityEvent, pkgName: $topPkgName")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "interrupted!")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.w(TAG, "onServiceConnected")
    }
}