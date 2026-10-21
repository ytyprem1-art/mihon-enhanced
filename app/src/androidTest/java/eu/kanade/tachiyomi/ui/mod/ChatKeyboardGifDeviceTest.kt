package eu.kanade.tachiyomi.ui.mod

import android.content.ClipDescription
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coil3.asDrawable
import eu.kanade.tachiyomi.ui.chat.GlobalChatState
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.mod.chatgif.ChatGifComposer
import eu.kanade.tachiyomi.ui.mod.chatgif.ChatGifStore
import eu.kanade.tachiyomi.util.storage.getUriCompat
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ChatKeyboardGifDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 30_000)
    fun gifRendererKeepsAnimation(): Unit = kotlinx.coroutines.runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bytes = instrumentation.context.assets.open("chat-keyboard-test.gif").use { it.readBytes() }
        val loader = coil3.ImageLoader.Builder(context).components { add(coil3.gif.GifDecoder.Factory()) }.build()
        try {
            val result = loader.execute(coil3.request.ImageRequest.Builder(context).data(bytes).size(256, 256).build())
            val drawable = requireNotNull(result.image).asDrawable(context.resources)
            assertTrue(
                "GIF must be decoded as an animation, not a still bitmap",
                drawable is android.graphics.drawable.Animatable,
            )
            instrumentation.runOnMainSync {
                val animation = drawable as android.graphics.drawable.Animatable
                animation.start()
                assertTrue(animation.isRunning)
                animation.stop()
            }
        } finally {
            loader.shutdown()
        }
    }

    @Test(timeout = 45_000)
    fun keyboardCommitContentPreviewsGifWithoutSendingToChat() {
        android.util.Log.d("ChatGifDeviceTest", "Preparing local GIF fixture")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var rootForTest: RootForTest? = null
        val file = File(context.cacheDir, "keyboard-gif-${UUID.randomUUID()}.gif")
        file.writeBytes(instrumentation.context.assets.open("chat-keyboard-test.gif").use { it.readBytes() })
        val bytes = ChatGifStore.validate(file.readBytes()).bytes

        @Suppress("DEPRECATION")
        val movie = requireNotNull(android.graphics.Movie.decodeByteArray(bytes, 0, bytes.size))
        assertTrue("Fixture must contain animation", movie.duration() > 0)
        try {
            android.util.Log.d("ChatGifDeviceTest", "Launching local composer")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                android.util.Log.d("ChatGifDeviceTest", "Activity launched")
                scenario.onActivity { activity ->
                    activity.setContentView(
                        ComposeView(activity).apply {
                            setContent {
                                var text by remember { mutableStateOf("") }
                                ChatGifComposer(
                                    room = GlobalChatState.ChatRoom("Local test", "local-test", emptyList()),
                                    text = text,
                                    onTextChange = { text = it },
                                    onSendMessage = { error("Test must never send") },
                                    onTyping = {},
                                    onGifSent = { error("Test must never send") },
                                )
                            }
                        },
                    )
                }
                android.util.Log.d("ChatGifDeviceTest", "Focusing field")
                compose.onNode(hasSetTextAction()).performClick()
                android.util.Log.d("ChatGifDeviceTest", "Field focused")
                compose.waitForIdle()
                scenario.onActivity { activity ->
                    fun editor(view: View): View? {
                        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
                        if (view is ViewGroup) {
                            for (index in 0 until view.childCount) {
                                editor(
                                    view.getChildAt(index),
                                )?.let {
                                    return it
                                }
                            }
                        }
                        return null
                    }
                    val view = requireNotNull(editor(activity.window.decorView))
                    rootForTest = view as RootForTest
                    val info = EditorInfo()
                    val input = requireNotNull(view.onCreateInputConnection(info))
                    assertTrue(info.contentMimeTypes.orEmpty().any { it == "image/*" || it == "*/*" })
                    assertTrue(
                        InputConnectionCompat.commitContent(
                            input,
                            info,
                            InputContentInfoCompat(
                                file.getUriCompat(context),
                                ClipDescription("GIF", arrayOf("image/gif")),
                                null,
                            ),
                            0,
                            null,
                        ),
                    )
                }
                android.util.Log.d("ChatGifDeviceTest", "IME content committed")
                // An animated Drawable continuously invalidates the window. Espresso's global idle
                // check can wait forever. Read the public Compose test semantics on the UI thread
                // instead, without disabling the animation being tested.
                fun find(description: String): SemanticsNode? {
                    compose.mainClock.advanceTimeByFrame()
                    var found: SemanticsNode? = null
                    instrumentation.runOnMainSync {
                        found = rootForTest?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = true)?.firstOrNull {
                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
                        }
                    }
                    return found
                }
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (find("Hapus GIF") == null &&
                    android.os.SystemClock.uptimeMillis() < deadline
                ) {
                    android.os.SystemClock.sleep(40)
                }
                if (find("Hapus GIF") == null) {
                    instrumentation.runOnMainSync {
                        android.util.Log.d(
                            "ChatGifDeviceTest",
                            rootForTest?.semanticsOwner?.getAllSemanticsNodes(true)?.joinToString("\n") {
                                it.config.toString()
                            }.orEmpty(),
                        )
                    }
                }
                assertNotNull("Keyboard GIF draft must appear", find("Hapus GIF"))
                assertNotNull("Animated GIF preview must be accessible", find("GIF"))
                val remove = requireNotNull(find("Hapus GIF"))
                instrumentation.runOnMainSync {
                    assertTrue(remove.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
                }
                val removedDeadline = android.os.SystemClock.uptimeMillis() + 5000
                while (find("Hapus GIF") != null &&
                    android.os.SystemClock.uptimeMillis() < removedDeadline
                ) {
                    android.os.SystemClock.sleep(40)
                }
                assertNull(find("Hapus GIF"))
                compose.onNode(hasSetTextAction()).performTextInput("ordinary text")
                compose.onNode(hasSetTextAction()).assertTextContains("ordinary text")
            }
        } finally {
            file.delete()
        }
    }
}
