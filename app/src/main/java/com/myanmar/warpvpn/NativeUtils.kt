package com.myanmar.warpvpn
import android.content.Context

object NativeUtils {

    init {
        System.loadLibrary("warpvpn")
    }
    external fun verifyAppSignature(context: Context): Boolean
    external fun validateLicenseNative(expireDateMillis: Long, isActivated: Boolean): Boolean
    external fun getCustomApiUrl(): String
    external fun getCfApiBase1(): String
    external fun getCfApiBase2(): String
    external fun getCfApiBase3(): String
}
