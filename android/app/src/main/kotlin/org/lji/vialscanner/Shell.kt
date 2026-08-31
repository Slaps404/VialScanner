package org.lji.vialscanner

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The app shell, matching `App/RootView.swift`.
 *
 * A slide-over drawer with the page pushed and scaled behind it. No tab bar and
 * no navigation chrome, because in use this app is a viewfinder with a menu,
 * not a document browser - a bottom bar would also eat the thumb space the
 * shutter needs.
 */

enum class Route(val title: String, val icon: ImageVector) {
    SCAN("Scan", Icons.Filled.CropFree),
    INVENTORY("Inventory", Icons.Filled.Inventory2),
    REVIEW("Review queue", Icons.Filled.HistoryToggleOff),
    AUDIT("Confirm inventory", Icons.Filled.Checklist),
    HISTORY("Scan history", Icons.AutoMirrored.Filled.ListAlt),
    DATA("Import & export", Icons.Filled.SwapVert),
    SETTINGS("Settings", Icons.Filled.Settings),
}

private val DrawerWidth = 288.dp

/** The spring Swift uses for every drawer move: response .34, damping .86. */
private val DrawerSpring = spring<Float>(
    dampingRatio = 0.86f,
    stiffness = Spring.StiffnessMediumLow,
)

@Composable
fun AppShell(
    store: AppStore,
    route: Route,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onRoute: (Route) -> Unit,
    page: @Composable () -> Unit,
) {
    var drag by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val widthPx = with(density) { DrawerWidth.toPx() }
    val edgePx = with(density) { 28.dp.toPx() }

    // While a drag is live the offset follows the finger; otherwise it springs
    // to whichever end it settled on. Two sources, one value, so the handoff
    // between them cannot jump.
    val settled by animateFloatAsState(
        targetValue = if (open) widthPx else 0f,
        animationSpec = DrawerSpring,
        label = "drawer",
    )
    val dragging = drag != 0f
    val offset = ((if (dragging) (if (open) widthPx else 0f) + drag else settled))
        .coerceIn(0f, widthPx)
    val openness = offset / widthPx

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.bg)
            .pointerInput(open) {
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        // Only from the left edge when closed, so a horizontal
                        // swipe inside a list never fights the drawer.
                        if (!open && start.x > edgePx) drag = Float.NaN
                    },
                    onDragEnd = {
                        if (!drag.isNaN()) {
                            val projected = offset
                            onOpenChange(projected > widthPx * 0.45f)
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                    onHorizontalDrag = { _, delta ->
                        if (!drag.isNaN()) drag += delta
                    },
                )
            },
    ) {
        // ---- The page, pushed and scaled ------------------------------------
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset((offset * 0.3f).roundToInt(), 0) }
                .scale(1f - 0.06f * openness)
                .clip(RoundedCornerShape(if (openness > 0f) 32.dp else 0.dp))
                .background(Ink.bg),
        ) {
            page()

            if (openness > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f * openness))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onOpenChange(false) },
                )
            }
        }

        // ---- The drawer ------------------------------------------------------
        Box(
            Modifier
                .width(DrawerWidth)
                .fillMaxHeight()
                .offset { IntOffset((-widthPx * (1f - openness)).roundToInt(), 0) }
                .background(Ink.bg),
        ) {
            DrawerContent(store, route) { r ->
                Haptics.tap()
                onRoute(r)
                onOpenChange(false)
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Ink.hairline.copy(alpha = openness)),
            )
        }
    }
}

@Composable
private fun DrawerContent(store: AppStore, route: Route, onPick: (Route) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Vial Scanner",
            color = Ink.text,
            fontSize = 21.sp2(),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 18.dp),
        )

        for (r in Route.entries) {
            val selected = r == route
            Row(
                Modifier
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Ink.fill else Color.Transparent)
                    .clickable { onPick(r) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(r.icon, null, tint = Ink.text, modifier = Modifier.width(24.dp))
                Spacer(Modifier.width(14.dp))
                Text(
                    r.title,
                    color = Ink.text,
                    fontSize = Type.body,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                badge(store, r)?.let { b ->
                    Surface(color = Ink.solid, shape = CircleShape) {
                        Text(
                            b,
                            color = Ink.onSolid,
                            fontSize = Type.caption,
                            fontWeight = Type.labelWeight,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Column(Modifier.padding(18.dp).navigationBarsPadding()) {
            Text(
                "${store.items.size} vials · ${store.defs.size} definitions",
                color = Ink.textSecond, fontSize = Type.caption,
            )
            store.openAudit?.let {
                Text("Audit open: ${it.title}", color = Ink.warn, fontSize = Type.caption)
            }
        }
    }
}

private fun badge(store: AppStore, r: Route): String? = when (r) {
    Route.REVIEW -> store.reviewQueue.size.takeIf { it > 0 }?.toString()
    Route.AUDIT -> store.openAudit?.missing?.size?.takeIf { it > 0 }?.toString()
    else -> null
}

/** The one size in the drawer that is not on the shared scale. */
private fun Int.sp2() = androidx.compose.ui.unit.TextUnit(
    this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp,
)

// MARK: - Shared chrome

/**
 * A screen: hamburger, title, optional trailing control, then the content.
 * Mirrors Swift's `Screen`.
 */
@Composable
fun Screen(
    title: String,
    onMenu: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Ink.bg).statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Metric.pad)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIcon(Icons.Filled.Menu, onClick = onMenu)
            Spacer(Modifier.width(12.dp))
            Text(title, color = Ink.text, fontSize = Type.title, fontWeight = Type.titleWeight)
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

/** A circular icon button on a page background. Mirrors Swift's `CircleIcon`. */
@Composable
fun CircleIcon(
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    filled: Boolean = false,
    tint: Color = Ink.text,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) Ink.solid else Ink.surface)
            .then(if (filled) Modifier else Modifier.border(1.dp, Ink.hairline, CircleShape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (filled) Ink.onSolid else tint, modifier = Modifier.size(size * 0.40f))
    }
}

/**
 * The same shape, legible on top of a live camera feed. Mirrors Swift's
 * `GlassCircleIcon` - on the viewfinder the palette is fixed black-on-white
 * rather than themed, because the backdrop is whatever the freezer looks like.
 */
@Composable
fun GlassCircleIcon(
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) Color.White else Color.Black.copy(alpha = 0.42f))
            .then(
                if (active) Modifier
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = if (active) Color.Black else Color.White,
            modifier = Modifier.size(size * 0.40f),
        )
    }
}

/**
 * The transient message, matching Swift's `bannerView`: a card at the top of
 * the screen rather than Material's bottom snackbar, because the bottom of this
 * screen is the shutter and covering it mid-scan is how a scan gets missed.
 */
@Composable
fun BoxScope.Banner(b: AppStore.Banner) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Ink.surface)
            .border(1.dp, Ink.hairline, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (b.isError) Icons.Filled.Warning else Icons.Filled.CheckCircle,
            null,
            tint = if (b.isError) Ink.bad else Ink.good,
        )
        Spacer(Modifier.width(10.dp))
        Text(b.text, color = Ink.text, fontSize = Type.label, fontWeight = Type.labelWeight)
    }
}
