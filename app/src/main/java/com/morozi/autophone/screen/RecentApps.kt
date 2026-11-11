package com.morozi.autophone.screen

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * The apps for the floating button's "Abas" panel: the most recently used
 * apps that can be opened, newest first. Android TV has no recents screen.
 *
 * The order comes from usage stats (GET_USAGE_STATS, granted with appops by
 * tools/pi/install.sh). Without that grant, the panel lists the installed
 * apps by name instead.
 */
internal object RecentApps {

    data class App(val label: String, val icon: Drawable, val intent: Intent, val pkg: String)

    private const val LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000

    fun list(context: Context, max: Int): List<App> {
        val pm = context.packageManager
        val skip = setOf(context.packageName, homePackage(context))
        val launchable = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
            .flatMap { category -> pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0) }
            .map { it.activityInfo.packageName }
            .filter { it !in skip }
            .distinct()

        val usage = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val lastUsed = runCatching {
            usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - LOOKBACK_MS, now)
                .groupBy { it.packageName }
                .mapValues { (_, stats) -> stats.maxOf { it.lastTimeUsed } }
        }.getOrDefault(emptyMap())

        val ordered = if (lastUsed.isEmpty()) {
            launchable.sortedBy { label(context, it) }
        } else {
            launchable.filter { (lastUsed[it] ?: 0) > 0 }.sortedByDescending { lastUsed[it] }
        }
        return ordered.take(max).mapNotNull { pkg ->
            val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            App(label(context, pkg), pm.getApplicationIcon(pkg), intent, pkg)
        }
    }

    /** The activity that opens [pkg], as "package/class" (for split screen), or null. */
    fun launchComponent(context: Context, pkg: String): String? {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        return intent?.component?.flattenToString()
    }

    private fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun homePackage(context: Context): String? =
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
}
