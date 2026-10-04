package com.example.scannerkotlin.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.scannerkotlin.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DeveloperCredits {
    const val COMPANY_NAME = "ООО «РЕГИУСЛАБ»"
    const val WEBSITE = "https://regiuslab.by/"
    const val EMAIL = "sales@regiuslab.by"
    const val PHONE_DISPLAY = "+375 44 798-04-98"
    const val PHONE_URI = "tel:+375447980498"
    const val UNP = "193776565"
    const val LEGAL_ADDRESS = "220083 г. Минск, пр. Газеты Правды 9, оф. 5Н"
    const val OFFICE_ADDRESS = "Тимирязева 65, помещение 202-5"

    fun showAboutDialog(context: Context) {
        val message = context.getString(
            R.string.developer_about_body,
            COMPANY_NAME,
            UNP,
            WEBSITE,
            EMAIL,
            PHONE_DISPLAY,
            LEGAL_ADDRESS,
            OFFICE_ADDRESS
        )
        MaterialAlertDialogBuilder(context, R.style.MyCustomMaterialAlertDialogStyle)
            .setTitle(R.string.developer_about_title)
            .setMessage(message)
            .setPositiveButton(R.string.developer_open_website) { _, _ ->
                openWebsite(context)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    fun openWebsite(context: Context) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            // No browser — ignore
        }
    }
}
