package dev.jmx.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.jmx.client.store.JmxDiagnostics
import dev.jmx.client.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JmxDiagnostics.i(
            "Lifecycle",
            "MainActivity onCreate",
            metadata = mapOf(
                "component" to "MainActivity",
                "callback" to "onCreate",
                "saved_state" to (savedInstanceState != null),
                "launch_action" to intent?.action.orEmpty(),
                "launch_data" to intent?.dataString.orEmpty()
            )
        )

        enableEdgeToEdge()
        setContent {
            AppTheme {
                App()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        JmxDiagnostics.setForeground(true)
        JmxDiagnostics.i(
            "Lifecycle",
            "MainActivity onStart",
            metadata = mapOf("component" to "MainActivity", "callback" to "onStart")
        )
    }

    override fun onResume() {
        super.onResume()
        JmxDiagnostics.setForeground(true)
        JmxDiagnostics.i(
            "Lifecycle",
            "MainActivity onResume",
            metadata = mapOf("component" to "MainActivity", "callback" to "onResume")
        )
    }

    override fun onPause() {
        JmxDiagnostics.i(
            "Lifecycle",
            "MainActivity onPause",
            metadata = mapOf("component" to "MainActivity", "callback" to "onPause")
        )
        super.onPause()
    }

    override fun onStop() {
        JmxDiagnostics.i(
            "Lifecycle",
            "MainActivity onStop",
            metadata = mapOf("component" to "MainActivity", "callback" to "onStop")
        )
        JmxDiagnostics.setForeground(false)
        super.onStop()
    }
}
