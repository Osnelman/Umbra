package com.freebuff.barrage

import android.content.Context
import com.freebuff.barrage.filter.BlocklistRepository

/** Préférences de l'app : verrou binaire, statistiques, liste de blocage. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("barrage", Context.MODE_PRIVATE)

    /** Hachage SHA-256 du code binaire (le code lui-même n'est jamais stocké). */
    var codeHash: String?
        get() = sp.getString("code_hash", null)
        set(value) {
            sp.edit().putString("code_hash", value).apply()
        }

    /** Longueur du code binaire (affichée dans le dialogue de déverrouillage). */
    var codeLength: Int
        get() = sp.getInt("code_len", 0)
        set(value) {
            sp.edit().putInt("code_len", value).apply()
        }

    /** URL de la liste de blocage mise à jour. */
    var listUrl: String
        get() = sp.getString("list_url", BlocklistRepository.DEFAULT_URL) ?: BlocklistRepository.DEFAULT_URL
        set(value) {
            sp.edit().putString("list_url", value).apply()
        }

    /** Nombre d'entrées de la liste actuellement chargée. */
    var listSize: Int
        get() = sp.getInt("list_size", 0)
        set(value) {
            sp.edit().putInt("list_size", value).apply()
        }

    /** La protection est-elle voulue active ? (relance au reboot) */
    var protectionWanted: Boolean
        get() = sp.getBoolean("protection_wanted", false)
        set(value) {
            sp.edit().putBoolean("protection_wanted", value).apply()
        }

    /** Nombre total de requêtes bloquées depuis l'installation. */
    val blockedTotal: Int
        get() = sp.getInt("blocked_total", 0)

    /** Enregistre un blocage (compteur + historique des 40 derniers). */
    fun recordBlock(host: String) {
        val recent = (sp.getString("recent", "") ?: "")
            .split('\n')
            .filter { it.isNotEmpty() }
            .toMutableList()
        recent.add(0, host)
        if (recent.size > RECENT_CAP) recent.subList(RECENT_CAP, recent.size).clear()
        sp.edit()
            .putString("recent", recent.joinToString("\n"))
            .putInt("blocked_total", blockedTotal + 1)
            .apply()
    }

    fun recentBlocks(): List<String> =
        (sp.getString("recent", "") ?: "").split('\n').filter { it.isNotEmpty() }

    private companion object {
        const val RECENT_CAP = 40
    }
}
