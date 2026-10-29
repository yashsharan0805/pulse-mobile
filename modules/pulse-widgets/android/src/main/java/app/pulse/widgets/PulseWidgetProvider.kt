package app.pulse.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

open class PulseWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    ids.forEach { renderWidget(context, manager, it) }
  }
  override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
    renderWidget(context, manager, id)
  }
  override fun onDeleted(context: Context, ids: IntArray) { WidgetCache.remove(context, ids) }
}
class PulseSmallWidgetProvider : PulseWidgetProvider()
class PulseMediumWidgetProvider : PulseWidgetProvider()
class PulseLargeWidgetProvider : PulseWidgetProvider()

internal fun updateAllWidgets(context: Context) {
  val manager = AppWidgetManager.getInstance(context)
  listOf(PulseSmallWidgetProvider::class.java, PulseMediumWidgetProvider::class.java, PulseLargeWidgetProvider::class.java).forEach { provider ->
    manager.getAppWidgetIds(ComponentName(context, provider)).forEach { renderWidget(context, manager, it) }
  }
}

private val CELLS = arrayOf(
  intArrayOf(R.id.widget_cell_1, R.id.widget_title_1, R.id.widget_value_1, R.id.widget_reason_1, R.id.widget_progress_1),
  intArrayOf(R.id.widget_cell_2, R.id.widget_title_2, R.id.widget_value_2, R.id.widget_reason_2, R.id.widget_progress_2),
  intArrayOf(R.id.widget_cell_3, R.id.widget_title_3, R.id.widget_value_3, R.id.widget_reason_3, R.id.widget_progress_3),
  intArrayOf(R.id.widget_cell_4, R.id.widget_title_4, R.id.widget_value_4, R.id.widget_reason_4, R.id.widget_progress_4),
  intArrayOf(R.id.widget_cell_5, R.id.widget_title_5, R.id.widget_value_5, R.id.widget_reason_5, R.id.widget_progress_5),
  intArrayOf(R.id.widget_cell_6, R.id.widget_title_6, R.id.widget_value_6, R.id.widget_reason_6, R.id.widget_progress_6),
)

internal fun renderWidget(context: Context, manager: AppWidgetManager, id: Int) {
  val info = manager.getAppWidgetInfo(id) ?: return
  if (info.provider.packageName != context.packageName) return
  val size = try { WidgetSize.of(info.provider.className) } catch (_: IllegalArgumentException) { return }
  val snapshot = WidgetCache.read(context)
  val now = System.currentTimeMillis() / 1000
  val zone = TimeZone.getTimeZone(snapshot?.optString("timeZone") ?: TimeZone.getDefault().id)
  val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(Date(now * 1000))
  val sameDay = snapshot != null && snapshot.optString("day") == day
  val labels = snapshot?.optJSONObject("labels")
  fun label(key: String, fallback: Int) = labels?.optString(key)?.takeIf { it.isNotBlank() } ?: context.getString(fallback)
  val demo = snapshot?.optString("source") == "demo"
  val synced = snapshot?.optDouble("lastSyncTs", Double.NaN)?.takeIf { it.isFinite() && it > 0 }?.toLong()
  val status = when {
    snapshot == null -> label("open", R.string.pulse_widget_open)
    !sameDay -> label("today", R.string.pulse_widget_today)
    demo -> label("demo", R.string.pulse_widget_demo)
    synced == null || synced > now + 300 || now - synced > 3 * 60 * 60 -> label("stale", R.string.pulse_widget_stale)
    else -> label("updated", R.string.pulse_widget_updated) + " " + android.text.format.DateFormat.getTimeFormat(context).format(Date(synced * 1000))
  }
  val views = RemoteViews(context.packageName, size.layout)
  views.setTextViewText(R.id.widget_status, status)
  views.setOnClickPendingIntent(R.id.widget_root, openPulse(context, id, "pulse:///"))
  val configure = Intent(context, WidgetConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
  views.setOnClickPendingIntent(R.id.widget_configure, PendingIntent.getActivity(context, id, configure, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
  val records = snapshot?.optJSONArray("metrics")
  val metrics = mutableMapOf<String, JSONObject>()
  if (records != null) for (i in 0 until records.length()) records.optJSONObject(i)?.let { metrics[it.optString("key")] = it }
  val chosen = WidgetCache.selection(context, id, size)
  for (index in 0 until size.count) {
    val cell = CELLS[index]
    val key = chosen.getOrNull(index)
    views.setViewVisibility(cell[0], if (key == null) View.GONE else View.VISIBLE)
    if (key == null) continue
    val metric = metrics[key]
    val valid = sameDay && metric != null && !metric.isNull("value") && metric.optDouble("value", Double.NaN).isFinite()
    val title = metric?.optString("label")?.takeIf { it.isNotBlank() } ?: metricLabel(context, key)
    val unit = metric?.optString("unit").orEmpty()
    val number = if (valid) metric!!.optString("display", "--") + (if (unit == "%") unit else if (unit.isNotEmpty()) " $unit" else "") else "--"
    val reason = if (!sameDay) label(if (snapshot == null) "open" else "today", if (snapshot == null) R.string.pulse_widget_open else R.string.pulse_widget_today)
      else if (!valid) metric?.optString("reason")?.takeIf { it != "null" && it.isNotBlank() } ?: label("open", R.string.pulse_widget_open)
      else if (metric!!.optBoolean("provisional")) label("provisional", R.string.pulse_widget_provisional) else ""
    views.setTextViewText(cell[1], title)
    views.setTextViewText(cell[2], number)
    views.setTextViewText(cell[3], reason)
    views.setViewVisibility(cell[3], if (reason.isBlank() || !sameDay) View.GONE else View.VISIBLE)
    views.setContentDescription(cell[0], listOf(title, number, reason).filter { it.isNotBlank() }.joinToString(", "))
    val color = try { Color.parseColor(metric?.optString("color") ?: "#12c805") } catch (_: IllegalArgumentException) { Color.GRAY }
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    views.setTextColor(cell[2], if (!valid) context.getColor(R.color.pulse_widget_muted) else if (dark) color else context.getColor(R.color.pulse_widget_fg))
    val progress = metric?.optDouble("progress", Double.NaN) ?: Double.NaN
    views.setViewVisibility(cell[4], if (valid && progress.isFinite()) View.VISIBLE else View.INVISIBLE)
    views.setProgressBar(cell[4], 100, if (progress.isFinite()) progress.coerceIn(0.0, 100.0).toInt() else 0, false)
    views.setOnClickPendingIntent(cell[0], openPulse(context, id, metric?.optString("href")?.takeIf { it.startsWith("pulse://") } ?: "pulse:///"))
  }
  manager.updateAppWidget(id, views)
}

private fun openPulse(context: Context, id: Int, href: String): PendingIntent {
  val intent = Intent(Intent.ACTION_VIEW, Uri.parse(href)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
  return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
internal fun metricLabel(context: Context, key: String): String = context.getString(when (key) {
  "recovery" -> R.string.pulse_widget_metric_recovery
  "strain" -> R.string.pulse_widget_metric_strain
  "sleep" -> R.string.pulse_widget_metric_sleep
  "steps" -> R.string.pulse_widget_metric_steps
  "calories" -> R.string.pulse_widget_metric_calories
  "hrv" -> R.string.pulse_widget_metric_hrv
  "rhr" -> R.string.pulse_widget_metric_rhr
  else -> R.string.pulse_widget_metric_stress
})
