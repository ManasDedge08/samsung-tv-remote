package com.manas.tvremote.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manas.tvremote.R
import com.manas.tvremote.TvApp

/** How an app's tile looks: tvOS-style landscape icon, brand background, brand glyph. */
private data class Art(
    val background: Brush,
    val glyph: Color,
    val icon: Int? = null,
    val glyphSize: Dp = 34.dp,
    val wordmark: String? = null,
)

/** Brand-black icons sit on the system surface grey so the tile still reads against black. */
private val tileBlack = SolidColor(Color(0xFF1C1C1E))
private fun solid(c: Long) = SolidColor(Color(c))

private val ART = mapOf(
    "3201907018807" to Art(tileBlack, Color(0xFFE50914), R.drawable.ic_app_netflix, 34.dp),
    "111299001912" to Art(solid(0xFFFFFFFF), Color(0xFFFF0000), R.drawable.ic_app_youtube, 40.dp),
    "3201910019365" to Art(solid(0xFF00A8E1), Color.White, R.drawable.ic_app_primevideo, 66.dp),
    "3201807016597" to Art(tileBlack, Color.White, R.drawable.ic_app_appletv, 46.dp),
    "3201606009684" to Art(tileBlack, Color(0xFF1ED760), R.drawable.ic_app_spotify, 36.dp),
    "3201908019041" to Art(tileBlack, Color(0xFFFA2D48), R.drawable.ic_app_applemusic, 38.dp),
    "3201512006963" to Art(tileBlack, Color(0xFFEBAF00), R.drawable.ic_app_plex, 40.dp),
    "3201901017640" to Art(solid(0xFF0E2F7A), Color.White, wordmark = "Disney+"),
    "3202010022079" to Art(solid(0xFF6F5BFF), Color.White, glyphSize = 32.dp),
)

@Composable
fun AppTiles(apps: List<TvApp>, onLaunch: (TvApp) -> Unit, sidePadding: Dp) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = sidePadding),
    ) {
        items(apps, key = { it.id }) { app -> AppTile(app) { onLaunch(app) } }
    }
}

@Composable
private fun AppTile(app: TvApp, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    val art = ART[app.id] ?: Art(solid(0xFF2C2C2E), Color.White, wordmark = app.name)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "tile",
    )

    Column(
        Modifier
            .width(112.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(interactionSource = source, indication = null) {
                haptic(true)
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(112.dp)
                .height(67.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(14.dp))
                .background(art.background),
            contentAlignment = Alignment.Center,
        ) {
            val painter: Painter? = when {
                art.icon != null -> painterResource(art.icon)
                art.wordmark == null -> rememberVectorPainter(Icons.Rounded.Language)
                else -> null
            }
            if (painter != null) {
                Icon(painter, contentDescription = null, tint = art.glyph, modifier = Modifier.size(art.glyphSize))
            } else {
                Text(art.wordmark!!, color = art.glyph, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.name,
            style = Type.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
