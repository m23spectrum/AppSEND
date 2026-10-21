package local.edgebeam

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device checks use generated pixels/text only, never the user's photos or camera feed. */
@RunWith(AndroidJUnit4::class)
class GrabDeviceTest {
    @Test
    fun bundledModelLoadsAndRejectsBlankFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recognizer =
            GestureRecognizer.createFromOptions(
                context,
                GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build()
                    )
                    .build(),
            )
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val image = BitmapImageBuilder(bitmap).build()
        try {
            assertTrue(recognizer.recognize(image).landmarks().isEmpty())
        } finally {
            recognizer.close()
            image.close()
            bitmap.recycle()
        }
    }

    @Test
    fun phoneDiscoversWindowsAndTransfersGeneratedTextOverLan() = runBlocking {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BeamApp
        val engine = Engine(app)
        try {
            app.state.value = UiState(motion = "grab")
            engine.start()
            val peer =
                withTimeout(20000) {
                    app.state
                        .mapNotNull {
                            it.peers.firstOrNull { p ->
                                p.name == "AppSEND hardware check" && p.platform == "Windows"
                            }
                        }
                        .first()
                }
            engine.stageText("AppSEND 1.5 phone-to-PC LAN check — generated test data")
            val source = app.state.value.stage!!
            app.state.update { it.copy(selected = peer.id) }
            withTimeout(30000) { engine.trigger(true, peer.id, source) }
            assertEquals("Delivered · integrity verified", app.state.value.status)
            assertEquals("plane", app.state.value.animation?.motion)
            assertEquals(true, app.state.value.animation?.departureShown)
            assertFalse(app.state.value.busy)
        } finally {
            engine.close()
            app.state.value = UiState()
        }
    }
}
