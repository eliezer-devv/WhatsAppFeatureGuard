package com.example.whatsappfeatureguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class FeatureGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(
            context,
            "Feature Guard device admin enabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(
            context,
            "Feature Guard device admin disabled",
            Toast.LENGTH_SHORT
        ).show()
    }
}
