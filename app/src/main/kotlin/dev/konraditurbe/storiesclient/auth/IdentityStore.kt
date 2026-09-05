package dev.konraditurbe.storiesclient.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Owner-identity store: the two per-owner secrets the Phase-B handshake needs (the app RSA-2048 private
 * key, PKCS#8/Base64, and a bootstrap resume ticket) plus the rotating session ticket. Backed by
 * SharedPreferences so a once-imported identity survives app restarts.
 *
 * Nothing sensitive ships in the APK: the user imports a `*_glasses.json` config (EXTRACT_SECRETS_CONFIG §5)
 * via "Load keys…", which calls [save].
 */
object IdentityStore {
    private const val PREFS = "storiesclient_session"
    private const val K_RSA = "identity_rsa_b64"                   // PKCS#8, Base64
    private const val K_BOOTSTRAP = "identity_bootstrap_ticket_hex" // 32-hex seed ticket
    private const val K_SERIAL = "identity_serial"
    private const val K_LABEL = "identity_label"
    internal const val K_RESUME = "resume_ticket"                    // rotating session ticket (PhaseB writes it)

    private val HEX32 = Regex("[0-9a-f]{32}")

    internal fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once a usable identity (RSA key + 32-hex bootstrap ticket) has been imported. */
    fun isConfigured(c: Context): Boolean {
        val p = prefs(c)
        return !p.getString(K_RSA, null).isNullOrEmpty() && p.getString(K_BOOTSTRAP, null)?.length == 32
    }

    /**
     * Import an identity. Validates lightly, persists, and drops the previous glasses' stale rotating
     * resume ticket so the next session bootstraps from this config's ticket. (A stale rotated ticket
     * replayed on StartSession makes the glasses disconnect with reason 0x13.)
     *
     * @throws IllegalArgumentException if the RSA Base64 or the 32-hex ticket is missing/malformed.
     */
    fun save(c: Context, rsaB64: String?, bootstrapTicketHex: String?, serial: String?, label: String?) {
        val rsa = rsaB64?.trim().orEmpty()
        require(rsa.isNotEmpty()) { "app_rsa_priv_pkcs8_b64 missing" }
        val ticket = bootstrapTicketHex?.trim()?.lowercase().orEmpty()
        require(HEX32.matches(ticket)) { "bootstrap_ticket_hex must be 32 hex chars" }
        prefs(c).edit {
            putString(K_RSA, rsa)
            putString(K_BOOTSTRAP, ticket)
            putString(K_SERIAL, serial?.trim().orEmpty())
            putString(K_LABEL, label?.trim().orEmpty())
            remove(K_RESUME)
        }
    }

    /** Wipe all identity + session material ("forget glasses"). */
    fun clear(c: Context) {
        prefs(c).edit {
            remove(K_RSA); remove(K_BOOTSTRAP); remove(K_SERIAL); remove(K_LABEL); remove(K_RESUME)
        }
    }

    fun rsaB64(c: Context): String? = prefs(c).getString(K_RSA, null)
    fun bootstrapHex(c: Context): String? = prefs(c).getString(K_BOOTSTRAP, null)
    fun serial(c: Context): String = prefs(c).getString(K_SERIAL, "") ?: ""
    fun label(c: Context): String = prefs(c).getString(K_LABEL, "") ?: ""

    /** Persisted rotating resume ticket (32 hex), or null. */
    fun resumeTicketHex(c: Context): String? = prefs(c).getString(K_RESUME, null)?.takeIf { it.length == 32 }

    fun saveResumeTicketHex(c: Context, hex: String) = prefs(c).edit { putString(K_RESUME, hex) }
}
