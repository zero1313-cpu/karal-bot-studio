package com.karal.studio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class UiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardShowsAllFiveDestinations() {
        val activity = composeRule.activity
        listOf(
            R.string.nav_home, R.string.nav_projects, R.string.nav_studio,
            R.string.nav_hosting, R.string.nav_settings
        ).forEach { res ->
            composeRule.onNodeWithText(activity.getString(res)).assertIsDisplayed()
        }
    }
}
