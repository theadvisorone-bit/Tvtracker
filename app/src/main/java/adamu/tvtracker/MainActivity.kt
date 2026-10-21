package com.adamu.tvtracker

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.concurrent.thread

// ---- EDIT THESE TWO LINES with your own Supabase project details ----
// Find them in Supabase: Project Settings -> API
const val SUPABASE_URL = "https://abcdxyz.supabase.co"
const val SUPABASE_ANON_KEY = 
"sb_secret_rGOT80lOCC8034lY-h5Nzw_dPZXuCMA"
-------------------------------------------------------------------

// TradingView's Android package name (the normal Play Store app)
const val TRADINGVIEW_PACKAGE = "com.tradingview.tradingviewapp"

class MainActivity : AppCompatActivity() {

    private lateinit var countText: TextView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        countText = findViewById(R.id.countText)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.permissionButton).setOnClickListener {
            // Sends you to the one Settings screen where you grant Usage Access.
            // You do this once, visibly, for this app only.
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            refreshCount()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCount()
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun refreshCount() {
        if (!hasUsagePermission()) {
            statusText.text = "Tap 'Grant Usage Access' first, find this app in the list, and switch it on."
            countText.text = "--"
            return
        }

        val count = countTodaysOpens(TRADINGVIEW_PACKAGE)
        countText.text = count.toString()
        statusText.text = "Updated just now."

        sendCountToServer(count)
    }

    // Counts how many times the app moved to the foreground (an "open") since midnight today.
    private fun countTodaysOpens(pkg: String): Int {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val now = System.currentTimeMillis()

        val events: UsageEvents = usm.queryEvents(startOfDay, now)
        val event = UsageEvents.Event()
        var opens = 0

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName == pkg &&
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                opens++
            }
        }
        return opens
    }

    // Sends today's count to your Supabase table so your website can show it.
    private fun sendCountToServer(count: Int) {
        thread {
            try {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())
                val url = URL("$SUPABASE_URL/rest/v1/tradingview_opens?on_conflict=day")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                // "Prefer: resolution=merge-duplicates" makes this update today's
                // row instead of creating a new one every refresh.
                conn.setRequestProperty("Prefer", "resolution=merge-duplicates")
                conn.doOutput = true

                val body = JSONObject()
                body.put("day", today)
                body.put("opens", count)

                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode // triggers the request
                conn.disconnect()
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Saved locally. Could not reach server." }
            }
        }
    }
}
