package com.jondude1.bookshelf

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Seeds a signed-in state with a cached library before the app starts. */
class SeededApp : BookShelfApp() {
    override fun onCreate() {
        val now = System.currentTimeMillis()
        val books = (1..30).joinToString(",") { i ->
            val series = if (i <= 12) """[{"name":"Saga ${(i - 1) / 4}","sequence":"${(i - 1) % 4 + 1}"}]""" else "[]"
            """{"id":"b$i","libraryId":"lib","title":"Book $i","author":"Author ${i % 5}","series":$series,"format":"${listOf("EPUB","PDF","CBZ","MOBI")[i % 4]}","addedAt":${now - i * 1000}}"""
        }
        File(filesDir, "library.json").writeText("[$books," + books.substringBefore("},") + "}]")
        File(filesDir, "progress.json").writeText(
            """{"b1":{"itemId":"b1","fraction":1.0,"finished":true,"updatedAt":$now},
               "b2":{"itemId":"b2","fraction":0.4,"location":"bs1:c:3:0.2","updatedAt":$now},
               "b5":{"itemId":"b5","fraction":1.0,"finished":true,"updatedAt":$now},
               "b14":{"itemId":"b14","fraction":0.1,"updatedAt":$now}}""")
        super.onCreate()
        Graph.prefs.updateServer { it.copy(lanUrl = "http://127.0.0.1:9", accessToken = "x", username = "jon") }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SeededApp::class, qualifiers = "w411dp-h3000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun launchesToHome() {
        rule.waitForIdle()
        Thread.sleep(3000)
        rule.waitForIdle()
        check(rule.onAllNodesWithText("Continue reading").fetchSemanticsNodes().isNotEmpty()) { "Home did not render" }
    }
}
