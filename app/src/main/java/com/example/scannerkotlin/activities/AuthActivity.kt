package com.example.scannerkotlin.activities

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.scannerkotlin.R
import com.example.scannerkotlin.dto.UserCredentials
import com.example.scannerkotlin.service.UserService
import com.example.scannerkotlin.utils.AppUserRoles
import com.example.scannerkotlin.utils.DeveloperCredits
import com.example.scannerkotlin.utils.SessionManager
import com.example.scannerkotlin.utils.TsdLogger
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import retrofit2.HttpException

class AuthActivity : AppCompatActivity() {

    private lateinit var userService: UserService
    private val enteredPassword = StringBuilder()
    private lateinit var dotViews: List<ImageView>
    private var isVerifyingPin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (SessionManager.isUserLoggedIn()) {
            val userId = SessionManager.getUserId()
            val userRole = SessionManager.getUserRole()

            if (userRole != null && isSupportedRole(userRole)) {
                TsdLogger.info(
                    category = "AUTH",
                    event = "session_restored",
                    fields = mapOf("userId" to userId, "role" to userRole)
                )
                navigateToActivityBasedOnRole(userRole)
                return
            } else {
                TsdLogger.warn(
                    category = "AUTH",
                    event = "invalid_or_missing_role_cleared",
                    fields = mapOf("userId" to userId, "role" to userRole)
                )
                SessionManager.clearSession()
            }
        }

        setupAuthScreen()
    }

    private fun isSupportedRole(role: String): Boolean {
        val normalized = role.trim().uppercase()
        return normalized == AppUserRoles.storekeeper.uppercase() ||
            normalized == AppUserRoles.otk.uppercase()
    }

    private fun setupAuthScreen() {
        setContentView(R.layout.activity_auth)
        userService = UserService()

        dotViews = listOf(
            findViewById(R.id.dot1),
            findViewById(R.id.dot2),
            findViewById(R.id.dot3),
            findViewById(R.id.dot4)
        )
        setupKeypadButtons()
        findViewById<TextView>(R.id.regiusLabel).setOnClickListener {
            DeveloperCredits.showAboutDialog(this)
        }
    }

    private fun setupKeypadButtons() {
        val buttonIds = listOf(
            R.id.btn1, R.id.btn2, R.id.btn3,
            R.id.btn4, R.id.btn5, R.id.btn6,
            R.id.btn7, R.id.btn8, R.id.btn9,
            R.id.btn0
        )

        buttonIds.forEach { buttonId ->
            findViewById<MaterialButton>(buttonId).setOnClickListener { button ->
                if (isVerifyingPin) return@setOnClickListener
                if (enteredPassword.length < 4) {
                    enteredPassword.append((button as MaterialButton).text)
                    updatePasswordDots()

                    if (enteredPassword.length == 4) {
                        checkPasswordViaApi()
                    }
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            if (isVerifyingPin) return@setOnClickListener
            if (enteredPassword.isNotEmpty()) {
                enteredPassword.deleteCharAt(enteredPassword.length - 1)
                updatePasswordDots()
            }
        }
    }

    private fun updatePasswordDots() {
        dotViews.forEachIndexed { index, imageView ->
            val isFilled = index < enteredPassword.length
            val drawableRes = if (isFilled) R.drawable.circle_filled else R.drawable.circle_outline
            imageView.setImageResource(drawableRes)
        }
    }

    private fun checkPasswordViaApi() {
        if (isVerifyingPin) return
        val enteredPass = enteredPassword.toString()
        isVerifyingPin = true

        lifecycleScope.launch {
            try {
                val userCredentialsList: List<UserCredentials>? = userService.getPasswords(enteredPass)

                if (!userCredentialsList.isNullOrEmpty()) {
                    val user = userCredentialsList.first()
                    if (!isSupportedRole(user.role)) {
                        TsdLogger.warn(
                            category = "AUTH",
                            event = "unsupported_role_rejected",
                            fields = mapOf("userId" to user.id, "role" to user.role)
                        )
                        Toast.makeText(
                            this@AuthActivity,
                            "Неподдерживаемая роль пользователя (${user.role})",
                            Toast.LENGTH_LONG
                        ).show()
                        clearPasswordInput()
                        return@launch
                    }

                    TsdLogger.info(
                        category = "AUTH",
                        event = "login_success",
                        fields = mapOf("userId" to user.id, "role" to user.role)
                    )
                    SessionManager.saveSession(user.id, user.role)
                    navigateToActivityBasedOnRole(user.role)
                } else {
                    TsdLogger.info(category = "AUTH", event = "login_wrong_pin")
                    Toast.makeText(this@AuthActivity, "Неверный пароль", Toast.LENGTH_SHORT).show()
                    clearPasswordInput()
                }
            } catch (e: HttpException) {
                TsdLogger.error(
                    category = "AUTH",
                    event = "login_http_error",
                    fields = mapOf("code" to e.code()),
                    throwable = e
                )
                Toast.makeText(
                    this@AuthActivity,
                    "Ошибка сети (${e.code()}). Попробуйте позже.",
                    Toast.LENGTH_LONG
                ).show()
                clearPasswordInput()
            } catch (e: Exception) {
                TsdLogger.error(category = "AUTH", event = "login_network_error", throwable = e)
                Toast.makeText(
                    this@AuthActivity,
                    "Ошибка при проверке пароля. Проверьте соединение.",
                    Toast.LENGTH_LONG
                ).show()
                clearPasswordInput()
            } finally {
                isVerifyingPin = false
            }
        }
    }

    private fun clearPasswordInput() {
        enteredPassword.clear()
        updatePasswordDots()
    }

    private fun navigateToActivityBasedOnRole(role: String) {
        val intent = when (role.trim().uppercase()) {
            AppUserRoles.storekeeper.uppercase() -> Intent(this, MainActivity::class.java)
            AppUserRoles.otk.uppercase() -> Intent(this, OtkActivity::class.java)
            else -> {
                SessionManager.clearSession()
                return
            }
        }
        startActivity(intent)
        finishAffinity()
    }
}
