package org.elderguard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.elderguard.R

// ---------------------------------------------------------------- shared building blocks (all screens + the redirect prompt)

internal val CardShape = RoundedCornerShape(24.dp)
internal val ButtonShape = RoundedCornerShape(18.dp)

@Composable
internal fun Page(bottom: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
        if (bottom != null) {
            Surface(color = Palette.Card, shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { bottom() }
            }
        }
    }
}

@Composable
internal fun BrandBar(onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // same mark and name as the launcher icon, so the app on the home screen is easy to connect with this screen
        Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.weight(1f)) {
            Text("fkAd", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
            Spacer(Modifier.size(8.dp))
            Text("守門員", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        }
        if (onBack != null) TextButton(onClick = onBack) { Text("回首頁", style = MaterialTheme.typography.labelMedium, color = Palette.Trust) }
        if (onSettings != null) OutlinedButton(onClick = onSettings, shape = ButtonShape, border = BorderStroke(1.5.dp, Palette.Trust),
            modifier = Modifier.heightIn(min = 48.dp)) { Text("設定", style = MaterialTheme.typography.labelMedium, color = Palette.Trust) }
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Safe))
    }
}

@Composable
internal fun StatusLine(ok: Boolean, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (ok) Palette.Safe else Palette.Caution)
        Spacer(Modifier.size(12.dp))
        Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(if (ok) "已開啟" else "沒有開啟", style = MaterialTheme.typography.bodyMedium, color = if (ok) Palette.Safe else Palette.Caution)
    }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, color: Color = Palette.Trust) {
    Button(onClick = onClick, shape = ButtonShape, colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = ButtonShape, border = BorderStroke(2.dp, Palette.Trust),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Trust, containerColor = Palette.Card),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
internal fun QuietButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.InkSoft)
    }
}

@Composable
internal fun Icon(bmp: ImageBitmap?, size: Int = 64) {
    val m = Modifier.size(size.dp).clip(RoundedCornerShape(16.dp))
    if (bmp != null) Image(bmp, contentDescription = null, modifier = m) else Box(m.background(Palette.Line))
}

@Composable
internal fun Bullet(text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 12.dp)) { Dot(color) }
        Spacer(Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
    }
}

@Composable
internal fun Card(color: Color = Palette.Card, border: Boolean = true, content: @Composable () -> Unit) {
    Surface(shape = CardShape, color = color, border = if (border) BorderStroke(1.dp, Palette.Line) else null, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

