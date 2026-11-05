package com.lumentv.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** TV and phone: a sync when the app comes to the foreground and when it leaves it, then [ProfileManager.start]. */
fun ProfileManager.startWithAppLifecycle(scope: CoroutineScope) {
    if (!enabled) return
    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { scope.launch { syncNow() } }
        override fun onStop(owner: LifecycleOwner) { scope.launch { syncNow() } }
    })
    start()
}
