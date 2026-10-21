package eu.kanade.tachiyomi.ui.mod.appupdate

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import eu.kanade.tachiyomi.ui.mod.panelshare.awaitPanelTask

object ModUpdateStore {
    // Keep password proofs out of Firestore's on-disk cache without changing chat caching.
    private val publisherApp by lazy {
        val original = FirebaseApp.getInstance()
        FirebaseApp.getApps(original.applicationContext).firstOrNull { it.name == "mod-update-publisher" }
            ?: FirebaseApp.initializeApp(original.applicationContext, original.options, "mod-update-publisher")
    }
    private val publisherDb by lazy {
        FirebaseFirestore.getInstance(publisherApp).apply {
            firestoreSettings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(com.google.firebase.firestore.MemoryCacheSettings.newBuilder().build()).build()
        }
    }
    private suspend fun uid(): String = FirebaseAuth.getInstance(publisherApp).let { auth ->
        auth.currentUser?.uid ?: requireNotNull(auth.signInAnonymously().awaitPanelTask().user?.uid)
    }

    fun passwordHash(password: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(password.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Only the proof document receives the hash. It is never placed in public release metadata. */
    suspend fun unlock(password: String): String {
        require(password.isNotEmpty()) { "Masukkan password admin" }
        val hash = passwordHash(password)
        try {
            publisherDb.collection("mod_update_proofs").document(uid()).set(
                mapOf("pinHash" to hash, "at" to FieldValue.serverTimestamp()),
            ).awaitPanelTask()
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                error("Password salah atau admin update belum diaktifkan di server.")
            }
            throw e
        }
        return hash
    }
    suspend fun latest(installed: String? = null): ModRelease? {
        val doc = FirebaseFirestore.getInstance().collection("mod_updates").document("stable").get(Source.SERVER).awaitPanelTask()
        if (!doc.exists()) return null
        val version = requireNotNull(doc.getString("version"))
        val fileId = requireNotNull(doc.getString("fileId"))
        require(ModVersion.parse(version) != null && fileId.matches(Regex("[A-Za-z0-9_-]{10,150}"))) { "Invalid update information" }
        val release = ModRelease(version, fileId, doc.getString("notes").orEmpty()).also {
            require(it.number == doc.getLong("releaseNumber")) { "Invalid update version" }
        }
        if (installed == null || !release.newerThan(installed)) return release
        return try {
            val current = requireNotNull(ModVersion.parse(installed)).number
            val history = FirebaseFirestore.getInstance().collection("mod_update_releases")
                .whereGreaterThan("releaseNumber", current).whereLessThanOrEqualTo("releaseNumber", release.number)
                .orderBy("releaseNumber").get(Source.SERVER).awaitPanelTask()
            val notes = history.documents.mapNotNull { entry ->
                val name = entry.getString("version") ?: return@mapNotNull null
                if (ModVersion.parse(name)?.number != entry.getLong("releaseNumber")) return@mapNotNull null
                ModReleaseNote(name, entry.getString("notes").orEmpty())
            }
            release.copy(history = release.notesSince(installed, notes))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { release.copy(historyAvailable = false) }
    }

    suspend fun publish(release: ModRelease, pinHash: String) {
        require(release.notes.length <= 4000)
        val db = publisherDb
        val data = mapOf("version" to release.version, "fileId" to release.fileId, "notes" to release.notes,
            "releaseNumber" to release.number, "publishedAt" to FieldValue.serverTimestamp())
        val batch = db.batch()
        batch.set(db.collection("mod_update_proofs").document(uid()),
            data + mapOf("pinHash" to pinHash, "at" to FieldValue.serverTimestamp()))
        batch.set(db.collection("mod_updates").document("stable"), data)
        batch.set(db.collection("mod_update_releases").document(release.version), data)
        batch.commit().awaitPanelTask()
    }
}
