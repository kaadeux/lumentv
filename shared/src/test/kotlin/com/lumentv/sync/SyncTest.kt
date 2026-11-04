package com.lumentv.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SyncTest {
    @Test fun ratingsMergeAndSpreadTheirRemoval() {
        val k = ProfileMerge.itemKey("pl", "mv", "x:1")
        val tv = ProfileDoc(ratings = mapOf(k to SyncRating("pl", "mv", "x:1", 5, 100, at = 100)))
        val pc = ProfileDoc(ratings = mapOf(k to SyncRating("pl", "mv", "x:1", 2, 200, at = 200)))
        assertEquals(2, ProfileMerge.merge(tv, pc).ratings.getValue(k).stars)
        // The PC took the rating away since the last sync: a tombstone, dated now, that wins on the TV.
        val stamped = ProfileMerge.stampLocal(pc, ProfileDoc(), now = 300)
        assertTrue(stamped.ratings.getValue(k).deleted)
        assertTrue(ProfileMerge.merge(tv, stamped).ratings.getValue(k).deleted)
    }

    @Test fun pbkdf2MatchesRfc7914Vector() {
        val dk = ProfileKeys.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
            "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783", ProfileKeys.hex(dk))
    }

    @Test fun pbkdf2MatchesJdk() {
        val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("une phrase".toCharArray(), "sel".toByteArray(), 1000, 512)).encoded
        assertEquals(ProfileKeys.hex(jdk), ProfileKeys.hex(ProfileKeys.pbkdf2Sha256("une phrase".toByteArray(), "sel".toByteArray(), 1000, 64)))
    }

    @Test fun keysDependOnNameAndPassphrase() {
        val a = ProfileKeys.derive("  Famille  Dupont ", "cheval batterie agrafe", 1000)
        val b = ProfileKeys.derive("famille dupont", "cheval batterie agrafe", 1000)
        val c = ProfileKeys.derive("famille dupont", "cheval batterie agrafX", 1000)
        assertEquals(a.id, b.id); assertEquals(a.token, b.token)
        assertEquals(a.id, c.id); assertNotEquals(a.token, c.token)
        assertFalse(a.encKey.contentEquals(c.encKey))
    }

    @Test fun sealOpensOnlyWithTheRightKey() {
        val k = ProfileKeys.derive("p", "0123456789", 1000)
        val sealed = ProfileCrypto.seal(k, "{\"secret\":\"mot de passe Xtream\"}")
        assertFalse(sealed.contains("secret"))
        assertEquals("{\"secret\":\"mot de passe Xtream\"}", ProfileCrypto.open(k, sealed))
        val wrong = ProfileKeys.derive("p", "0123456780", 1000)
        assertTrue(runCatching { ProfileCrypto.open(wrong, sealed) }.isFailure)
    }

    @Test fun mergeKeepsNewestEntryAndSpreadsRemovals() {
        val a = ProfileDoc(
            favorites = mapOf("pl|ch|1" to SyncFavorite("pl", "ch", "1", on = true, at = 100), "pl|ch|2" to SyncFavorite("pl", "ch", "2", on = false, at = 300)),
            history = mapOf("pl|movie|9" to SyncHistory("pl", "movie", "9", "Film", watchedAt = 10, positionMs = 50_000, at = 200)),
        )
        val b = ProfileDoc(
            favorites = mapOf("pl|ch|1" to SyncFavorite("pl", "ch", "1", on = false, at = 150), "pl|ch|2" to SyncFavorite("pl", "ch", "2", on = true, at = 250)),
            history = mapOf("pl|movie|9" to SyncHistory("pl", "movie", "9", "Film", watchedAt = 20, positionMs = 90_000, at = 400)),
        )
        val m = ProfileMerge.merge(a, b)
        assertFalse(m.favorites["pl|ch|1"]!!.on)      // removed later on b
        assertFalse(m.favorites["pl|ch|2"]!!.on)      // removed later on a
        assertEquals(90_000, m.history["pl|movie|9"]!!.positionMs)
        assertEquals(m, ProfileMerge.merge(b, a))
    }

    @Test fun stampLocalDatesOnlyWhatChanged() {
        val synced = ProfileDoc(
            favorites = mapOf("k1" to SyncFavorite("pl", "ch", "1", at = 5), "k2" to SyncFavorite("pl", "ch", "2", at = 6)),
            settings = mapOf("audio" to SyncValue("fr", 7)),
        )
        val current = ProfileDoc(
            favorites = mapOf("k1" to SyncFavorite("pl", "ch", "1"), "k3" to SyncFavorite("pl", "ch", "3")),
            settings = mapOf("audio" to SyncValue("fr"), "subs" to SyncValue("en")),
        )
        val s = ProfileMerge.stampLocal(synced, current, now = 1000)
        assertEquals(5, s.favorites["k1"]!!.at)
        assertEquals(1000, s.favorites["k3"]!!.at)
        assertFalse(s.favorites["k2"]!!.on); assertEquals(1000, s.favorites["k2"]!!.at)
        assertEquals(7, s.settings["audio"]!!.at)
        assertEquals(1000, s.settings["subs"]!!.at)
    }

    @Test fun playlistKeyIsTheSameOnEveryDevice() {
        assertEquals(ProfileMerge.playlistKey("xtream", "http://Exemple.com:8080/", "user"),
            ProfileMerge.playlistKey("xtream", "http://exemple.com:8080", "user "))
    }

    /** Against a local server: `cd sync-server && npx wrangler dev`, then run with SYNC_TEST_URL=http://127.0.0.1:8787 */
    @Test fun twoDevicesAgainstServer() = runBlocking {
        val url = System.getenv("SYNC_TEST_URL")
        assumeTrue(url != null)
        val sync = ProfileSync(OkHttpClient(), url!!)
        val keys = ProfileKeys.derive("test-${System.nanoTime()}", "phrase de test assez longue", 1000)
        val tv = ProfileDoc(favorites = mapOf("pl|ch|1" to SyncFavorite("pl", "ch", "1", at = 10)))
        val pc = ProfileDoc(history = mapOf("pl|ep|7" to SyncHistory("pl", "ep", "7", "Épisode", watchedAt = 1, positionMs = 42, at = 20)))
        sync.sync(keys, tv)
        val r = sync.sync(keys, pc)
        assertTrue("pl|ch|1" in r.doc.favorites && "pl|ep|7" in r.doc.history)
        assertEquals(r.doc, sync.pull(keys)!!.doc)
        val intruder = ProfileKeys.derive(keys.name, "une autre phrase secrète", 1000)
        assertTrue(runCatching { sync.pull(intruder) }.exceptionOrNull() is WrongPassphraseException)
        sync.delete(keys)
    }
}
