package eu.kanade.tachiyomi.util.system

import android.util.Base64
import java.security.SecureRandom

object PkceUtil {

    fun generateCodeVerifier(): String {
        val codeVerifier = ByteArray(50)
        SecureRandom().nextBytes(codeVerifier)
        return Base64.encodeToString(codeVerifier, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
