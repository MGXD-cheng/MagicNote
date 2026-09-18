package com.magicnote.mgxd.util

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 日记锁：数字密码哈希 + 系统设备锁（指纹 / 人脸 / 设备密码）验证
 *
 * - 密码不存明文：每次生成随机盐，存 SHA-256(salt:password)
 * - 生物识别走系统设备锁（KeyguardManager.createConfirmDeviceCredentialIntent），
 *   指纹 / 人脸 / PIN / 图案 / 密码 都可用，零第三方依赖，全机型兼容
 */
object DiaryLock {

    /** 生成 16 字节随机盐（hex 字符串） */
    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 密码哈希：SHA-256(salt:password) 的 hex */
    fun hash(password: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest("$salt:$password".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 密码规则：4~6 位数字 */
    fun isValidPassword(pwd: String): Boolean =
        pwd.length in 4..6 && pwd.all { it.isDigit() }

    /** 设备是否设置过锁屏（PIN / 密码 / 图案）；设置后指纹、人脸也可用 */
    fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return false
        return km.isDeviceSecure
    }

    /** 系统验证界面（指纹 / 人脸 / 设备密码均可）；不可用时返回 null */
    fun deviceCredentialIntent(context: Context, title: String = "解锁日记"): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return null
        return km.createConfirmDeviceCredentialIntent(title, "验证通过后即可查看日记")
    }
}