package com.example.scannerkotlin.utils

import com.example.scannerkotlin.config.BitrixConfig

object AppUserRoles {
    val storekeeper: String get() = BitrixConfig.roleStorekeeper
    val otk: String get() = BitrixConfig.roleOtk
}
