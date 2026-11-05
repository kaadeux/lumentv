package com.lumentv.data.repo

/** Tests and the TV: an M3U from a BufferedReader. */
fun M3uImport.build(playlistId: Long, reader: java.io.BufferedReader, dedupe: Boolean): ImportResult = build(playlistId, reader::readLine, dedupe)
