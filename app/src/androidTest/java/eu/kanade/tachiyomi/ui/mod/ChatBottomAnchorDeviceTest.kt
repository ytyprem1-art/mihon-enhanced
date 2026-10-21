package eu.kanade.tachiyomi.ui.mod

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.mod.chatprofile.KeepChatAtBottom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Local synthetic messages only: no writes to the shared chat. */
class ChatBottomAnchorDeviceTest {
    @Test fun newMessagesFollowBottomButPreserveReadingPosition(): Unit {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val messages = mutableStateOf((30 downTo 1).map { "message-$it" })
        val viewport = mutableStateOf(400)
        var stateReady = false
        lateinit var listState: LazyListState
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        listState = rememberLazyListState()
                        stateReady = true
                        KeepChatAtBottom(listState, messages.value.firstOrNull())
                        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.height(viewport.value.dp)) {
                            items(messages.value, key = { it }) {
                                Box(Modifier.fillMaxWidth().height(70.dp))
                            }
                        }
                    }
                })
            }
            fun awaitPosition(check: () -> Boolean) {
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    var passed = false
                    instrumentation.runOnMainSync { passed = check() }
                    if (passed) return
                    android.os.SystemClock.sleep(40)
                }
                fail("Chat did not reach the expected scroll position")
            }
            awaitPosition { stateReady && listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == "message-30" }
            instrumentation.runOnMainSync { messages.value = listOf("message-31") + messages.value }
            awaitPosition { !listState.canScrollBackward && listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == "message-31" }
            // Simulate the smaller available viewport while the keyboard is open.
            instrumentation.runOnMainSync { viewport.value = 180; messages.value = listOf("message-32") + messages.value }
            awaitPosition { !listState.canScrollBackward && listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == "message-32" }
            instrumentation.runOnMainSync { runBlocking { listState.scrollToItem(8, 17) } }
            awaitPosition { listState.firstVisibleItemIndex == 8 }
            var anchor: Any? = null
            instrumentation.runOnMainSync { anchor = listState.layoutInfo.visibleItemsInfo.first().key; messages.value = listOf("message-33") + messages.value }
            awaitPosition { listState.firstVisibleItemIndex == 9 && listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == anchor }
            instrumentation.runOnMainSync { messages.value = messages.value + "older-message" }
            awaitPosition { listState.layoutInfo.totalItemsCount == 34 && listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == anchor }
            instrumentation.runOnMainSync { runBlocking { listState.scrollToItem(0) } }
            awaitPosition { !listState.canScrollBackward }
            instrumentation.runOnMainSync { messages.value = listOf("message-34") + messages.value }
            awaitPosition { !listState.canScrollBackward && listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == "message-34" }
        }
    }
}
