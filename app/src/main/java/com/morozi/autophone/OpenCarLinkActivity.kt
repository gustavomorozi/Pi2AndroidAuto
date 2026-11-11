package com.morozi.autophone

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

private const val TAG = "OpenCarLink"

/**
 * A launcher entry named "CarLink" in the Android TV menu. The CarLink app
 * (https://github.com/F-e-n-y-x/CarLink) has no Android TV launcher icon, so
 * after installing it there would be nothing to open it from. This opens its
 * home screen and closes at once.
 */
class OpenCarLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(CARLINK_PACKAGE, "$CARLINK_PACKAGE.ui.HomeActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "could not open CarLink", e)
            Toast.makeText(this, "CarLink não está instalado (rode o install.sh)", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private companion object {
        const val CARLINK_PACKAGE = "com.carlink.app"
    }
}
