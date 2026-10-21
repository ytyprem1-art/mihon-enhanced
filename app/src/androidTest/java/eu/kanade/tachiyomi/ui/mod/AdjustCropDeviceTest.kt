package eu.kanade.tachiyomi.ui.mod

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.mod.panelshare.CropTemplate
import eu.kanade.tachiyomi.ui.mod.panelshare.DocumentCrop
import eu.kanade.tachiyomi.ui.mod.panelshare.OriginalCropDocument
import eu.kanade.tachiyomi.ui.mod.panelshare.OriginalCropView
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AdjustCropDeviceTest {
    @Test fun reopeningEachTemplateBeforeLayoutKeepsFiniteCropAndCanRenderAgain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(200, 600, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        val page = ReaderPage(0, stream = { ByteArrayInputStream(bytes) }).apply { status = Page.State.Ready }
        val chapter = ReaderChapter(Chapter.create()).apply { state = ReaderChapter.State.Loaded(listOf(page)) }
        val document =
            withContext(Dispatchers.Main) { OriginalCropDocument(context, chapter, 0).also { it.loadNext() } }
        try {
            assertEquals(1, document.pages.size)
            for (template in CropTemplate.entries) {
                var selection = DocumentCrop(.1, .2, .9, 1.5)
                repeat(3) {
                    withContext(Dispatchers.Main) {
                        var callbacks = 0
                        val view = OriginalCropView(context).apply {
                            onSelection = { crop ->
                                callbacks++
                                assertTrue(listOf(crop.left, crop.top, crop.right, crop.bottom).all { it.isFinite() })
                                selection = crop
                                val dimensions = document.dimensions(crop)
                                assertTrue(dimensions.first > 0 && dimensions.second > 0)
                            }
                        }
                        // Compose calls update before layout when returning from the crop preview.
                        view.update(document, selection, template, .5)
                        assertEquals(0, callbacks)
                        view.layout(0, 0, 600, 900)
                        view.update(document, selection, template, .5)
                    }
                    val rendered = document.render(selection, 160)
                    assertTrue(rendered.width > 0 && rendered.height > 0)
                    rendered.recycle()
                }
            }
        } finally {
            withContext(Dispatchers.Main) { document.close() }
        }
    }
}
