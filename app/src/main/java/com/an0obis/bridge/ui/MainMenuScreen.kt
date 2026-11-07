package com.an0obis.bridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.an0obis.bridge.R
import com.an0obis.bridge.monetization.AdsManager
import com.an0obis.bridge.monetization.BillingManager
import com.an0obis.bridge.ui.misc.RemoveAdsDialog

@Composable
private fun MenuItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(text = title, fontSize = 30.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(text = subtitle, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
    }
}

@Composable
fun MainMenuScreen(
    onSolo: () -> Unit,
    onSeason: () -> Unit,
    onOnline: () -> Unit,
    onRate: () -> Unit,
    onPrivacy: () -> Unit,
    onAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        var titleSize by remember { mutableStateOf(56.sp) }
        Text(
            text = stringResource(R.string.app_name),
            fontSize = titleSize,
            fontFamily = FontFamily.Serif,
            color = AccentGold,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { if (it.didOverflowWidth) titleSize *= 0.92f },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.menu_subtitle),
            fontSize = 22.sp,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            letterSpacing = 6.sp,
            color = AccentGold.copy(alpha = 0.75f),
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.dp, bottom = 24.dp)
        )
        MenuItem(stringResource(R.string.menu_solo), stringResource(R.string.menu_solo_sub), onSolo)
        MenuItem(stringResource(R.string.menu_season), stringResource(R.string.menu_season_sub), onSeason)
        MenuItem(stringResource(R.string.mp_menu), stringResource(R.string.mp_menu_sub), onOnline)
        val adFree by BillingManager.adFree.collectAsState()
        var showRemoveAds by remember { mutableStateOf(false) }
        if (AdsManager.enabled && !adFree) {
            MenuItem(stringResource(R.string.menu_remove_ads), stringResource(R.string.menu_remove_ads_sub)) {
                showRemoveAds = true
            }
        }
        if (showRemoveAds) RemoveAdsDialog(onDismiss = { showRemoveAds = false })
        MenuItem(stringResource(R.string.menu_rate), stringResource(R.string.menu_rate_sub), onRate)
        val privacyRequired by AdsManager.privacyOptionsRequired.collectAsState()
        if (AdsManager.enabled && !adFree && privacyRequired) {
            MenuItem(stringResource(R.string.menu_privacy), stringResource(R.string.menu_privacy_sub), onPrivacy)
        }
        MenuItem(stringResource(R.string.menu_about), stringResource(R.string.menu_about_sub), onAbout)
    }
}
