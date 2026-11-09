package com.example.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

class ScreenCaptureActivity : Activity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val EXTRA_ACTION = "extra_action"
        const val ACTION_START_SCREEN = "action_start_screen"
        const val ACTION_START_AUDIO = "action_start_audio"
        const val ACTION_START_BOTH = "action_start_both"
        const val ACTION_SELECT_REGION = "action_select_region"
        const val ACTION_AUTO_MONITOR = "action_auto_monitor"

        fun createIntent(context: Context, action: String = ACTION_START_SCREEN): Intent {
            return Intent(context, ScreenCaptureActivity::class.java).apply {
                putExtra(EXTRA_ACTION, action)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private var targetAction: String = ACTION_START_SCREEN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetAction = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_START_SCREEN

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e("ScreenCaptureActivity", "Failed to launch screen capture intent", e)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                // Forward granted result to AskScreenOverlayService
                AskScreenOverlayService.onMediaProjectionGranted(this, resultCode, data, targetAction)
            } else {
                AskScreenOverlayService.onMediaProjectionDenied(this)
            }
        }
        finish()
    }
}
