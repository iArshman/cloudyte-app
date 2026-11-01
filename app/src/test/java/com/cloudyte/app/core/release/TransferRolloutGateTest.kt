package com.cloudyte.app.core.release

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransferRolloutGateTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("cloudyte_rollout_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultsEnabledAndCanBeDisabledForRollback() {
        val gate = TransferRolloutGate(context)
        assertTrue(gate.isEnabled())

        gate.disable("server_error_rate")

        assertFalse(gate.isEnabled())
        assertTrue(gate.current().reason == "server_error_rate")
    }

    @Test
    fun statePersistsAcrossGateInstances() {
        TransferRolloutGate(context).disable("manual")

        val restored = TransferRolloutGate(context)
        assertFalse(restored.isEnabled())
        restored.enable()
        assertTrue(TransferRolloutGate(context).isEnabled())
    }
}
