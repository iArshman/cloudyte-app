package com.example.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.ThemeMode
import com.example.data.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private lateinit var application: Application
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        UserPreferences.resetAllSettings(application)
        viewModel = SettingsViewModel(application)
    }

    @Test
    fun testSelectSection() {
        assertEquals(null, viewModel.currentSection.value)

        viewModel.selectSection(SettingsSectionType.BACKUP)
        assertEquals(SettingsSectionType.BACKUP, viewModel.currentSection.value)

        viewModel.selectSection(null)
        assertEquals(null, viewModel.currentSection.value)
    }

    @Test
    fun testSetThemeMode() = runBlocking {
        UserPreferences.setThemeMode(application, ThemeMode.LIGHT)
        val state = UserPreferences.observe(application).first()
        assertEquals(ThemeMode.LIGHT, state.themeMode)
    }
}
