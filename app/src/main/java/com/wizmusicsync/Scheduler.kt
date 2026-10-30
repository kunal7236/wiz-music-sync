package com.wizmusicsync

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Stores power schedules and keeps one exact alarm armed per enabled schedule. */
object Scheduler {
    private const val PREFS = "schedules"
    private const val KEY = "list"
    private const val KEY_NEXT_ID = "nextId"

    fun all(context: Context): List<Schedule> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: Int) = all(context).firstOrNull { it.id == id }

    /** Saves a new schedule (its id is assigned here) and arms it. */
    fun add(context: Context, schedule: Schedule): Schedule {
        val p = prefs(context)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        val saved = schedule.copy(id = id)
        save(context, all(context) + saved)
        arm(context, saved)
        return saved
    }

    fun update(context: Context, schedule: Schedule) {
        save(context, all(context).map { if (it.id == schedule.id) schedule else it })
        arm(context, schedule)
    }

    fun delete(context: Context, id: Int) {
        disarm(context, id)
        save(context, all(context).filterNot { it.id == id })
    }

    /** After a schedule fired: timers are removed, one-off times switched off, repeating ones re-armed. */
    fun afterFired(context: Context, schedule: Schedule) {
        when {
            schedule.isTimer -> delete(context, schedule.id)
            schedule.isOneOff -> update(context, schedule.copy(enabled = false))
            else -> arm(context, schedule)
        }
    }

    /**
     * Re-arms everything (after a reboot, app update or clock change). One-off schedules whose
     * time passed while the phone was off are dropped rather than fired late.
     */
    fun rearmAll(context: Context) {
        for (schedule in all(context)) {
            if (schedule.nextTrigger() == null && schedule.isOneOff) {
                if (schedule.isTimer) delete(context, schedule.id)
                else if (schedule.enabled) update(context, schedule.copy(enabled = false))
            } else {
                arm(context, schedule)
            }
        }
    }

    fun clearAll(context: Context) {
        all(context).forEach { disarm(context, it.id) }
        prefs(context).edit().clear().apply()
    }

    /** False when Android only allows inexact alarms, which can fire several minutes late. */
    fun canFireOnTime(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun arm(context: Context, schedule: Schedule) {
        val at = schedule.nextTrigger()
        if (at == null) {
            disarm(context, schedule.id)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = pendingIntent(context, schedule.id)
        // AllowWhileIdle so the alarm still fires (with brief network access) while the phone dozes
        if (canFireOnTime(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun disarm(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, id))
    }

    private fun pendingIntent(context: Context, id: Int) = PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, ScheduleReceiver::class.java).putExtra(ScheduleReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun save(context: Context, list: List<Schedule>) {
        prefs(context).edit().putString(KEY, JSONArray(list.map { toJson(it) }).toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toJson(s: Schedule) = JSONObject()
        .put("id", s.id)
        .put("on", s.turnOn)
        .put("hour", s.hour)
        .put("minute", s.minute)
        .put("days", s.days)
        .put("enabled", s.enabled)
        .apply { s.atMillis?.let { put("at", it) } }

    private fun fromJson(o: JSONObject) = Schedule(
        id = o.getInt("id"),
        turnOn = o.getBoolean("on"),
        atMillis = if (o.has("at")) o.getLong("at") else null,
        hour = o.getInt("hour"),
        minute = o.getInt("minute"),
        days = o.getInt("days"),
        enabled = o.getBoolean("enabled"),
    )
}

/** Fires a schedule: stops music sync for "off", switches the bulb, then re-arms or retires it. */
class ScheduleReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_ID = "scheduleId"
        private const val CHANNEL_ID = "schedules"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val schedule = Scheduler.get(app, id)
                if (schedule != null && schedule.enabled) {
                    Settings.load(app)
                    if (!schedule.turnOn && SyncService.isRunning) {
                        SyncService.stopWithoutRestore()
                        Thread.sleep(600) // let the last queued colours go out before switching off
                    }
                    // Budget stays under ~10 s, the time Android allows a receiver to finish
                    var ok = WizBulb.setPower(Settings.bulbIp, schedule.turnOn, timeoutMs = 5000)
                    if (!ok) {
                        // The bulb may have a new IP: if exactly one bulb answers a scan, switch to it
                        val bulbs = WizBulb.discover(app, listenMs = 2000)
                        val moved = bulbs.singleOrNull()?.ip?.takeIf { it != Settings.bulbIp }
                        if (moved != null) {
                            Settings.setBulbIp(app, moved)
                            ok = WizBulb.setPower(moved, schedule.turnOn, timeoutMs = 2500)
                        }
                    }
                    Scheduler.afterFired(app, schedule)
                    if (!ok) notifyFailure(app, schedule)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun notifyFailure(context: Context, schedule: Schedule) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Schedules", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val action = if (schedule.turnOn) "on" else "off"
        manager.notify(
            1000 + schedule.id,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Couldn't turn the bulb $action")
                .setContentText("The bulb didn't answer. Is this device on the same Wi-Fi as the bulb?")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}

/** Re-arms schedules after a reboot, an app update or a clock / time-zone change. */
class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> Scheduler.rearmAll(context.applicationContext)
        }
    }
}
