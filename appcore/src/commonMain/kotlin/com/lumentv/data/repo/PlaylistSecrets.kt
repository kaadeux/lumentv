package com.lumentv.data.repo

import com.lumentv.core.SecretBox
import com.lumentv.core.Secrets
import com.lumentv.data.db.PlaylistEntity

/** A list whose login or addresses are kept in clear (the device's key store refused when it was saved). */
fun PlaylistEntity.secretsInClear() = Secrets.inClear(sourceEnc, passwordEnc, epgUrlEnc, autoEpgUrlEnc)

/** The same list with its secrets encrypted again (once the key store works). */
fun PlaylistEntity.reEncrypted() = copy(
    sourceEnc = SecretBox.encrypt(SecretBox.decrypt(sourceEnc)), passwordEnc = SecretBox.encrypt(SecretBox.decrypt(passwordEnc)),
    epgUrlEnc = SecretBox.encrypt(SecretBox.decrypt(epgUrlEnc)), autoEpgUrlEnc = SecretBox.encrypt(SecretBox.decrypt(autoEpgUrlEnc)),
)
