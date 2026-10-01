package com.freebuff.barrage.filter

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Charge la liste de blocage (fichier mis à jour, sinon asset embarqué) et
 * peut la remplacer par une liste distante (gzip ou texte brut, formats hosts /
 * AdGuard / domaine simple acceptés).
 */
object BlocklistRepository {

    /** Liste par défaut : Steven Black « porn » (hosts, plusieurs Mo). */
    const val DEFAULT_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn/hosts"

    private const val FILE_NAME = "blocklist.txt"
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MIN_ENTRIES = 100

    /** Charge la liste locale (fichier mis à jour en priorité, sinon asset). */
    fun load(context: Context): DomainBlocklist {
        val list = DomainBlocklist()
        val local = File(context.filesDir, FILE_NAME)
        if (local.exists()) {
            local.bufferedReader().use { reader -> list.load(reader.lineSequence()) }
        } else {
            context.assets.open("blocklist.txt").bufferedReader().use { reader ->
                list.load(reader.lineSequence())
            }
        }
        return list
    }

    /** Compte les entrées sans construire la liste en mémoire (affichage UI). */
    fun countEntries(context: Context): Int {
        val list = DomainBlocklist()
        val local = File(context.filesDir, FILE_NAME)
        val stream = if (local.exists()) local.inputStream() else context.assets.open("blocklist.txt")
        stream.bufferedReader().use { reader -> list.load(reader.lineSequence()) }
        return list.size
    }

    data class UpdateResult(val entries: Int, val error: String? = null)

    /**
     * Télécharge la liste depuis [url] en arrière-plan, l'écrit sur disque puis
     * appelle [onDone] (thread background : à repasser sur l'UI).
     */
    fun update(context: Context, url: String, onDone: (UpdateResult) -> Unit) {
        Thread({
            val result = try {
                download(context, url)
            } catch (e: Exception) {
                UpdateResult(0, e.message ?: e.javaClass.simpleName)
            }
            onDone(result)
        }, "barrage-update").start()
    }

    private fun download(context: Context, url: String): UpdateResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return UpdateResult(0, "HTTP $code")

            var stream: InputStream = BufferedInputStream(conn.inputStream, 64 * 1024)
            // Détection gzip (magic bytes 1f 8b)
            stream.mark(2)
            val b1 = stream.read()
            val b2 = stream.read()
            stream.reset()
            if (b1 == 0x1F && b2 == 0x8B) stream = GZIPInputStream(stream)

            val list = DomainBlocklist()
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            var bytes = 0L
            stream.bufferedReader().use { reader ->
                tmp.bufferedWriter().use { writer ->
                    for (line in reader.lineSequence()) {
                        list.addLine(line)
                        writer.write(line)
                        writer.write('\n'.code)
                        bytes += line.length + 1
                        if (bytes > MAX_FILE_BYTES) throw IllegalStateException("liste trop volumineuse")
                    }
                }
            }

            if (list.size < MIN_ENTRIES) {
                tmp.delete()
                return UpdateResult(0, "liste trop petite (${list.size} entrées)")
            }

            val dest = File(context.filesDir, FILE_NAME)
            if (dest.exists() && !dest.delete()) {
                tmp.delete()
                return UpdateResult(0, "remplacement impossible")
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return UpdateResult(0, "écriture impossible")
            }
            return UpdateResult(list.size)
        } finally {
            conn.disconnect()
        }
    }
}
