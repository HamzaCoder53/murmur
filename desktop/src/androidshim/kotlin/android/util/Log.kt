package android.util

import com.bitchat.desktop.shim.ShimRuntime

/**
 * JVM stand-in for android.util.Log, covering the calls the shared core makes.
 * Messages go to [ShimRuntime.logSink] (stderr by default) when at or above
 * [ShimRuntime.minLogLevel]. Shared code already avoids logging message contents and keys.
 */
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    @JvmStatic fun v(tag: String?, msg: String?): Int = emit(VERBOSE, tag, msg, null)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = emit(VERBOSE, tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String?): Int = emit(DEBUG, tag, msg, null)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = emit(DEBUG, tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?): Int = emit(INFO, tag, msg, null)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = emit(INFO, tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?): Int = emit(WARN, tag, msg, null)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = emit(WARN, tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = emit(WARN, tag, null, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = emit(ERROR, tag, msg, null)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = emit(ERROR, tag, msg, tr)

    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = level >= ShimRuntime.minLogLevel

    private fun emit(level: Int, tag: String?, msg: String?, tr: Throwable?): Int {
        if (level < ShimRuntime.minLogLevel) return 0
        ShimRuntime.logSink(level, tag ?: "", msg ?: "", tr)
        return 0
    }
}
