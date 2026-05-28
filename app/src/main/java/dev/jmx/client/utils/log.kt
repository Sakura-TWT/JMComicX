package dev.jmx.client.utils

import android.util.Log
import dev.jmx.client.store.JmxDiagnostics

inline fun <reified T> T.log(msg: String) {
    Log.d("[JMX] ${T::class.java.simpleName}", msg)
    JmxDiagnostics.d(T::class.java.simpleName, msg)
}
