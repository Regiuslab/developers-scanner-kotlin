package com.example.scannerkotlin.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

object SessionManager {

    private const val PREFS_NAME = "AppSessionPrefs"
    private const val KEY_USER_ID = "userId"
    private const val KEY_USER_ROLE = "userRole"

    @Volatile
    private var _backingSharedPreferences: SharedPreferences? = null

    private val sharedPreferences: SharedPreferences
        get() = _backingSharedPreferences ?: throw IllegalStateException(
            "SessionManager not initialized. Call SessionManager.init(context) in your Application class."
        )

    @Volatile
    private var currentUserId: String? = null
    @Volatile
    private var currentUserRole: String? = null


    fun init(context: Context) {
        if (_backingSharedPreferences == null) {
            synchronized(this) {
                if (_backingSharedPreferences == null) {
                    _backingSharedPreferences = context.applicationContext.getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                    currentUserId = _backingSharedPreferences?.getString(KEY_USER_ID, null)
                    currentUserRole = _backingSharedPreferences?.getString(KEY_USER_ROLE, null) // Загружаем роль
                    Log.i("SessionManager", "SessionManager initialized. UserID: $currentUserId, Role: $currentUserRole")
                }
            }
        }
    }


    fun saveSession(userId: String, userRole: String) {
        currentUserId = userId
        this.currentUserRole = userRole

        try {
            sharedPreferences.edit {
                putString(KEY_USER_ID, userId)
                putString(KEY_USER_ROLE, userRole)
            }
            Log.i("SessionManager", "Session saved. UserID: $userId, Role: $userRole")
        } catch (e: IllegalStateException) {
            Log.e("SessionManager", "Error saving session: ${e.message}", e)
        }
    }


    fun getUserId(): String? {
        if (currentUserId == null && _backingSharedPreferences != null) {
            Log.w("SessionManager", "currentUserId was null, attempting to reload from SharedPreferences.")
            currentUserId = _backingSharedPreferences?.getString(KEY_USER_ID, null)
        }
        return currentUserId
    }


    fun getUserRole(): String? {
        if (currentUserRole == null && _backingSharedPreferences != null) {
            Log.w("SessionManager", "currentUserRole was null, attempting to reload from SharedPreferences.")
            currentUserRole = _backingSharedPreferences?.getString(KEY_USER_ROLE, null)
        }
        return currentUserRole
    }


    fun requireUserId(): String {
        return getUserId() ?: throw IllegalStateException(
            "User ID is required but was null. Ensure user is logged in and SessionManager is initialized."
        )
    }


    fun requireUserRole(): String {
        return getUserRole() ?: throw IllegalStateException(
            "User Role is required but was null. Ensure user is logged in and role is saved."
        )
    }



    fun isUserLoggedIn(): Boolean {
        return getUserId() != null
    }


    fun clearSession() {
        val oldUserId = currentUserId
        val oldUserRole = currentUserRole
        currentUserId = null
        currentUserRole = null

        try {
            sharedPreferences.edit {
                remove(KEY_USER_ID)
                remove(KEY_USER_ROLE)
            }
            Log.i("SessionManager", "Session cleared. Was UserID: $oldUserId, Role: $oldUserRole")
        } catch (e: IllegalStateException) {
            Log.e("SessionManager", "Error clearing session: ${e.message}", e)
        }
    }
}