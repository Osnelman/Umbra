package com.freebuff.barrage.filter

/**
 * Liste de blocage : domaines exacts (et leurs sous-domaines), TLD entiers,
 * mots-clés dans le nom de domaine, exceptions.
 *
 * Formats acceptés (une règle par ligne) :
 *  - domaine simple         : `pornhub.com`
 *  - TLD entier             : `xxx`          (bloque tout *.xxx)
 *  - format hosts           : `0.0.0.0 pornhub.com`
 *  - AdGuard / EasyList     : `||pornhub.com^$third-party`
 *  - exception              : `@@exemple.com` ou `||exemple.com^`
 *  - commentaires           : lignes `!`, `#` ou `/regex/` ignorées
 *
 * Thread-safety : l'instance n'est mutée que pendant le chargement ; elle est
 * ensuite publiée via une référence volatile (le service VPN en crée une neuve
 * à chaque rechargement).
 */
class DomainBlocklist {

    private val domains = HashSet<String>()
    private val allowed = HashSet<String>()

    val size: Int get() = domains.size
    val allowedSize: Int get() = allowed.size

    fun load(lines: Sequence<String>) {
        lines.forEach { addLine(it) }
    }

    fun addLine(raw: String) {
        var line = raw.trim()
        if (line.isEmpty()) return
        if (line[0] == '!' || line[0] == '#') return
        if (line[0] == '/') return // règle regex : non supportée

        var allow = false
        if (line.startsWith("@@")) {
            allow = true
            line = line.substring(2).trim()
            if (line.isEmpty()) return
        }

        // Format hosts ou ligne avec commentaire : on garde le premier champ,
        // sauf si le premier champ est une IP (alors le domaine est le second).
        if (line.any { it.isWhitespace() }) {
            val parts = line.split(Regex("\\s+"))
            val first = parts[0]
            line = if (parts.size >= 2 && looksLikeIp(first)) parts[1] else first
        }

        // Ancrage AdGuard / EasyList
        if (line.startsWith("||")) line = line.substring(2)

        // Coupe aux modificateurs : ^ $ / | # (ex. `domain.com^$third-party`)
        val cut = line.indexOfFirst { it == '^' || it == '$' || it == '/' || it == '|' || it == '#' }
        if (cut >= 0) line = line.substring(0, cut)

        line = line.trim().trimEnd('.').lowercase()
        if (line.isEmpty()) return
        if (!isValidDomain(line)) return

        if (allow) allowed.add(line) else domains.add(line)
    }

    /**
     * True si le nom de domaine doit être bloqué.
     * Priorité : exception (`@@`) > mots-clés > liste de domaines.
     */
    fun isBlocked(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false

        // Exceptions : ni le domaine ni ses sous-domaines ne sont bloqués
        var candidate = h
        while (true) {
            if (candidate in allowed) return false
            val dot = candidate.indexOf('.')
            if (dot < 0) break
            candidate = candidate.substring(dot + 1)
        }

        // Mots-clés larges : présents n'importe où dans le nom
        if (KEYWORDS.any { h.contains(it) }) return true

        // Mots-clés stricts : en début de label uniquement
        // (« sussex », « essex » ne déclenchent pas « sex »)
        if (h.split('.').any { label -> STRICT_KEYWORDS.any { label == it || label.startsWith(it) } }) {
            return true
        }

        // Domaines connus : on remonte les parents (sub.a.b.com → b.com)
        candidate = h
        while (true) {
            if (candidate in domains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) break
            candidate = candidate.substring(dot + 1)
        }
        return false
    }

    private fun looksLikeIp(s: String): Boolean =
        s.isNotEmpty() && s.all { it in "0123456789.:" }

    private fun isValidDomain(d: String): Boolean {
        if (d.length > 253) return false
        if (!d.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }) return false
        val labels = d.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        if (labels.any { it.startsWith("-") || it.endsWith("-") }) return false
        // Rejet des adresses IP (inutiles comme entrées)
        if (labels.all { it.all { c -> c in '0'..'9' } }) return false
        return true
    }

    companion object {
        /** Sous-chaînes bloquées dès qu'elles apparaissent dans le nom de domaine. */
        val KEYWORDS = listOf(
            "porn", "porno", "xxx", "xnxx", "xvideos", "xhamster", "onlyfans",
            "hentai", "youporn", "redtube", "spankbang", "porntube", "brazzers",
            "bangbros", "rule34", "camgirl", "nsfw", "blowjob", "fetish", "nude",
            "escort"
        )

        /** Mots-clés acceptés uniquement en début de label (évite les faux positifs). */
        val STRICT_KEYWORDS = listOf("sex")
    }
}
