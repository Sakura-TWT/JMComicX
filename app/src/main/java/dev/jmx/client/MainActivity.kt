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
        JmxDiagnostics.i("MainActivity", "onCreate savedState=${savedInstanceState != null}")

        enableEdgeToEdge()
        setContent {
            AppTheme {
                App()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        JmxDiagnostics.i("MainActivity", "onStart")
    }

    override fun onResume() {
        super.onResume()
        JmxDiagnostics.i("MainActivity", "onResume")
    }

    override fun onPause() {
        JmxDiagnostics.i("MainActivity", "onPause")
        super.onPause()
    }

    override fun onStop() {
        JmxDiagnostics.i("MainActivity", "onStop")
        super.onStop()
    }
}
