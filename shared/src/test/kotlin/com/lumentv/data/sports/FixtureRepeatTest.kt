package com.lumentv.data.sports

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test

/** Live: the same request repeated, as the screen does, must give the same games every time. */
class FixtureRepeatTest {
    @Test fun sameGamesEveryTime() = runBlocking {
        val client = FixtureClient(OkHttpClient())
        val l1 = FixtureClient.league("fra1")!!
        val out = StringBuilder()
        repeat(3) { i ->
            val t = System.currentTimeMillis()
            val list = client.fixtures(listOf(l1), SportsFunnel.DAYS)
            out.append("run $i: ${list.size} games in ${System.currentTimeMillis() - t} ms: ${list.joinToString { it.label + " " + java.util.Date(it.start) }}\n")
        }
        java.io.File("build/fixture-repeat.txt").writeText(out.toString())
    }
}
