package io.nekohasekai.sagernet.ktx

import android.util.Log
import java.io.InputStream
import java.io.OutputStream

object Logs {
    private fun mkTag(): String = Thread.currentThread().stackTrace
        .firstOrNull { it.className.startsWith("io.nekohasekai") && it.className != Logs::class.java.name }
        ?.className?.substringAfterLast('.') ?: "wanBox"

    fun d(message: String) = Log.d(mkTag(), message)
    fun d(message: String, exception: Throwable) = Log.d(mkTag(), message, exception)
    fun i(message: String) = Log.i(mkTag(), message)
    fun i(message: String, exception: Throwable) = Log.i(mkTag(), message, exception)
    fun w(message: String) = Log.w(mkTag(), message)
    fun w(message: String, exception: Throwable) = Log.w(mkTag(), message, exception)
    fun w(exception: Throwable) = Log.w(mkTag(), exception.toString(), exception)
    fun e(message: String) = Log.e(mkTag(), message)
    fun e(message: String, exception: Throwable) = Log.e(mkTag(), message, exception)
    fun e(exception: Throwable) = Log.e(mkTag(), exception.toString(), exception)
}

fun InputStream.use(out: OutputStream) {
    use { input -> out.use { output -> input.copyTo(output) } }
}
