package org.fcitx.fcitx5.android.plugin.quicksend.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import org.fcitx.fcitx5.android.plugin.quicksend.R

/** 延迟段在 Compose 界面中统一使用、可随资源限定符切换日夜模式的琥珀色语义样式。 */
object DelayVisualStyle {
    @Immutable
    data class Colors(
        val container: Color,
        val content: Color,
        val border: Color,
        val dialogContainer: Color
    )

    @Composable
    fun colors() = Colors(
        container = colorResource(R.color.qs_delay_container),
        content = colorResource(R.color.qs_delay_content),
        border = colorResource(R.color.qs_delay_border),
        dialogContainer = colorResource(R.color.qs_delay_dialog_container)
    )
}
