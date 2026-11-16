package com.bitchat.desktop.sentry

import android.content.Context
import com.bitchat.desktop.platform.CrashReporter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Opt-in Sentry crash reporter using Sentry's standard envelope HTTP API over OkHttp.
 * Disabled by default until the user opts in AND a valid DSN is provided via
 * `-Dskein.sentryDsn=...` or `SKEIN_SENTRY_DSN`.
 */
class SentryCrashReporter(
    private val contextProvider: () -> Context,
    private val dsnProvider: () -> String? = {
        System.getProperty("skein.sentryDsn")?.takeIf { it.isNotBlank() }
            ?: System.getenv("SKEIN_SENTRY_DSN")?.takeIf { it.isNotBlank() }
    },
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
) : CrashReporter {

    internal data class ParsedDsn(val envelopeUrl: String, val authHeader: String)

    private val breadcrumbs = ConcurrentLinkedDeque<JSONObject>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "skein-sentry").apply { isDaemon = true }
    }

    @Volatile
    private var releaseName: String = "unknown"
    @Volatile
    private var environmentName: String = "production"
    @Volatile
    private var parsedDsn: ParsedDsn? = null

    override val isEnabled: Boolean
        get() = parsedDsn != null && readOptIn()

    fun hasOptInDecision(): Boolean =
        prefs().contains(KEY_OPT_IN)

    override fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_OPT_IN, enabled).commit()
    }

    override fun init(release: String, environment: String) {
        releaseName = release
        environmentName = environment
        parsedDsn = dsnProvider()?.let(::parseDsn)
    }

    override fun addBreadcrumb(category: String, message: String) {
        if (!isEnabled) return
        breadcrumbs.addLast(
            JSONObject()
                .put("timestamp", Instant.now().toString())
                .put("category", SentryScrubber.scrubText(category))
                .put("message", SentryScrubber.scrubText(message)),
        )
        while (breadcrumbs.size > MAX_BREADCRUMBS) {
            breadcrumbs.pollFirst()
        }
    }

    override fun capture(throwable: Throwable, context: Map<String, String>) {
        val dsn = parsedDsn ?: return
        if (!readOptIn()) return
        val envelope = buildEnvelope(throwable, context, dsn)
        executor.execute {
            try {
                val req = Request.Builder()
                    .url(dsn.envelopeUrl)
                    .header("X-Sentry-Auth", dsn.authHeader)
                    .post(envelope.toRequestBody("application/x-sentry-envelope".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }
    }

    override fun flush(timeoutMs: Long) {
        try {
            executor.submit { }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    override fun close() {
        executor.shutdown()
    }

    internal fun buildEnvelope(
        throwable: Throwable,
        context: Map<String, String>,
        dsn: ParsedDsn? = parsedDsn,
    ): String {
        val eventId = UUID.randomUUID().toString().replace("-", "")
        val header = JSONObject()
            .put("event_id", eventId)
            .put("sent_at", Instant.now().toString())
        if (dsn != null) header.put("dsn", dsn.envelopeUrl)

        val frames = JSONArray()
        for (el in throwable.stackTrace.take(MAX_FRAMES)) {
            frames.put(
                JSONObject()
                    .put("module", el.className)
                    .put("function", el.methodName)
                    .put("lineno", el.lineNumber),
            )
        }
        val excValue = JSONObject()
            .put("type", throwable.javaClass.name)
            .put("value", SentryScrubber.scrubText(throwable.message.orEmpty()))
            .put("stacktrace", JSONObject().put("frames", frames))

        val event = JSONObject()
            .put("event_id", eventId)
            .put("timestamp", Instant.now().toString())
            .put("platform", "java")
            .put("release", releaseName)
            .put("environment", environmentName)
            .put("exception", JSONObject().put("values", JSONArray().put(excValue)))
            .put("extra", JSONObject(SentryScrubber.scrubContext(context)))
            .put("breadcrumbs", JSONObject().put("values", JSONArray(breadcrumbs.toList())))

        val itemHeader = JSONObject().put("type", "event")
        return "${header}\n${itemHeader}\n${event}\n"
    }

    private fun prefs() = contextProvider().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun readOptIn(): Boolean = prefs().getBoolean(KEY_OPT_IN, false)

    private fun parseDsn(raw: String): ParsedDsn? = try {
        val uri = URI(raw.trim())
        val key = uri.userInfo?.substringBefore(':') ?: return null
        val projectId = uri.path.trim('/').substringAfterLast('/')
        if (projectId.isEmpty()) return null
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        val url = "${uri.scheme}://${uri.host}$portPart/api/$projectId/envelope/"
        val auth = "Sentry sentry_version=7, sentry_key=$key, sentry_client=skein-desktop/1.0"
        ParsedDsn(url, auth)
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val PREFS_NAME = "skein_crash_reporting"
        private const val KEY_OPT_IN = "sentry_opt_in"
        private const val MAX_BREADCRUMBS = 30
        private const val MAX_FRAMES = 40
    }
}
