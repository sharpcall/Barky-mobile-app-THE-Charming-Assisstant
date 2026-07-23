package com.example.barky.net

/**
 * Stable contact / peer display names.
 *
 * Titles must stay device identity (Pixel 8, S20, S23) and never flip to
 * "Phone 192.168.x.x" when the peer moves Wi‑Fi ↔ cellular. IP addresses
 * belong only in subtitles / path lines.
 */
object ContactLabels {

    private val IP_ONLY = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val UGLY_PREFIX = Regex("(?i)^(udp|path|inet|peer|lan|phone)[-_\\s]*")
    private val VERSION_SUFFIX = Regex("-v\\d{2,5}$")
    private val PORT_SUFFIX = Regex("-\\d{4}$")

    /**
     * Prefer manufacturer/model fields from Firebase, then any display string.
     * Never returns a bare IP as the title.
     */
    fun stableTitle(
        displayName: String? = null,
        phoneModel: String? = null,
        manufacturer: String? = null,
        fallback: String = "Phone"
    ): String {
        val model = phoneModel?.trim().orEmpty()
        val mfr = manufacturer?.trim().orEmpty()
        val fromFields = when {
            model.isNotBlank() && mfr.isNotBlank() -> {
                if (model.startsWith("Pixel", ignoreCase = true)) model
                else if (model.startsWith("SM-", ignoreCase = true)) "$mfr $model"
                else "$mfr $model"
            }
            model.isNotBlank() -> model
            mfr.isNotBlank() -> mfr
            else -> displayName?.trim().orEmpty()
        }
        val cleaned = cleanRaw(fromFields.ifBlank { displayName.orEmpty() })
        val mapped = mapKnownDevice(cleaned)
        if (mapped != null) return mapped
        if (cleaned.isNotBlank() && !isUglyOrIp(cleaned)) return cleaned
        // Last resort: never use an IP as the visible name
        val fromDisplay = cleanRaw(displayName.orEmpty())
        mapKnownDevice(fromDisplay)?.let { return it }
        if (fromDisplay.isNotBlank() && !isUglyOrIp(fromDisplay)) return fromDisplay
        return fallback
    }

    fun fromEndpoint(ep: PeerEndpoint): String =
        stableTitle(ep.displayName, ep.phoneModel, ep.manufacturer, fallback = "Phone")

    /** Clean WalkiePeer / NSD / HELLO junk into a stable label (never bare IP). */
    fun fromPeerName(raw: String, host: String? = null): String {
        val cleaned = cleanRaw(raw)
        mapKnownDevice(cleaned)?.let { return it }
        if (cleaned.isNotBlank() && !isUglyOrIp(cleaned) && cleaned != host) return cleaned
        return "Phone"
    }

    fun isUglyOrIp(s: String): Boolean {
        val t = s.trim()
        if (t.isBlank()) return true
        if (t.matches(IP_ONLY)) return true
        if (t.startsWith("udp-", ignoreCase = true)) return true
        if (t.startsWith("path-", ignoreCase = true)) return true
        if (t.startsWith("inet-", ignoreCase = true)) return true
        if (t.startsWith("peer-", ignoreCase = true)) return true
        if (t.startsWith("Phone ", ignoreCase = true) && t.contains(Regex("\\d+\\.\\d+"))) return true
        return false
    }

    private fun cleanRaw(raw: String): String {
        var s = raw.trim()
            .removePrefix("Barky-")
            .removePrefix("Barky")
        s = s.replace(VERSION_SUFFIX, "")
        s = s.replace(PORT_SUFFIX, "")
        s = s.replace(UGLY_PREFIX, "")
        s = s.replace(Regex("(?i)^google\\s+"), "")
        s = s.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        // "Pixel8" → "Pixel 8"
        s = s.replace(Regex("(?i)^Pixel(\\d)"), "Pixel $1")
        return s
    }

    /**
     * Map known Barky phones to short stable names the user expects.
     * S20 / S23 / Pixel 8 (and close variants).
     */
    private fun mapKnownDevice(s: String): String? {
        if (s.isBlank()) return null
        val u = s.uppercase()
        // Galaxy S20 family (SM-G980 / G981 / G985 / G986 / G988)
        if (u.contains("SM-G980") || u.contains("SM-G981") || u.contains("SM-G985") ||
            u.contains("SM-G986") || u.contains("SM-G988") ||
            (u.contains("S20") && (u.contains("GALAXY") || u.contains("SAMSUNG") || u.contains("SM-")))
        ) {
            return "S20"
        }
        // Galaxy S23 family (SM-S911 / S916 / S918)
        if (u.contains("SM-S911") || u.contains("SM-S916") || u.contains("SM-S918") ||
            (u.contains("S23") && (u.contains("GALAXY") || u.contains("SAMSUNG") || u.contains("SM-") || u.contains("ULTRA")))
        ) {
            return "S23"
        }
        // Pixel 8 / 8 Pro / 8a
        if (u.contains("PIXEL 8") || u.contains("PIXEL8") || u.contains("SHIBA") ||
            u.contains("HUSKY") || u.contains("AKITA")
        ) {
            return when {
                u.contains("PRO") || u.contains("HUSKY") -> "Pixel 8 Pro"
                u.contains("8A") || u.contains("AKITA") -> "Pixel 8a"
                else -> "Pixel 8"
            }
        }
        if (u.contains("PIXEL 7") || u.contains("PIXEL7")) {
            return if (u.contains("PRO")) "Pixel 7 Pro" else "Pixel 7"
        }
        if (u.contains("PIXEL 9") || u.contains("PIXEL9")) {
            return if (u.contains("PRO")) "Pixel 9 Pro" else "Pixel 9"
        }
        if (Regex("(?i)^PIXEL\\s*\\d").containsMatchIn(s)) {
            return cleanRaw(s)
        }
        return null
    }

    /**
     * Subtitle line: path + LAN/public IPs (name stays separate).
     */
    fun pathSubtitle(
        pathLabel: String,
        targetHost: String,
        localIp: String?,
        publicIp: String?,
        udp: Int,
        videoPort: Int,
        online: Boolean,
        ageMs: Long,
        appVersion: String?
    ): String = buildString {
        append(pathLabel).append(' ').append(targetHost)
        val lan = localIp?.takeIf { it.isNotBlank() && it != targetHost }
        val pub = publicIp?.takeIf { it.isNotBlank() && it != targetHost }
        if (lan != null) append(" · LAN ").append(lan)
        if (pub != null) append(" · public ").append(pub)
        append(" · UDP ").append(udp)
        append(" · video ").append(videoPort)
        if (online) append(" · online")
        else if (ageMs > 0) append(" · last ").append(ageMs / 60_000L).append("m")
        if (!appVersion.isNullOrBlank()) append(" · ").append(appVersion)
    }
}
