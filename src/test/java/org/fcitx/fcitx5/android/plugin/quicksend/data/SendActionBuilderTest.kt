package org.fcitx.fcitx5.android.plugin.quicksend.data

import android.view.KeyEvent
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SendActionBuilderTest {

    @Test
    fun combination_keepsTmuxPrefixAndDelayInOrder() {
        val actions = SendActionBuilder.build(
            listOf(key("CTRL"), key("B"), delay(80), key("W")),
            QuickSendEntry.MODE_COMBINATION
        )

        assertEquals(
            listOf(
                SendAction.KeyCombination(listOf(KeyEvent.KEYCODE_CTRL_LEFT), KeyEvent.KEYCODE_B),
                SendAction.Delay(80),
                SendAction.KeyPress(KeyEvent.KEYCODE_W)
            ),
            actions
        )
    }

    @Test
    fun combination_doesNotCrossDelayWhenBuildingChord() {
        val actions = SendActionBuilder.build(
            listOf(key("CTRL"), delay(80), key("B")),
            QuickSendEntry.MODE_COMBINATION
        )

        assertEquals(
            listOf(
                SendAction.KeyPress(KeyEvent.KEYCODE_CTRL_LEFT),
                SendAction.Delay(80),
                SendAction.KeyPress(KeyEvent.KEYCODE_B)
            ),
            actions
        )
    }

    @Test
    fun sequence_keepsTextAsOneActionAndPreservesTextBraces() {
        val actions = SendActionBuilder.build(
            listOf(text("{80}"), delay(80), key("ENTER")),
            QuickSendEntry.MODE_SEQUENCE
        )

        assertEquals(
            listOf(
                SendAction.Text("{80}"),
                SendAction.Delay(80),
                SendAction.KeyPress(KeyEvent.KEYCODE_ENTER)
            ),
            actions
        )
    }

    @Test
    fun combination_supportsMultipleChordsInOneSequence() {
        val actions = SendActionBuilder.build(
            listOf(key("CTRL"), key("C"), key("CTRL"), key("V")),
            QuickSendEntry.MODE_COMBINATION
        )

        assertEquals(
            listOf(
                SendAction.KeyCombination(listOf(KeyEvent.KEYCODE_CTRL_LEFT), KeyEvent.KEYCODE_C),
                SendAction.KeyCombination(listOf(KeyEvent.KEYCODE_CTRL_LEFT), KeyEvent.KEYCODE_V)
            ),
            actions
        )
    }

    private fun text(value: String) = ContentSegment(ContentSegment.TYPE_TEXT, value)
    private fun key(value: String) = ContentSegment(ContentSegment.TYPE_KEY, value)
    private fun delay(value: Long) = ContentSegment(ContentSegment.TYPE_DELAY, value.toString())
}
