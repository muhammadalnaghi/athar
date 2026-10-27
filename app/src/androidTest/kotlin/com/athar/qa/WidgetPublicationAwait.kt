package com.athar.qa

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.common.money.Money
import com.athar.feature.widgets.WidgetDataLoader
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.math.BigDecimal

/** Observe actual host views before the instrumentation process is deliberately torn down. */
internal object WidgetPublicationAwait {
    suspend fun awaitCurrent(context: Context): JSONObject {
        val manager = AppWidgetManager.getInstance(context)
        for (receiver in listOf("monthly.MonthlyWidgetReceiver", "today.TodayWidgetReceiver")) {
            check(manager.getAppWidgetIds(ComponentName(context.packageName,
                "com.athar.feature.widgets.$receiver")).size == 1) {
                "Publication observation requires one placed widget for each owned provider"
            }
        }
        val month = WidgetDataLoader.loadMonthlySnapshot(context)
        val today = WidgetDataLoader.loadTodaySnapshot(context)
        check(month.localeTag.startsWith("ar")) { "This bounded observation is Arabic only" }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
                it.readBytes()
            }
        }
        fun texts(): List<String> {
            val output = mutableListOf<String>()
            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                node.text?.toString()?.let(output::add)
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }
            walk(automation.rootInActiveWindow)
            return output
        }
        suspend fun await(label: String, matches: (List<String>) -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (matches(texts())) return
                delay(200)
            }
            error("Widget publication not observed: $label")
        }
        fun swipe(left: Boolean) {
            val metrics = context.resources.displayMetrics
            val start = (metrics.widthPixels * if (left) 0.9 else 0.1).toInt()
            val end = (metrics.widthPixels * if (left) 0.1 else 0.9).toInt()
            val y = (metrics.heightPixels * 0.7).toInt()
            shell("input swipe $start $y $end $y 400")
        }
        shell("input keyevent KEYCODE_HOME")
        await("owned widget page") { values ->
            values.contains("أثر · ${month.yearMonth}") || values.contains("أثر · اليوم")
        }
        if (!texts().contains("أثر · ${month.yearMonth}")) swipe(left = true)
        await("monthly restored money") { values ->
            values.contains("أثر · ${month.yearMonth}") && values.any { matchesMoney(it, month.netFlow) } &&
                (month.declaredPosition?.let { position -> values.any { matchesMoney(it, position.net) } } ?: true)
        }
        swipe(left = false)
        await("today restored money") { values ->
            values.contains("أثر · اليوم") && values.any { matchesMoney(it, today.todayNet) } &&
                (today.declaredPosition?.let { position -> values.any { matchesMoney(it, position.net) } } ?: true)
        }
        return JSONObject().put("monthlyRendered", true).put("todayRendered", true)
            .put("manualRefresh", false).put("financialWrites", false)
    }

    private fun matchesMoney(text: String, expected: Money): Boolean {
        val normalized = buildString {
            for (c in text) when (c) {
                in '٠'..'٩' -> append('0' + (c - '٠'))
                '٫' -> append('.')
                '−' -> append('-')
                '٬', ',', '⁦', '⁧', '⁨', '⁩' -> Unit
                else -> if (!c.isWhitespace()) append(c)
            }
        }
        val match = Regex("""^(-?[0-9]+(?:\.[0-9]+)?)([A-Z]{3})$""").matchEntire(normalized) ?: return false
        return match.groupValues[2] == expected.currency &&
            BigDecimal(match.groupValues[1]).compareTo(expected.amount) == 0
    }
}
