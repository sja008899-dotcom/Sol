package com.example.sol.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.R

class SolQuickSettingsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val role = SolWidgetStateMirror.readActiveRole(this)
        val location = SolWidgetStateMirror.readConfirmedLocation(this)

        tile.state = Tile.STATE_ACTIVE
        tile.label = getString(R.string.qs_tile_label)
        tile.contentDescription = "باز کردن فوری میکروفون Sol"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                role.isNotBlank() -> role
                location.isNotBlank() -> location
                else -> "دستیار فارسی"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val launch = {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_START_VOICE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    9201,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }

        if (isLocked) {
            unlockAndRun { launch() }
        } else {
            launch()
        }
    }

    companion object {
        fun promptPinTile(context: Context, onResult: (String) -> Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val sbm = context.getSystemService(android.app.StatusBarManager::class.java)
                if (sbm == null) {
                    onResult("سرویس نوار وضعیت روی این دستگاه در دسترس نیست.")
                    return
                }
                sbm.requestAddTileService(
                    ComponentName(context, SolQuickSettingsTileService::class.java),
                    context.getString(R.string.qs_tile_label),
                    Icon.createWithResource(context, R.drawable.ic_sol_tile_mic),
                    context.mainExecutor,
                ) { result ->
                    val msg = when (result) {
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                            "دکمه «صحبت با Sol» به نوار اعلان بالای گوشی اضافه شد."
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                            "دکمه «صحبت با Sol» از قبل در نوار بالای گوشی وجود دارد."
                        else -> "پنجرهٔ افزودن دکمهٔ نوار بالا بسته شد یا پشتیبانی نشد."
                    }
                    onResult(msg)
                }
            } else {
                onResult("نوار اعلان بالای گوشی را پایین بکشید، دکمه ویرایش (مداد) را بزنید و «صحبت با Sol» را اضافه کنید.")
            }
        }
    }
}
