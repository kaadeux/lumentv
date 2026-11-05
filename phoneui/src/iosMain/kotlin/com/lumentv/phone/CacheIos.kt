package com.lumentv.phone

import platform.Foundation.NSURLCache

actual object PlatformCache {
    actual fun sizeBytes(): Long = NSURLCache.sharedURLCache.currentDiskUsage.toLong()
    actual fun clear() = NSURLCache.sharedURLCache.removeAllCachedResponses()
}
