package com.example.scannerkotlin.debug

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.scannerkotlin.activities.DocumentComingActivity
import com.example.scannerkotlin.activities.DocumentMovingActivity
import com.example.scannerkotlin.activities.MainActivity
import com.example.scannerkotlin.activities.OtkActivity
import com.example.scannerkotlin.activities.ProductsDocumentMovingActivity
import com.example.scannerkotlin.activities.ScanActivity
import com.example.scannerkotlin.activities.SgpActivity
import com.example.scannerkotlin.utils.SessionManager

/**
 * Debug-only entry to open non-exported screens for demo screenshots.
 * Extra: screen = auth|main|coming-list|moving-list|scan|sgp|otk|moving-products
 */
class DemoScreenFixtureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screen = intent.getStringExtra(EXTRA_SCREEN).orEmpty()
        val role = intent.getStringExtra(EXTRA_ROLE) ?: "75"

        when (screen) {
            "auth" -> {
                SessionManager.clearSession()
                // relaunch launcher Auth via finish of this after start
                startActivity(
                    Intent(this, com.example.scannerkotlin.activities.AuthActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
            "main" -> {
                SessionManager.saveSession("1", role)
                startActivity(Intent(this, MainActivity::class.java))
            }
            "coming-list" -> {
                SessionManager.saveSession("1", "75")
                startActivity(Intent(this, DocumentComingActivity::class.java))
            }
            "moving-list" -> {
                SessionManager.saveSession("1", "75")
                startActivity(Intent(this, DocumentMovingActivity::class.java))
            }
            "scan" -> {
                SessionManager.saveSession("1", "75")
                startActivity(Intent(this, ScanActivity::class.java))
            }
            "sgp" -> {
                SessionManager.saveSession("1", "75")
                startActivity(Intent(this, SgpActivity::class.java))
            }
            "otk" -> {
                SessionManager.saveSession("1", "76")
                startActivity(Intent(this, OtkActivity::class.java))
            }
            "moving-products" -> {
                SessionManager.saveSession("1", "75")
                startActivity(
                    Intent(this, ProductsDocumentMovingActivity::class.java).apply {
                        putExtra("idDocument", intent.getStringExtra("idDocument") ?: "900002")
                        putExtra("title", intent.getStringExtra("title") ?: "Перемещение №demo")
                    }
                )
            }
            else -> {
                SessionManager.saveSession("1", "75")
                startActivity(Intent(this, MainActivity::class.java))
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_ROLE = "role"
    }
}
