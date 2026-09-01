package dev.morphhid.ui.renderer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.morphhid.core.profile.LayoutSpec
import dev.morphhid.core.profile.Profile
import dev.morphhid.core.profile.ScreenSpec
import dev.morphhid.core.profile.WidgetSpec

/**
 * Renders a profile's UI: horizontal pager of screens, each laid out as a
 * grid (or rows) of generic widgets.
 *
 * @param requestedScreenId screen the host wants shown (e.g. a macro `page`
 *   step switched screens); the pager animates to it when it changes.
 */
@Composable
fun ProfileRenderer(
    profile: Profile,
    host: WidgetHost,
    ledStates: Map<String, Boolean>,
    modifier: Modifier = Modifier,
    requestedScreenId: String? = null,
) {
    val screens = profile.ui?.screens.orEmpty()
    if (screens.isEmpty()) {
        Text(
            text = "This profile defines no screens.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(24.dp),
        )
        return
    }
    val pagerState = rememberPagerState(pageCount = { screens.size })

    LaunchedEffect(requestedScreenId, screens.size) {
        val id = requestedScreenId ?: return@LaunchedEffect
        val index = screens.indexOfFirst { it.id == id }
        if (index >= 0 && index != pagerState.currentPage) {
            pagerState.animateScrollToPage(index)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            ScreenContent(screens[page], host, ledStates)
        }
        if (screens.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                screens.forEachIndexed { i, screen ->
                    val active = i == pagerState.currentPage
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: ScreenSpec, host: WidgetHost, ledStates: Map<String, Boolean>) {
    val columns = (screen.layout as? LayoutSpec.Grid)?.columns ?: 3
    Column(Modifier.fillMaxSize()) {
        if (screen.title.isNotBlank()) {
            Text(
                text = screen.title,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceIn(1, 8)),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(
                items = screen.widgets,
                span = { _, item ->
                    if (item is WidgetSpec.KeyGrid || item is WidgetSpec.Label || (item is WidgetSpec.PointerPad && item.wide)) {
                        GridItemSpan(maxLineSpan)
                    } else {
                        GridItemSpan(1)
                    }
                },
            ) { _, widget ->
                when (widget) {
                    is WidgetSpec.Button -> ButtonWidget(widget, host)
                    is WidgetSpec.Toggle -> ToggleWidget(widget, host)
                    is WidgetSpec.Joystick -> JoystickWidget(widget, host)
                    is WidgetSpec.Dpad -> DpadWidget(widget, host)
                    is WidgetSpec.Slider -> SliderWidget(widget, host)
                    is WidgetSpec.PointerPad -> PointerPadWidget(widget, host)
                    is WidgetSpec.TrackPoint -> TrackPointWidget(widget, host)
                    is WidgetSpec.KeyGrid -> KeyGridWidget(widget, host)
                    is WidgetSpec.Led -> LedWidget(widget, ledStates)
                    is WidgetSpec.Label -> LabelWidget(widget)
                }
            }
        }
    }
}
