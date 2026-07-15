package ai.grayin.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSecurityUiBoundaryTest {
    @Test
    fun lockBoundaryKeepsLaunchersRegisteredAndClearsTransientSecretsOnStop() {
        val source = File("src/main/java/ai/grayin/app/GrayinApp.kt").readText()
        val materialBoundary = source.indexOf("MaterialTheme(")

        assertTrue(materialBoundary > 0)
        assertTrue(source.indexOf("rememberLauncherForActivityResult") in 0 until materialBoundary)
        assertTrue(source.contains("Lifecycle.Event.ON_STOP"))
        assertTrue(source.contains("if (appSecurityState.protectedContentVisible) {\n        when (backupDialogMode)"))
        assertTrue(source.contains("backupPassword = \"\""))
        assertTrue(source.contains("backupPasswordConfirmation = \"\""))
    }

    @Test
    fun askPromptIsNeverStoredInSaveableInstanceState() {
        val source = File("src/main/java/ai/grayin/app/GrayinApp.kt").readText()
        val askScreen = source.substringAfter("private fun AskScreen(")
            .substringBefore("\n@Composable\nprivate fun AnswerCard(")

        assertTrue(askScreen.contains("var query by remember { mutableStateOf(\"\") }"))
        assertFalse(askScreen.contains("rememberSaveable"))
    }
}
