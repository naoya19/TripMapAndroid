package com.patipan.tripmap

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object AppDiagnostics {
    @Suppress("DEPRECATION")
    fun signingSha1(context: Context): String = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val signature = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            packageInfo.signatures?.firstOrNull()
        } ?: return "ไม่พบลายเซ็นแอป"
        MessageDigest.getInstance("SHA-1")
            .digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }.getOrElse { "อ่าน SHA-1 ไม่สำเร็จ: ${it.message}" }
}
