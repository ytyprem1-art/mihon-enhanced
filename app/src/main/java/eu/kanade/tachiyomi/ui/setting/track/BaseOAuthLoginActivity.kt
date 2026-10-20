package eu.kanade.tachiyomi.ui.setting.track

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import mihon.app.di.appGraph
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Abstract base activity for OAuth-based login flows.
 * Handles the common OAuth callback processing.
 */
@dev.zacsweers.metro.HasMemberInjections
abstract class BaseOAuthLoginActivity : BaseActivity() {

    @Inject protected lateinit var trackerManager: TrackerManager

    /**
     * Handle the result from the OAuth callback.
     */
    abstract fun handleResult(uri: Uri?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appGraph.inject(this)

        setComposeContent {
            LoadingScreen()
        }

        val data = intent.data
        if (data == null) {
            returnToSettings()
        } else {
            handleResult(data)
        }
    }

    /**
     * Returns to the main activity and finishes this one.
     */
    internal fun returnToSettings() {
        finish()

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }
}
