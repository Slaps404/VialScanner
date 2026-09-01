package org.lji.vialscanner

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.IntOffset
import org.lji.vialcore.LabelField
import org.lji.vialcore.NormRect
import org.lji.vialcore.ScanResult
import org.lji.vialcore.ScanState
import org.lji.vialcore.Verdict

/**
 * The screen the app exists for. Same layout rules as `App/ScanView.swift`,
 * because they came from watching people hold a vial in one hand: the
 * viewfinder is always live and always visible, every control that matters sits
 * in the bottom third where a thumb reaches, and the result never covers the
 * aiming rectangle - it slides up underneath it, so the next vial can be lined
 * up while the last one is still being decided.
 *
 * The chrome floats on the camera rather than sitting in a bar, so nothing
 * borrows the preview's brightness: every control carries its own dark ground
 * or its own white fill.
 */
@Composable
fun ScanScreen(
    store: AppStore,
    controller: ScanController,
    granted: Boolean,
    openMenu: () -> Unit,
    onRequestCamera: () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    var showSessionBox by remember { mutableStateOf(false) }

    // What the control you just pressed did, in words, for about a second.
    // Every control on this screen is an icon, and an icon is only obvious to
    // whoever chose it: a padlock could plausibly lock focus, lock exposure, or
    // lock the screen. Phones have no hover, and a long-press tooltip is a
    // gesture nobody discovers either, so the caption rides on the tap that
    // already happened and names the state the control just moved to. Swift
    // does the same in `ScanView.flash`.
    var caption by remember { mutableStateOf<String?>(null) }
    var captionSeq by remember { mutableStateOf(0) }
    val tip: (String) -> Unit = { caption = it; captionSeq++ }
    LaunchedEffect(captionSeq) {
        if (caption != null) { delay(1400); caption = null }
    }
    var receiving by remember { mutableStateOf<ReceiveDraft?>(null) }
    var rejecting by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<String?>(null) }

    // Per-scan clip toggle. Seeded from "always keep" once, like Swift's
    // `.onAppear { keepClip = store.settings.alwaysKeepClips }`, so the toggle
    // starts wherever the setting says but is still a live per-scan decision.
    var keepClip by remember { mutableStateOf(store.settings.alwaysKeepClips) }
    // Recomputed fresh on every recomposition, so a setting flipped mid-run
    // (hands-free turned on, "always keep" turned on) applies to the next scan
    // rather than waiting for the app to restart.
    val recordThisScan = keepClip || store.settings.alwaysKeepClips || store.settings.handsFree

    val state = controller.state
    val idle = !controller.running

    // The controller is not a composable and cannot read the store, so the one
    // setting its focus logic needs is pushed to it whenever it changes.
    LaunchedEffect(store.settings.autoTextFocus) {
        controller.autoTextFocus = store.settings.autoTextFocus
    }

    // Hands-free: start the next scan on its own, so a run of vials never needs
    // a tap to begin either.
    LaunchedEffect(idle, store.settings.handsFree) {
        if (idle && store.settings.handsFree) { delay(400); controller.rescan(recordThisScan) }
    }

    // A result landing is worth a buzz whether or not hands-free is on - it is
    // the same moment `App/ScanView.swift`'s `handleResult()` fires
    // `Haptics.result`. Speaking it out loud, though, is hands-free only: with
    // the phone in hand the result card already says everything out loud reads.
    LaunchedEffect(state) {
        val s = state
        if (s !is ScanState.Finished) return@LaunchedEffect
        Haptics.result(s.result.verdict)
        if (store.settings.handsFree) Speaker.say(Speaker.phrase(s.result))
    }

    // Hands-free: commit the verdict on its own after a beat. The delay is not
    // cosmetic - it is the window in which a wrong answer can still be caught by
    // the person holding the vial.
    LaunchedEffect(state) {
        val s = state
        if (s !is ScanState.Finished || !store.settings.handsFree) return@LaunchedEffect
        val r = s.result
        delay((store.settings.handsFreeDelay * 1000).toLong())
        if (controller.state !== s) return@LaunchedEffect
        when {
            meetsBar(r.verdict, store.settings.autoCommit) && r.vial != null -> {
                flash = confirm(store, controller, r, keepClip)
                controller.rescan(recordThisScan)
            }
            store.settings.handsFreeParksUncertain && !r.readNothing -> {
                park(store, controller, r)
                flash = "Saved for review"
                controller.rescan(recordThisScan)
            }
            else -> {
                dismiss(store, controller, r)
                controller.rescan(recordThisScan)
            }
        }
    }

    LaunchedEffect(flash) { if (flash != null) { delay(2500); flash = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    // Tap anywhere to aim focus, pinch to zoom. Both are on the
                    // preview rather than on a control, because the hand that is
                    // not holding the vial is the hand holding the phone.
                    .pointerInput(Unit) {
                        detectTapGestures { p ->
                            controller.focusAt(p.x / size.width, p.y / size.height)
                            Haptics.tap()
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) controller.zoomBy(zoom)
                        }
                    },
                factory = { ctx ->
                    PreviewView(ctx).also { v ->
                        v.scaleType = PreviewView.ScaleType.FILL_CENTER
                        controller.bind(owner, v)
                    }
                },
            )
            Reticle(store.settings.roiInset.toFloat(), active = !idle && state is ScanState.Scanning)
            TextLockOverlay(controller.textBoxes, controller.focusLocked)
            FocusRing(controller.focusPoint, controller.focusLocked)
        } else {
            PermissionPane(onRequestCamera)
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            TopBar(store, controller, openMenu, tip) {
                tip("Stamp a box onto every vial you confirm")
                showSessionBox = true
            }
            CaptionBar(caption)
            Spacer(Modifier.weight(1f))

            if (granted && state !is ScanState.Finished) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ZoomBar(controller, tip)
                }
                Spacer(Modifier.height(12.dp))
            }

            when {
                state is ScanState.Finished -> ResultPanel(
                    r = state.result,
                    store = store,
                    onConfirm = {
                        flash = confirm(store, controller, state.result, keepClip)
                        controller.rescan(recordThisScan)
                    },
                    onPark = {
                        park(store, controller, state.result)
                        flash = "Saved for review"
                        controller.rescan(recordThisScan)
                    },
                    onReceive = { receiving = buildReceiveDraft(store, state.result) },
                    onRescan = {
                        dismiss(store, controller, state.result)
                        controller.rescan(recordThisScan)
                    },
                    onReject = { rejecting = true },
                )
                idle -> IdleControls(
                    store, controller, keepClip,
                    onToggleKeepClip = {
                        val turningOn = !keepClip
                        keepClip = turningOn
                        Haptics.tap()
                        tip(if (turningOn) "Keeping a clip of the next scan" else "Not keeping clips")
                    },
                    tip,
                ) { Haptics.commit(); controller.rescan(recordThisScan) }
                else -> ScanningControls(state as ScanState.Scanning) {
                    controller.finishNow()
                }
            }
        }

        flash?.let {
            Text(
                it,
                color = Color.Black,
                fontWeight = FontWeight.SemiBold,
                fontSize = Type.label,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .clip(CircleShape)
                    .background(Ink.good)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    if (showSessionBox) SessionBoxSheet(store) { showSessionBox = false }

    receiving?.let { rd ->
        ReceiveSheet(
            store = store,
            draft = rd.item,
            prefillDef = rd.prefillDef,
            sources = rd.sources,
            onDismiss = { receiving = null },
            onSaved = { saved ->
                val s = controller.state
                if (s is ScanState.Finished) {
                    val clip = attachClip(store, controller, s.result, keepClip || store.settings.alwaysKeepClips)
                    store.log(record(s.result, ScanOutcome.RECEIVED, clip).copy(abNo = saved.abNo))
                }
                receiving = null
                flash = "Received AbNo ${saved.abNo}"
                Haptics.commit()
                controller.rescan(recordThisScan)
            },
        )
    }

    if (rejecting) {
        val s = controller.state
        if (s is ScanState.Finished) {
            RejectSheet(
                store = store,
                rankedCandidates = s.result.otherVials,
                onDismiss = { rejecting = false },
                onPickItem = { item ->
                    reject(store, controller, s.result, recordThisScan, trueAbNo = item.abNo)
                    rejecting = false
                    controller.rescan(recordThisScan)
                },
                onPickDefinition = { def ->
                    reject(store, controller, s.result, recordThisScan, trueCatalog = def.catalog)
                    rejecting = false
                    controller.rescan(recordThisScan)
                },
                onNotOnFile = {
                    rejecting = false
                    receiving = buildReceiveDraft(store, s.result)
                },
                onDontKnow = {
                    reject(store, controller, s.result, recordThisScan)
                    rejecting = false
                    controller.rescan(recordThisScan)
                },
            )
        } else {
            rejecting = false
        }
    }
}

// ---- Camera layer ----------------------------------------------------------

/**
 * The aiming rectangle. Not decoration: recognition is restricted to it, so it
 * is drawn exactly where the region of interest is. Drawn dark-outside-white,
 * because a single white hairline is invisible against a white vial label,
 * which is the one thing this box is always drawn over.
 */
@Composable
private fun BoxScope.Reticle(inset: Float, active: Boolean) {
    val i = inset.coerceIn(0f, 0.4f)
    val shape = RoundedCornerShape(28.dp)
    val glow by rememberInfiniteTransition(label = "reticle").animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "glow",
    )
    Box(
        Modifier
            .fillMaxWidth(1f - 2 * i)
            .fillMaxHeight(1f - 2.6f * i)
            .align(Alignment.Center)
            .border(4.dp, Color.Black.copy(alpha = 0.3f), shape)
            .padding(2.dp)
            .border(
                2.dp,
                if (active) OrbTop.copy(alpha = glow) else Color.White.copy(alpha = 0.85f),
                shape,
            )
    )
}

@Composable
private fun BoxScope.PermissionPane(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CenterFocusStrong, null,
            tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Camera access needed", color = Color.White, fontSize = Type.title, fontWeight = Type.titleWeight)
        Spacer(Modifier.height(8.dp))
        Text(
            "The scanner reads the label and barcode straight from the camera. " +
                "Nothing leaves the phone.",
            color = Color.White.copy(alpha = 0.75f), fontSize = Type.label, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Allow camera",
            color = Color.Black, fontSize = Type.heading, fontWeight = Type.headingWeight,
            modifier = Modifier
                .clip(CircleShape).background(Color.White).clickable(onClick = onRequest)
                .padding(horizontal = 22.dp, vertical = 13.dp),
        )
    }
}

/**
 * The boxes the recogniser is currently reading, matching Swift's
 * `TextLockOverlay`.
 *
 * Worth the pixels because it answers the question the operator actually has
 * when a scan is going badly: is the camera looking at the label at all? A
 * blank frame here means reframe; boxes in the wrong place mean the reticle is
 * over the neighbouring vial.
 */
@Composable
private fun BoxScope.TextLockOverlay(boxes: List<NormRect>, locked: Boolean) {
    if (boxes.isEmpty()) return
    val tint = if (locked) Color.White else OrbTop
    Canvas(Modifier.fillMaxSize()) {
        for (b in boxes) {
            // Engine boxes are bottom-left origin fractions of the frame; the
            // canvas is top-left pixels.
            val x = b.minX.toFloat() * size.width
            val w = b.width.toFloat() * size.width
            val h = b.height.toFloat() * size.height
            val y = (1f - b.maxY.toFloat()) * size.height
            drawRect(
                color = tint.copy(alpha = 0.85f),
                topLeft = Offset(x, y), size = Size(w, h),
                style = Stroke(width = 2f),
            )
        }
    }
}

/** The ring that says "focus went here". Fades itself out unless focus is
 *  locked, in which case it stays as the only indication that it is. */
@Composable
private fun BoxScope.FocusRing(point: Pair<Float, Float>?, locked: Boolean) {
    if (point == null) return
    var visible by remember(point, locked) { mutableStateOf(true) }
    LaunchedEffect(point, locked) {
        visible = true
        if (!locked) { delay(1200); visible = false }
    }
    if (!visible) return
    val (fx, fy) = point
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(fx * size.width, fy * size.height)
        val r = 34f
        drawCircle(
            color = if (locked) Color.White else Color.White.copy(alpha = 0.9f),
            radius = r, center = c, style = Stroke(width = 2.5f),
        )
        drawCircle(color = Color.Black.copy(alpha = 0.25f), radius = r + 2.5f, center = c, style = Stroke(width = 2f))
    }
}

/**
 * The lens stops this phone offers. Hidden when there is only one, so a phone
 * without an ultra-wide shows no dead control.
 */
private fun zoomLabel(z: Float): String =
    // Whole stops read as "1x"; the wide lens is whatever this phone's sensor
    // actually gives, so it is shown to one decimal rather than rounded to a
    // number the hardware does not have.
    if (z >= 1f && z == z.toInt().toFloat()) "${z.toInt()}x" else "%.1fx".format(z)

/** The caption pill. Under the top bar, so it never appears beneath the hand
 *  that summoned it. */
@Composable
private fun CaptionBar(caption: String?) {
    androidx.compose.animation.AnimatedVisibility(
        visible = caption != null,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                caption.orEmpty(),
                color = Color.White,
                fontSize = Type.caption,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun ZoomBar(controller: ScanController, tip: (String) -> Unit) {
    if (controller.zoomOptions.size < 2) return
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (z in controller.zoomOptions) {
            val on = kotlin.math.abs(controller.zoomRatio - z) < 0.1f
            Text(
                zoomLabel(z),
                color = if (on) Color.Black else Color.White,
                fontSize = Type.caption,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable {
                        controller.setZoom(z)
                        Haptics.tap()
                        tip(if (z < 1f) "Wide lens - focuses closest" else "${zoomLabel(z)} zoom")
                    }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
    }
}

// ---- Top bar ---------------------------------------------------------------

@Composable
private fun TopBar(
    store: AppStore,
    controller: ScanController,
    openMenu: () -> Unit,
    tip: (String) -> Unit,
    onSessionBox: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassCircleIcon(Icons.Filled.Menu, onClick = openMenu)
        Spacer(Modifier.width(10.dp))

        store.openAudit?.let { a ->
            WhiteCapsule(Icons.Filled.Checklist, "${a.title} · ${a.foundSet.size}/${a.expected.size}") {}
            Spacer(Modifier.width(10.dp))
        }

        // Always visible, because a stamp nobody can see is a stamp nobody can
        // correct. Filled white while it is stamping, an outline while it is not.
        val box = store.activeSessionBox
        if (box != null) WhiteCapsule(Icons.Filled.Inventory2, box, onSessionBox)
        else GlassCircleIcon(Icons.Filled.Inventory2, size = 40.dp, onClick = onSessionBox)

        Spacer(Modifier.weight(1f))

        // Focus lock sits beside the torch because they are the same kind of
        // control: both are things you reach for when the read is going badly.
        GlassCircleIcon(
            if (controller.focusLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            active = controller.focusLocked,
        ) {
            controller.toggleFocusLock()
            Haptics.tap()
            tip(if (controller.focusLocked) "Focus locked" else "Focus following the label")
        }
        Spacer(Modifier.width(10.dp))

        GlassCircleIcon(
            if (controller.torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
            active = controller.torchOn,
        ) {
            controller.toggleTorch()
            Haptics.tap()
            tip(if (controller.torchOn) "Light on" else "Light off")
        }
    }
}

@Composable
private fun WhiteCapsule(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .height(34.dp).clip(CircleShape).background(Color.White)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.Black, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.Black, fontSize = Type.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ---- Idle ------------------------------------------------------------------

@Composable
private fun IdleControls(
    store: AppStore,
    controller: ScanController,
    keepClip: Boolean,
    onToggleKeepClip: () -> Unit,
    tip: (String) -> Unit,
    onScan: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DarkCapsuleText(
            if (store.settings.handsFree) "Hands-free - hold up the next vial"
            else "Hold the vial in the frame and press scan"
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CaptionedToggle(
                if (store.settings.handsFree) Icons.Filled.GraphicEq else Icons.Filled.TouchApp,
                caption = if (store.settings.handsFree) "Hands-free" else "Manual",
                active = store.settings.handsFree,
            ) {
                Haptics.tap()
                val turningOn = !store.settings.handsFree
                store.updateSettings { c -> c.copy(handsFree = turningOn) }
                // Announced with force = true: the mode itself changing is worth
                // a word even with "speak results" off, same as Swift's
                // `Speaker.shared.say("Hands-free on", force: true)`.
                tip(
                    if (turningOn) "Hands-free - scans and saves on its own"
                    else "Hands-free off - press to scan"
                )
                if (turningOn) Speaker.say("Hands-free on", force = true) else Speaker.stop()
            }

            Spacer(Modifier.width(14.dp))
            Shutter(onScan)
            Spacer(Modifier.width(14.dp))

            // "Always keep" and hands-free both force recording regardless of
            // this toggle (see `recordThisScan` in `ScanScreen`), so the icon
            // reflects the effective state even when the operator cannot turn
            // it off - same as Swift always showing "video.fill" in that case.
            val forcedOn = store.settings.alwaysKeepClips || store.settings.handsFree
            val effectivelyOn = keepClip || forcedOn
            CaptionedToggle(
                if (effectivelyOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                caption = if (forcedOn) "Clip always" else if (keepClip) "Clip on" else "Clip off",
                active = effectivelyOn,
                // Red rather than the default white: this is the one control on
                // the bar that decides whether video is written, and "on" for it
                // should not look like "on" for hands-free next to it.
                activeColor = Ink.bad,
            ) {
                // Nothing to toggle if binding VideoCapture failed on this
                // device - the icon still reflects the (unreachable) setting
                // rather than lying about it.
                if (controller.clipRecordingUnsupported) return@CaptionedToggle
                Haptics.tap()
                // Parity with Swift's `flash(...)` on this toggle, which Android
                // was missing: the caption says the new state, the tip says what
                // it means. Say nothing misleading when a setting is holding it
                // on and the tap cannot turn it off.
                if (forcedOn) {
                    tip(
                        if (store.settings.handsFree) "Hands-free always keeps a clip"
                        else "\"Always keep clips\" is on in Settings"
                    )
                } else {
                    onToggleKeepClip()
                    tip(
                        if (!keepClip) "Keeping a clip of the next scan"
                        else "Not keeping clips"
                    )
                }
            }
        }
    }
}

/**
 * A round toggle with its state written underneath.
 *
 * The two controls flanking the shutter used to say nothing: on was a white
 * circle, off was a grey one, and which was which is only obvious if you
 * already know. The caption is the state, not the control's name, so there is
 * nothing to infer - and it is what makes "am I recording?" answerable without
 * tapping to find out.
 */
@Composable
private fun CaptionedToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    caption: String,
    active: Boolean,
    activeColor: Color = Color.White,
    onClick: () -> Unit,
) {
    // Fixed width so the row does not shift when the caption changes length.
    // Wide enough for the longest caption either toggle uses.
    Column(
        Modifier.width(92.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassCircleIcon(icon, size = 50.dp, active = active, activeColor = activeColor, onClick = onClick)
        Spacer(Modifier.height(4.dp))
        Text(
            caption,
            color = if (active) activeColor else Color.White.copy(alpha = 0.65f),
            fontSize = Type.caption,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun Shutter(onClick: () -> Unit) {
    Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(86.dp).clip(CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
        )
        Box(
            Modifier.size(74.dp).clip(CircleShape).background(Color.White).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.CenterFocusStrong, null, tint = Color.Black, modifier = Modifier.size(28.dp))
        }
    }
}

// ---- Scanning --------------------------------------------------------------

@Composable
private fun ScanningControls(state: ScanState.Scanning, onReadNow: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth().clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Orb()
            Spacer(Modifier.width(10.dp))
            Text(state.hint, color = Color.White, fontSize = Type.body, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "${state.ocrFrames} reads",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = Type.caption, fontFamily = Type.monoFamily,
            )
        }
        Spacer(Modifier.height(12.dp))

        // The bar tracks readiness to answer, not frames burned: elapsed time,
        // OCR passes and how much of the label has actually been turned into
        // view. A bar that fills at a fixed rate tells the user nothing about
        // whether rotating further would help.
        LinearProgressIndicator(
            progress = { state.progress.toFloat() },
            color = Color.White, trackColor = Color.White.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth(0.8f).height(3.dp),
        )
        Spacer(Modifier.height(12.dp))

        // Faces of the label seen so far. This is the one piece of engine state
        // worth showing outright: "keep rotating" is a nag, but a pip that
        // lights up when a new side registers tells the user what the scanner is
        // actually waiting for.
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier.width(16.dp).height(3.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = if (i < state.views) 0.95f else 0.25f))
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        Text(
            "Read what you have",
            color = Color.Black, fontSize = Type.label, fontWeight = Type.labelWeight,
            modifier = Modifier
                .clip(CircleShape).background(Color.White).clickable(onClick = onReadNow)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

/** The pulsing gradient dot iOS uses to say "the engine is working". */
@Composable
private fun Orb() {
    val t = rememberInfiniteTransition(label = "orb")
    val s by t.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse",
    )
    Box(
        Modifier.size(22.dp * s).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(OrbTop, OrbBottom)))
    )
}

@Composable
private fun DarkCapsuleText(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.85f), fontSize = Type.label, fontWeight = Type.labelWeight,
        modifier = Modifier
            .clip(CircleShape).background(Color.Black.copy(alpha = 0.38f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

// ---- Result ----------------------------------------------------------------

@Composable
private fun ResultPanel(
    r: ScanResult,
    store: AppStore,
    onConfirm: () -> Unit,
    onPark: () -> Unit,
    onReceive: () -> Unit,
    onRescan: () -> Unit,
    onReject: () -> Unit,
) {
    // The result is a themed card on the camera, not another glass overlay: once
    // there is an answer to read the page stops being a viewfinder.
    Card(Modifier.heightIn(max = 460.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val tint = when (r.verdict) {
                Verdict.CERTAIN, Verdict.CONFIDENT -> Ink.good
                Verdict.AMBIGUOUS -> Ink.warn
                Verdict.NOT_FOUND -> Ink.accent
            }
            val headline = if (r.readNothing) "Nothing read" else when (r.verdict) {
                Verdict.CERTAIN, Verdict.CONFIDENT -> "Identified"
                Verdict.AMBIGUOUS -> "Not sure"
                Verdict.NOT_FOUND -> "New vial"
            }
            val sub = if (r.readNothing) "The camera never read the label" else when (r.verdict) {
                Verdict.CERTAIN -> "Matched by barcode"
                Verdict.CONFIDENT -> "Matched from label text"
                Verdict.AMBIGUOUS -> "Best guess - confirm before use"
                Verdict.NOT_FOUND -> "Nothing on file matches this label"
            }

            Text(headline, color = tint, fontSize = Type.display, fontWeight = Type.displayWeight)
            Text(sub, color = Ink.textSecond, fontSize = Type.label)
            Spacer(Modifier.height(14.dp))

            r.definition?.let { def ->
                Text(def.name, color = Ink.text, fontSize = Type.title, fontWeight = Type.titleWeight)
                Text(
                    "${def.vendor} ${def.catalog} · clone ${def.clone}",
                    color = Ink.textSecond, fontSize = Type.label,
                )
            }

            val vial = r.vial
            if (vial != null) {
                Spacer(Modifier.height(12.dp))
                KeyValueRow("AbNo", vial.abNo)
                KeyValueRow("lot", vial.lot)
                KeyValueRow("where", listOf(vial.location, vial.box).filter { it.isNotEmpty() }.joinToString(" · "))
                KeyValueRow("conc", vial.concentration)
                KeyValueRow("owner", vial.owner)
                if (r.otherVials.isNotEmpty()) {
                    KeyValueRow("also on file", r.otherVials.joinToString(", ") { "AbNo ${it.abNo}" })
                }
            } else if (r.lotNotOnRecord) {
                Spacer(Modifier.height(12.dp))
                Text("Lot not on record - a new lot of a known antibody", color = Ink.warn, fontSize = Type.label)
                Mono(r.otherVials.joinToString(", ") { "AbNo ${it.abNo} lot ${it.lot}" })
            }

            Spacer(Modifier.height(12.dp))
            Mono(LabelField.entries.mapNotNull { f -> r.fields[f]?.let { "$f=$it" } }.joinToString("  "))
            Mono(r.reasons.joinToString(", "))
            store.activeSessionBox?.let { Mono("will file to box $it", Ink.accent) }

            Spacer(Modifier.height(16.dp))
            if (vial != null) {
                PrimaryButton("Confirm this vial is here", onClick = onConfirm)
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Rescan", Modifier.weight(1f), onRescan)
                SecondaryButton("Review later", Modifier.weight(1f), onPark)
                SecondaryButton("Receive", Modifier.weight(1f), onReceive)
            }
            // A proposed match is exactly what needs a first-class no - a
            // labelling session lives or dies on how cheap this is to reach.
            // NOT_FOUND has no proposal to reject, so it stays off this card.
            if (r.verdict != Verdict.NOT_FOUND) {
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Wrong match", Modifier.fillMaxWidth(), onReject)
            }
        }
    }
}

// ---- Actions ---------------------------------------------------------------

private fun meetsBar(v: Verdict, bar: Verdict): Boolean {
    val rank = mapOf(Verdict.CERTAIN to 3, Verdict.CONFIDENT to 2, Verdict.AMBIGUOUS to 1, Verdict.NOT_FOUND to 0)
    return (rank[v] ?: 0) >= (rank[bar] ?: 2)
}

fun record(r: ScanResult, outcome: ScanOutcome, clipFile: String? = null): ScanRecord = ScanRecord(
    verdict = r.verdict.name,
    outcome = outcome,
    abNo = r.vial?.abNo,
    definitionName = r.definition?.name,
    catalog = r.fields[LabelField.CATALOG] ?: r.definition?.catalog,
    lot = r.fields[LabelField.LOT],
    vendor = r.definition?.vendor,
    clone = r.definition?.clone,
    barcode = r.barcode?.raw,
    score = r.score,
    margin = r.margin,
    reasons = r.reasons,
    framesUsed = r.framesUsed,
    lotNotOnRecord = r.lotNotOnRecord,
    clipFile = clipFile,
)

// ---- Clips ------------------------------------------------------------

/**
 * Move the just-recorded temp file into permanent storage and write its
 * diagnostic sidecar, or discard it. `wantsKeep` folds together the per-scan
 * toggle and "always keep clips" - callers pass the already-decided value so
 * this function only has to act on it.
 */
private fun attachClip(store: AppStore, controller: ScanController, r: ScanResult, wantsKeep: Boolean): String? {
    val temp = if (wantsKeep) controller.consumePendingClip() else null
    val name = store.keepClip(temp)
    if (name == null) controller.discardPendingClip() else store.writeClipMetadata(name, clipMetadata(store, controller, r))
    return name
}

/**
 * Everything a reviewer would need to make sense of the clip without the app:
 * what the engine concluded, what fed that, and the conditions the frames were
 * shot under. See `ClipMetadata`'s doc for what is deliberately left out.
 */
private fun clipMetadata(store: AppStore, controller: ScanController, r: ScanResult): ClipMetadata {
    val bc = r.barcode
    val s = store.settings
    return ClipMetadata(
        verdict = r.verdict.name,
        definitionName = r.definition?.name,
        abNo = r.vial?.abNo,
        score = r.score,
        margin = r.margin,
        reasons = r.reasons,
        fields = r.fields.entries.associate { (field, value) -> field.name to value },
        barcode = bc?.let { BarcodeMetadata(raw = it.raw, symbology = it.symbology, catalog = it.catalog, lot = it.lot) },
        capture = CaptureMetadata(
            zoomRatio = controller.zoomRatio,
            torchOn = controller.torchOn,
            focusLocked = controller.focusLocked,
            framesSeen = controller.lastFramesSeen,
            ocrFramesUsed = controller.lastOcrFrames,
            roiInset = s.roiInset,
            maxOCRFrames = s.maxOCRFrames,
            minScanSeconds = s.minScanSeconds,
            maxScanSeconds = s.maxScanSeconds,
        ),
        device = DeviceMetadata(
            model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            androidVersion = android.os.Build.VERSION.RELEASE ?: android.os.Build.VERSION.SDK_INT.toString(),
            appVersion = BuildConfig.VERSION_NAME,
        ),
    )
}

/**
 * Confirm: the vial was physically seen. Returns the banner text, which names
 * the box when the session box actually filed it somewhere new - a stamp nobody
 * can see is a stamp nobody can correct.
 */
private fun confirm(store: AppStore, controller: ScanController, r: ScanResult, keepClip: Boolean): String {
    val abNo = r.vial?.abNo ?: run { controller.discardPendingClip(); return "Nothing to confirm" }
    val clip = attachClip(store, controller, r, keepClip || store.settings.alwaysKeepClips)
    val open = store.openAudit
    val logged = store.log(record(r, ScanOutcome.CONFIRMED, clip).copy(auditID = open?.id))
    store.markConfirmed(abNo, logged.id)
    val filed = store.stampSessionBox(abNo)
    if (open != null) store.recordFound(abNo, open.id, method = "scan", scanID = logged.id)
    Haptics.commit()
    var text = "Confirmed AbNo $abNo"
    if (filed != null) text += " · filed to $filed"
    if (open != null) text += " · counted in ${open.title}"
    return text
}

/** A parked scan always keeps its video, whatever the toggle says - that is
 *  the point of parking: someone else has to be able to see what the camera
 *  saw. Mirrors Swift's `park()`. */
private fun park(store: AppStore, controller: ScanController, r: ScanResult) {
    val clip = attachClip(store, controller, r, wantsKeep = true)
    store.log(record(r, ScanOutcome.REVIEW, clip))
    Haptics.commit()
    // A scan the audit could not name is still evidence that something was in
    // the drawer, so record it as an unexpected item rather than dropping it.
    val open = store.openAudit
    if (open != null && r.vial == null) {
        store.recordExtra(
            AuditExtra(
                label = r.definition?.name ?: (r.fields[LabelField.CATALOG]?.let { "Cat $it" } ?: "Unreadable label"),
                catalog = r.fields[LabelField.CATALOG],
                lot = r.fields[LabelField.LOT],
            ),
            open.id,
        )
    }
}

/**
 * The operator (or a hands-free pass that met no bar) walked away from a
 * result without confirming or parking it. Swift's `reject()` always throws
 * the clip away; "always keep clips" overrides that here, on purpose - a NEW
 * VIAL or an uncorroborated read nobody acted on is exactly the failure a
 * labelling run needs the video for. A blank read carries no verdict worth
 * keeping a row for, so it is skipped either way, same as Swift.
 */
private fun dismiss(store: AppStore, controller: ScanController, r: ScanResult) {
    if (store.settings.alwaysKeepClips && !r.readNothing) {
        val clip = attachClip(store, controller, r, wantsKeep = true)
        if (clip != null) store.log(record(r, ScanOutcome.REJECTED, clip))
    } else {
        controller.discardPendingClip()
    }
}

/**
 * A rejected scan is worth logging because someone looked at a verdict and
 * disagreed with it. A scan that read nothing carries no verdict to disagree
 * with, and logging it only fills the history with blank rows.
 *
 * The clip is kept whenever clips are being kept at all. A rejection the
 * operator took the trouble to correct is the single most valuable row a
 * labelling run produces: it says what the matcher answered and what the
 * answer should have been, and the video is the only way to see why.
 */
private fun reject(
    store: AppStore,
    controller: ScanController,
    r: ScanResult,
    wantsClip: Boolean,
    trueAbNo: String? = null,
    trueCatalog: String? = null,
) {
    if (r.readNothing) {
        controller.discardPendingClip()
        return
    }
    val clip = attachClip(store, controller, r, wantsClip)
    store.log(
        record(r, ScanOutcome.REJECTED, clip)
            .copy(trueAbNo = trueAbNo, trueCatalog = trueCatalog),
    )
    Haptics.commit()
}

/** Pre-fill a receive form from what the scan read.
 *
 * NEW VIAL - nothing on file matched - is the common outcome, not the edge
 * case: most clips in the sample set end here. So when `r.definition` is
 * null this also builds the `DefRecord` the same scan read enough for, using
 * exactly the fields catalog/clone/vendor/isotype that a definition needs,
 * instead of leaving `vialName` a "Cat 12345" placeholder for someone to
 * notice and fix by hand later. */
fun buildReceiveDraft(store: AppStore, r: ScanResult): ReceiveDraft {
    val sources = mutableMapOf<String, FieldSource>()

    val lot = r.fields[LabelField.LOT] ?: r.barcode?.lot ?: ""
    if (lot.isNotEmpty()) sources["Lot"] = if (r.barcode?.lot != null) FieldSource.BARCODE else FieldSource.OCR
    val concentration = r.fields[LabelField.CONCENTRATION] ?: ""
    if (concentration.isNotEmpty()) sources["Concentration"] = FieldSource.OCR

    // Remembered rather than blank - see `rememberReceivingDefaults` at the
    // call site for how these get updated after a save.
    val owner = store.settings.defaultOwner
    if (owner.isNotEmpty()) sources["Owner"] = FieldSource.REMEMBERED
    val location = store.settings.defaultLocation
    if (location.isNotEmpty()) sources["Location"] = FieldSource.REMEMBERED
    val box = store.activeSessionBox ?: store.settings.defaultBox
    if (box.isNotEmpty()) sources["Box"] = FieldSource.REMEMBERED

    val prefillDef = if (r.definition == null) definitionDraft(r, sources) else null

    val item = ItemRecord(
        abNo = store.nextAbNo(),
        definitionName = r.definition?.name ?: "",
        vialName = r.definition?.name ?: prefillDef?.name ?: "",
        lot = lot,
        concentration = concentration,
        location = location,
        box = box,
        owner = owner,
    )
    return ReceiveDraft(item, prefillDef, sources)
}

/** A `DefRecord` built from whatever the scan read, so the operator corrects
 *  OCR mistakes instead of retyping a label from scratch. */
private fun definitionDraft(r: ScanResult, sources: MutableMap<String, FieldSource>): DefRecord? {
    val catalog = r.fields[LabelField.CATALOG] ?: r.barcode?.catalog ?: ""
    val clone = r.fields[LabelField.CLONE] ?: ""
    val vendor = r.fields[LabelField.VENDOR] ?: ""
    val isotype = r.fields[LabelField.ISOTYPE] ?: ""
    if (catalog.isEmpty() && clone.isEmpty() && vendor.isEmpty()) return null

    if (catalog.isNotEmpty()) sources["Catalog #"] = if (r.barcode?.catalog != null) FieldSource.BARCODE else FieldSource.OCR
    if (clone.isNotEmpty()) sources["Clone"] = FieldSource.OCR
    if (isotype.isNotEmpty()) sources["Isotype"] = FieldSource.OCR
    if (vendor.isNotEmpty()) sources["Vendor"] = FieldSource.OCR

    var name = listOfNotNull(vendor.ifEmpty { null }, clone.ifEmpty { null }?.let { "Clone $it" })
        .joinToString(" ")
    if (name.isEmpty() && catalog.isNotEmpty()) name = "Cat $catalog"
    return DefRecord(name = name, clone = clone, isotype = isotype, vendor = vendor, catalog = catalog, isLocal = true)
}
