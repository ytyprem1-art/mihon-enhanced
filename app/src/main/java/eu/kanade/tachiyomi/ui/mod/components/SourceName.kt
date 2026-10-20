package eu.kanade.tachiyomi.ui.mod.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import mihon.app.di.appGraph

@Composable
fun rememberSourceName(sourceId: Long): String {
    val manager = LocalContext.current.appGraph.sourceManager
    val name by produceState(initialValue = "", manager, sourceId) {
        value = manager.getOrStub(sourceId).name
    }
    return name
}
