package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PreferencesManager
import com.example.data.model.QueryType
import com.example.data.model.WindowSizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AskScreen AI", appName)
    }

    @Test
    fun `verify preferences manager defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesManager(context)
        assertEquals(60, prefs.bubbleSizeDp)
        assertEquals(90, prefs.bubbleOpacityPercent)
        assertEquals("gemini-3.8-flash", prefs.geminiModel)
        assertEquals(WindowSizePreset.MEDIUM, prefs.windowPreset)
    }

    @Test
    fun `verify query type display names`() {
        assertEquals("Screen Reading", QueryType.SCREEN.displayName)
        assertEquals("Audio Listening", QueryType.AUDIO.displayName)
        assertEquals("Screen & Audio", QueryType.SCREEN_AND_AUDIO.displayName)
    }
}
