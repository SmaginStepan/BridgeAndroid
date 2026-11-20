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
import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.ui.misc.AboutScreen
import com.an0obis.bridge.ui.misc.SettingsScreen
import com.an0obis.bridge.ui.season.SeasonScreen
import com.an0obis.bridge.ui.solo.SoloViewModel
import com.an0obis.bridge.model.session.Seasons
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.an0obis.bridge.ui.system.SystemBuilderScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.an0obis.bridge.ui.misc.TutorialScreen
import com.an0obis.bridge.ui.misc.TopPlayersScreen
import com.an0obis.bridge.ui.online.OnlineScreen
import com.an0obis.bridge.ui.solo.SoloScreen
import com.an0obis.bridge.ui.misc.openStorePage

object Routes {
    const val MENU = "menu"
    const val SOLO = "solo"
    const val SEASON = "season"
    /** The table of a season game (the run saved on the device continues). */
    const val SEASON_PLAY = "season_play"
    const val ONLINE = "online"
    const val TOP = "top"
    const val ABOUT = "about"
    const val SETTINGS = "settings"
    /** The bidding-system builder; with an id it edits one of the player's systems. */
    const val SYSTEM_BUILDER = "system_builder?id={id}"
    fun systemBuilder(id: String?) = "system_builder" + if (id != null) "?id=$id" else ""
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
            composable(Routes.SEASON) {
                SeasonScreen(
                    onPlay = { navController.navigate(Routes.SEASON_PLAY) },
                    onToMenu = { navController.popBackStack() }
                )
            }
            composable(Routes.SEASON_PLAY) {
                val run = remember { Seasons.load() }
                if (run == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    // no advice and no undo in a season game: everyone plays the same boards on equal terms
                    SoloScreen(
                        onBack = { navController.popBackStack() },
                        adviceAllowed = false,
                        vm = viewModel(factory = SoloViewModel.seasonFactory(run))
                    )
                }
            }
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
                SettingsScreen(
                    onTutorial = { navController.navigate(Routes.TUTORIAL) },
                    onEditSystem = { id -> navController.navigate(Routes.systemBuilder(id)) }
                )
            }
            composable(
                Routes.SYSTEM_BUILDER,
                arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { entry ->
                SystemBuilderScreen(
                    editId = entry.arguments?.getString("id"),
                    onDone = { navController.popBackStack() }
                )
            }
            composable(Routes.ONLINE) { OnlineScreen(onBack = { navController.popBackStack() }) }
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
