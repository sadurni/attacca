package com.sadurni.attacca.ui.settings

import com.sadurni.attacca.ui.theme.AttaccaType
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import com.sadurni.attacca.ui.common.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.BuildConfig
import com.sadurni.attacca.R
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import java.text.DateFormat
import java.util.Date

private data class Component(val name: String, val version: String?, val license: String, val url: String)

private const val APACHE_2 = "Apache License 2.0"

/** Where the source code is published. */
private val SOURCE_URL: String? = "https://github.com/sadurni/attacca"

/** The app's page in F-Droid ("https://f-droid.org/packages/com.sadurni.attacca/"); null until it is there. */
private val FDROID_URL: String? = null

private val components = listOf(
    Component("AndroidX Media3 (ExoPlayer, Session)", BuildConfig.MEDIA3_VERSION, APACHE_2, "https://github.com/androidx/media"),
    Component("Jetpack Compose (BOM)", BuildConfig.COMPOSE_BOM_VERSION, APACHE_2, "https://developer.android.com/jetpack/compose"),
    Component("Material Components & Icons", null, APACHE_2, "https://github.com/google/material-design-icons"),
    Component("AndroidX Core, AppCompat, Activity, Lifecycle", BuildConfig.ANDROIDX_CORE_VERSION, APACHE_2, "https://developer.android.com/jetpack/androidx"),
    Component("AndroidX Palette", BuildConfig.PALETTE_VERSION, APACHE_2, "https://developer.android.com/jetpack/androidx/releases/palette"),
    Component("Kotlin", BuildConfig.KOTLIN_VERSION, APACHE_2, "https://kotlinlang.org"),
    Component("kotlinx.coroutines", BuildConfig.COROUTINES_VERSION, APACHE_2, "https://github.com/Kotlin/kotlinx.coroutines"),
    Component("Guava (via Media3)", null, APACHE_2, "https://github.com/google/guava"),
    Component("jaudiotagger", BuildConfig.JAUDIOTAGGER_VERSION, "GNU LGPL 2.1", "https://bitbucket.org/ijabz/jaudiotagger"),
    Component("YAMNet sound classifier (weights)", null, APACHE_2, "https://www.kaggle.com/models/google/yamnet"),
)

/** The app, its version and build, and the components it is made of with their licenses. */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenChangelog: () -> Unit = {}) {
    val context = LocalContext.current
    val accent = LocalAccent.current
    val open = { url: String ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        Unit
    }
    var showLicense by rememberSaveable { mutableStateOf(false) }
    val buildDate = remember { DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(BuildConfig.BUILD_TIME)) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.about_title), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.Black),
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text(stringResource(R.string.app_name), fontSize = AttaccaType.display, fontFamily = com.sadurni.attacca.ui.theme.LocalTitleFamily.current, fontWeight = FontWeight.Bold, color = accent)
                    com.sadurni.attacca.ui.common.AppTagline(Modifier.padding(bottom = 4.dp))
                    com.sadurni.attacca.ui.common.AppSlogan(Modifier.padding(bottom = 6.dp))
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), fontSize = AttaccaType.body)
                    Text(
                        stringResource(R.string.about_build, buildDate) + if (BuildConfig.DEBUG) " · debug" else "",
                        fontSize = AttaccaType.secondary,
                        color = LocalAttaccaColors.current.textGrayed,
                    )
                }
            }
            Hint(stringResource(R.string.about_description))
            InfoLine(stringResource(R.string.about_package), BuildConfig.APPLICATION_ID)
            InfoLine(stringResource(R.string.about_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoLine(stringResource(R.string.about_device), "${Build.MANUFACTURER} ${Build.MODEL}")

            SectionTitle(stringResource(R.string.about_project))
            ProjectRow(stringResource(R.string.changelog_title), stringResource(R.string.about_changelog_hint, BuildConfig.VERSION_NAME), external = false, onClick = onOpenChangelog)
            val soon = stringResource(R.string.about_coming_soon)
            ProjectRow(stringResource(R.string.about_source), SOURCE_URL ?: soon, onClick = SOURCE_URL?.let { url -> { open(url) } })
            ProjectRow("F-Droid", FDROID_URL ?: soon, onClick = FDROID_URL?.let { url -> { open(url) } })

            SectionTitle(stringResource(R.string.about_license))
            Text(
                stringResource(R.string.about_license_summary),
                fontSize = AttaccaType.body,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                stringResource(if (showLicense) R.string.about_hide_license else R.string.about_show_license),
                color = accent,
                fontSize = AttaccaType.body,
                modifier = Modifier
                    .clickable { showLicense = !showLicense }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (showLicense) {
                Text(
                    MIT_LICENSE,
                    fontSize = AttaccaType.caption,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            SectionTitle(stringResource(R.string.about_components))
            Hint(stringResource(R.string.about_components_hint))
            for (component in components) ComponentRow(component) { open(component.url) }

            SectionTitle(stringResource(R.string.about_algorithms))
            Hint(stringResource(R.string.about_algorithms_text))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
        Text(value, fontSize = AttaccaType.secondary, modifier = Modifier.padding(start = 16.dp))
    }
}

/** A row of the project: what it is, where it leads (or that it is not there yet). */
@Composable
private fun ProjectRow(title: String, detail: String, external: Boolean = true, onClick: (() -> Unit)?) {
    val enabled = onClick != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = AttaccaType.body, fontWeight = FontWeight.Medium, color = if (enabled) Color.Unspecified else LocalAttaccaColors.current.textGrayed)
            Text(detail, fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
        }
        if (enabled) {
            Icon(
                if (external) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = LocalAccent.current.copy(alpha = 0.7f),
                modifier = Modifier.size(if (external) 18.dp else 24.dp),
            )
        }
    }
}

@Composable
private fun ComponentRow(component: Component, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(component.name, fontSize = AttaccaType.body, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(component.version, component.license).joinToString(" · "),
                fontSize = AttaccaType.secondary,
                color = LocalAttaccaColors.current.textGrayed,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = LocalAccent.current.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
    }
}

private val MIT_LICENSE = """
    MIT License

    Copyright (c) 2026 The Attacca authors

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
""".trimIndent()
