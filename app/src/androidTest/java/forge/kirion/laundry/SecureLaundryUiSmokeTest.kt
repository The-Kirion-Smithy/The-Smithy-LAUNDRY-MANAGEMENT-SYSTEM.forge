package forge.kirion.laundry

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android emulator smoke of the integrated encrypted-DB Compose UI.
 * This test does not substitute for a physical handset or screenshot/keyboard review.
 */
@RunWith(AndroidJUnit4::class)
class SecureLaundryUiSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun tabsAndEntryScreensAreActuallyNavigable() {
        rule.onNodeWithText("Good day, team.").assertExists()
        rule.onNodeWithText("People").performClick()
        rule.onNodeWithText("Add a customer").assertExists()

        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Today's report").assertExists()
        rule.onNodeWithText("Services").performClick()
        rule.onNodeWithText("Create a service").assertExists()

        rule.onNodeWithText("New").performClick()
        rule.onNodeWithText("New drop-off").assertExists()
        rule.onNodeWithText("Queue").performClick()
        rule.onNodeWithText("Laundry queue").assertExists()
    }
}
