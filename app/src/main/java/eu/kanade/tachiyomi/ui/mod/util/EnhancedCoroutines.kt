package eu.kanade.tachiyomi.ui.mod.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Finish an explicitly requested mod write even when its originating screen closes. */
fun CoroutineScope.launchNonCancellable(block: suspend CoroutineScope.() -> Unit) =
    launch(Dispatchers.IO) { withContext(NonCancellable, block) }
