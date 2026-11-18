package com.example.sol.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.sol.calendar.JalaliCalendar

object SolWidgetStateMirror {
    private const val PREFS_NAME = "sol_widget_mirror"
    private const val KEY_LOCATION = "confirmed_location"
    private const val KEY_ROLE = "active_role"

    fun saveAndRefresh(context: Context, confirmedLocation: String, activeRoleName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCATION, confirmedLocation)
            .putString(KEY_ROLE, activeRoleName)
            .apply()
        SolAppWidgetProvider.requestRefreshAll(context)
    }

    fun readConfirmedLocation(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOCATION, "")
            .orEmpty()

    fun readActiveRole(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, "")
            .orEmpty()
}

class SolAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidgetInstance(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, SolAppWidgetProvider::class.java))
            onUpdate(context, mgr, ids)
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.example.sol.ACTION_REFRESH_WIDGET"

        fun requestRefreshAll(context: Context) {
            val intent = Intent(context, SolAppWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            context.sendBroadcast(intent)
        }

        internal fun updateWidgetInstance(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_sol_assistant)
            val location = SolWidgetStateMirror.readConfirmedLocation(context)
                .ifBlank { "ثبت‌نشده" }
            val role = SolWidgetStateMirror.readActiveRole(context)
                .ifBlank { "حالت عمومی Sol" }
            val jalaliToday = JalaliCalendar.fromMillis().formatFullFa()

            views.setTextViewText(R.id.widget_confirmed_location, "موقعیت تأییدشده: $location")
            views.setTextViewText(R.id.widget_active_role, "نقش فعال: $role")
            views.setTextViewText(R.id.widget_jalali_date, "$jalaliToday • لمس میکروفون برای فرمان")

            val voiceIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_START_VOICE, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val voicePending = PendingIntent.getActivity(
                context,
                9101,
                voiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_mic_button, voicePending)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                9102,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
