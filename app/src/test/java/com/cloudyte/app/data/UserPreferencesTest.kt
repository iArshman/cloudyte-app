package com.cloudyte.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserPreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        UserPreferences.resetAllSettings(context)
    }

    @Test
    fun testDefaultPreferences() = runBlocking {
        val state = UserPreferences.observe(context).first()
        assertEquals(ThemeMode.DARK, state.themeMode)
        assertEquals(ViewMode.GRID, state.viewMode)
        assertEquals(2, state.gridColumns)
        assertTrue(state.backupWifiOnly)
    }

    @Test
    fun testUpdatePreferences() = runBlocking {
        UserPreferences.setThemeMode(context, ThemeMode.LIGHT)
        UserPreferences.setViewMode(context, ViewMode.LIST)
        UserPreferences.setGridColumns(context, 4)
        UserPreferences.setBackupWifiOnly(context, false)

        val state = UserPreferences.observe(context).first()
        assertEquals(ThemeMode.LIGHT, state.themeMode)
        assertEquals(ViewMode.LIST, state.viewMode)
        assertEquals(4, state.gridColumns)
        assertFalse(state.backupWifiOnly)
    }

    @Test
    fun testResetAllSettings() = runBlocking {
        UserPreferences.setThemeMode(context, ThemeMode.LIGHT)
        UserPreferences.resetAllSettings(context)

        val state = UserPreferences.observe(context).first()
        assertEquals(ThemeMode.DARK, state.themeMode)
    }
}
