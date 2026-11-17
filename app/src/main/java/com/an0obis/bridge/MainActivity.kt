package com.an0obis.bridge

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.an0obis.bridge.monetization.AdsManager
import com.an0obis.bridge.monetization.BillingManager
import com.an0obis.bridge.monetization.Engagement
import com.an0obis.bridge.ui.BridgeTheme
import com.an0obis.bridge.ui.MainMenuScreen
import com.an0obis.bridge.ui.PlaceholderScreen
import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.ui.misc.AboutScreen
import com.an0obis.bridge.ui.misc.SettingsScreen
import com.an0obis.bridge.ui.misc.TutorialScreen
import com.an0obis.bridge.ui.misc.TopPlayersScreen
import com.an0obis.bridge.ui.solo.SoloScreen
import com.an0obis.bridge.ui.misc.openStorePage

object Routes {
    const val MENU = "menu"
    const val SOLO = "solo"
    const val SEASON = "season"
    const val ONLINE = "online"
    const val TOP = "top"
    const val ABOUT = "about"
    const val SETTINGS = "settings"
    const val TUTORIAL = "tutorial"
    /** The tutorial opened by the first tap on Solo play; it continues into the game. */
    const val TUTORIAL_FIRST = "tutorial_first"
}

class MainActivity : AppCompatActivity() {

    private val app: BridgeApp
        get() = application as BridgeApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // phones stay portrait; tablets may rotate
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // purchases first (they decide whether any ad is ever requested), then ads
        BillingManager.start(this)
        AdsManager.init(this)
        setContent {
            BridgeTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    BridgeNavHost()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // catches a purchase or refund that happened outside the app
        BillingManager.refresh()
    }

    override fun onStop() {
        super.onStop()
        app.saveAll()
    }

    @Composable
    private fun BridgeNavHost() {
        val navController = rememberNavController()

        NavHost(navController = navController, startDestination = Routes.MENU) {
            composable(Routes.MENU) {
                MainMenuScreen(
                    onSolo = {
                        val first = !AppSettings().tutorialSeen
                        navController.navigate(if (first) Routes.TUTORIAL_FIRST else Routes.SOLO)
                    },
                    onSeason = { navController.navigate(Routes.SEASON) },
                    onTop = { navController.navigate(Routes.TOP) },
                    onOnline = { navController.navigate(Routes.ONLINE) },
                    onTutorial = { navController.navigate(Routes.TUTORIAL) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onRate = {
                        openStorePage(this@MainActivity)
                        Engagement.markRated()
                    },
                    onPrivacy = { AdsManager.showPrivacyOptions(this@MainActivity) },
                    onAbout = { navController.navigate(Routes.ABOUT) }
                )
            }
            composable(Routes.SOLO) { SoloScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SEASON) { PlaceholderScreen(getString(R.string.coming_soon)) }
            composable(Routes.TOP) { TopPlayersScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.TUTORIAL) {
                TutorialScreen(onDone = {
                    AppSettings().tutorialSeen = true
                    navController.popBackStack()
                })
            }
            composable(Routes.TUTORIAL_FIRST) {
                TutorialScreen(onDone = {
                    AppSettings().tutorialSeen = true
                    navController.navigate(Routes.SOLO) {
                        popUpTo(Routes.TUTORIAL_FIRST) { inclusive = true }
                    }
                })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onTutorial = { navController.navigate(Routes.TUTORIAL) })
            }
            composable(Routes.ONLINE) { PlaceholderScreen(getString(R.string.coming_soon)) }
            composable(Routes.ABOUT) {
                val version = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
                } catch (e: Exception) {
                    "1.0"
                }
                AboutScreen(versionName = version)
            }
        }
    }
}
