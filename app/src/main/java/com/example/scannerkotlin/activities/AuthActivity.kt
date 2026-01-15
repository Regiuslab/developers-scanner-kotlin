package com.example.scannerkotlin.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.scannerkotlin.R
import com.example.scannerkotlin.dto.UserCredentials
import com.example.scannerkotlin.utils.SessionManager
import com.example.scannerkotlin.service.UserService
import com.example.scannerkotlin.utils.AppUserRoles
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException




class AuthActivity : AppCompatActivity() {

    private lateinit var userService: UserService
    private val enteredPassword = StringBuilder()
    private lateinit var dotViews: List<ImageView>
    private val authScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        if (SessionManager.isUserLoggedIn()) {
            val userId = SessionManager.getUserId()
            val userRole = SessionManager.getUserRole()

            if (userRole != null) {
                Log.i("AuthActivity", "User already logged in (userId: $userId, role: $userRole). Redirecting.")
                navigateToActivityBasedOnRole(userRole)

                return
            } else {
                Log.w("AuthActivity", "User logged in (userId: $userId) but role is missing. Clearing session.")
                SessionManager.clearSession()
            }
        }


        setupAuthScreen()
    }

    private fun setupAuthScreen() {
        setContentView(R.layout.activity_auth)
        Log.i("AuthActivity", "Setting up authentication screen.")

        userService = UserService()

        dotViews = listOf(
            findViewById(R.id.dot1),
            findViewById(R.id.dot2),
            findViewById(R.id.dot3),
            findViewById(R.id.dot4)
        )
        setupKeypadButtons()
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
        val enteredPass = enteredPassword.toString()
        Log.d("AuthActivity", "Attempting login with password: $enteredPass")


        authScope.launch {
            try {
                val userCredentialsList: List<UserCredentials>? = userService.getPasswords(enteredPass)


                if (!userCredentialsList.isNullOrEmpty()) {

                    val user = userCredentialsList.first()

                    Log.i("AuthActivity", "API check successful for userId: ${user.id}, role: ${user.role}. Saving session.")


                    SessionManager.saveSession(user.id, user.role)


                    navigateToActivityBasedOnRole(user.role)

                } else {
                    Log.w("AuthActivity", "Incorrect password entered (API check).")
                    Toast.makeText(this@AuthActivity, "Неверный пароль", Toast.LENGTH_SHORT).show()
                    clearPasswordInput()
                }

            } catch (e: HttpException) {

                Log.e("AuthActivity", "API HTTP Error during password check", e)
                Toast.makeText(this@AuthActivity, "Ошибка сети (${e.code()}). Попробуйте позже.", Toast.LENGTH_LONG).show()
                clearPasswordInput()
            } catch (e: Exception) {

                Log.e("AuthActivity", "API Error during password check", e)
                Toast.makeText(this@AuthActivity, "Ошибка при проверке пароля. Проверьте соединение.", Toast.LENGTH_LONG).show()
                clearPasswordInput()
            }
        }
    }

    private fun clearPasswordInput() {
        enteredPassword.clear()
        updatePasswordDots()
    }

    private fun navigateToActivityBasedOnRole(role: String) {
        Log.d("AuthActivity", "Navigating based on role: $role")
        val intent = when (role.uppercase()) {
            AppUserRoles.storekeeper.uppercase() -> {
                Log.i("AuthActivity", "Redirecting to AdminActivity")
                Intent(this, MainActivity::class.java)
            }
            AppUserRoles.otk.uppercase() -> {
                Log.i("AuthActivity", "Redirecting to MontagActivity")
                Intent(this, OtkActivity::class.java)
            }

            else -> {
                Log.w("AuthActivity", "Unknown or default role: $role. Redirecting to MainActivity.")
                Intent(this, AuthActivity::class.java)
            }
        }

        startActivity(intent)
        finishAffinity()
    }

    override fun onDestroy() {
        super.onDestroy()
        authScope.cancel()
    }
}