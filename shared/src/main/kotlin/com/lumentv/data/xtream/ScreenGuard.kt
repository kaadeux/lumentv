package com.lumentv.data.xtream

/**
 * Subscriptions are often limited to one screen. Before a device starts playing, the provider's count of active
 * connections tells whether another device is already watching. This device's own streams are tracked here, so
 * zapping or a stream that just stopped (the provider counts it for a little while longer) do not trigger the warning.
 */
object ScreenGuard {
    /** How long the provider may keep counting a stream after it stopped. */
    private const val GRACE_MS = 90_000L

    @Volatile private var active = 0
    @Volatile private var lastStop = 0L

    @Synchronized fun started() { active++ }
    @Synchronized fun stopped() { if (active > 0) active--; lastStop = System.currentTimeMillis() }

    /** True when this device is playing, or stopped so recently that the provider may still count its stream. */
    fun ownStreamCounted() = active > 0 || System.currentTimeMillis() - lastStop < GRACE_MS

    /** A warning when every allowed screen is already in use, null otherwise (or when the provider does not say). */
    fun warning(account: XtreamAccount): String? {
        val max = account.maxConnections ?: return null
        val used = account.activeConnections ?: return null
        if (max <= 0 || used < max) return null
        val screens = if (max == 1) "un seul écran" else "$max écrans"
        return "Votre abonnement permet $screens et il est déjà utilisé sur un autre appareil ($used/$max). " +
            "Si vous lancez la lecture ici, l'autre appareil risque d'être coupé, ou ce flux d'être refusé."
    }

    const val REFUSED = "Limite d'écrans atteinte : votre abonnement est déjà utilisé sur un autre appareil. Arrêtez la lecture là-bas puis réessayez."
}
