package com.an0obis.bridge.ui.misc

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.an0obis.bridge.R
import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.ui.AccentGold

/** Language tags the app is translated into; "" means "follow the system". */
private val LANGUAGES = listOf("", "en", "es", "ru")

/** The tag of the language chosen in the app, or "" when it follows the system. */
private fun currentLanguageTag(): String {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return ""
    val language = locales[0]?.language ?: return ""
    return if (language in LANGUAGES) language else ""
}

@Composable
private fun languageLabel(tag: String): String = when (tag) {
    "en" -> "English"
    "es" -> "Español"
    "ru" -> "Русский"
    else -> stringResource(R.string.language_system)
}

@Composable
private fun systemLabel(id: String): String = when (id) {
    "sayc" -> stringResource(R.string.set_system_sayc)
    else -> id
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, fontSize = 18.sp)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 22.sp,
        fontFamily = FontFamily.Serif,
        color = AccentGold,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingsScreen(onTutorial: () -> Unit) {
    val settings = remember { AppSettings() }
    var language by remember { mutableStateOf(currentLanguageTag()) }
    var system by remember { mutableStateOf(settings.biddingSystem) }
    var tourReset by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = 36.sp,
            fontFamily = FontFamily.Serif,
            color = AccentGold
        )

        SectionTitle(stringResource(R.string.set_language))
        LANGUAGES.forEach { tag ->
            ChoiceRow(languageLabel(tag), language == tag) {
                language = tag
                // recreates the activity, so every screen is redrawn in the new language
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }

        SectionTitle(stringResource(R.string.set_system))
        AppSettings.BIDDING_SYSTEMS.forEach { id ->
            ChoiceRow(systemLabel(id), system == id) {
                system = id
                settings.biddingSystem = id
            }
        }
        Text(
            text = stringResource(R.string.set_system_note),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )

        SectionTitle(stringResource(R.string.menu_learning))
        Text(
            text = stringResource(R.string.set_tutorial_again),
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTutorial)
                .padding(vertical = 8.dp)
        )
        Text(
            text = stringResource(R.string.set_tour_again),
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    settings.boardTourSeen = 0
                    tourReset = true
                }
                .padding(vertical = 8.dp)
        )
        if (tourReset) {
            Text(
                text = stringResource(R.string.set_tour_again_done),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}
