@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)

package com.lumentv.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import kotlin.io.encoding.Base64

/**
 * iPhone: a random 256-bit key kept in the Keychain (this device only, readable after the first unlock), AES-GCM
 * from the shared code. Same stored format as Android's.
 */
actual object SecretBox {
    private const val SERVICE = "com.lumentv.secrets"
    private const val ACCOUNT = "secrets_v1"
    private val lock = SynchronizedObject()
    private var cachedKey: ByteArray? = null

    actual fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val key = key() ?: return "p:$plain"
        val iv = Crypto.randomBytes(12)
        return "k1:" + Base64.encode(iv + Crypto.aesGcmSeal(key, iv, ByteArray(0), plain.encodeToByteArray()))
    }

    actual fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (stored.startsWith("p:")) return stored.substring(2)
        if (!stored.startsWith("k1:")) return stored
        return try {
            val raw = Base64.decode(stored.substring(3))
            val key = key() ?: return ""
            Crypto.aesGcmOpen(key, raw.copyOfRange(0, 12), ByteArray(0), raw.copyOfRange(12, raw.size)).decodeToString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun key(): ByteArray? = synchronized(lock) {
        cachedKey ?: (read() ?: Crypto.randomBytes(32).takeIf { store(it) })?.also { cachedKey = it }
    }

    /** A query on our Keychain item, with [extra] entries; released after [use]. */
    private fun <R> withQuery(extra: (CFMutableDictionaryRef?) -> Unit, use: (CFMutableDictionaryRef?) -> R): R {
        val dict = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val service = CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8)
        val account = CFStringCreateWithCString(null, ACCOUNT, kCFStringEncodingUTF8)
        try {
            CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dict, kSecAttrService, service)
            CFDictionaryAddValue(dict, kSecAttrAccount, account)
            extra(dict)
            return use(dict)
        } finally {
            CFRelease(service); CFRelease(account); CFRelease(dict)
        }
    }

    private fun read(): ByteArray? = memScoped {
        val out = alloc<CFTypeRefVar>()
        val status = withQuery({ d ->
            CFDictionaryAddValue(d, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(d, kSecMatchLimit, kSecMatchLimitOne)
        }) { d -> SecItemCopyMatching(d, out.ptr) }
        val data: CFDataRef = out.value?.reinterpret() ?: return@memScoped null
        try {
            if (status != errSecSuccess) return@memScoped null
            val len = CFDataGetLength(data).toInt()
            val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
            ByteArray(len) { bytes[it].toByte() }.takeIf { it.size == 32 }
        } finally {
            CFRelease(data)
        }
    }

    private fun store(key: ByteArray): Boolean {
        val data = key.asUByteArray().usePinned { CFDataCreate(null, it.addressOf(0), key.size.convert()) }
        try {
            return withQuery({ d ->
                CFDictionaryAddValue(d, kSecValueData, data)
                CFDictionaryAddValue(d, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            }) { d -> SecItemAdd(d, null) } == errSecSuccess
        } finally {
            CFRelease(data)
        }
    }
}
