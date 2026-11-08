package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.PartyUtils
import com.github.noamm9.utils.ThreadUtils
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.DungeonListener.DualTime
import com.github.noamm9.utils.dungeons.DungeonListener.DualTime.Companion.minus
import com.github.noamm9.utils.location.LocationUtils
import java.util.Locale

object LagAnnounce: Feature(
    name = "Lag Announce",
    description = "Announces the estimated time lost to server lag at the end of a dungeon run."
) {
    private val naTag by ToggleSetting("[NA] Tag", true)
        .withDescription("Adds [NA] prefix to the beginning of the message.")

    private val precision by SliderSetting("Precision", 4, 2, 7, 1)
        .withDescription("Number of decimal places to show for the lag time.")

    private val delay by SliderSetting("Delay", 5, 0, 50, 1, "t")
        .withDescription("Delay in ticks before sending the lag message after the run ends.")

    private var startNano = 0L
    private var startTicks = 0L

    override fun init() {
        register<WorldChangeEvent> {
            startNano = 0L
            startTicks = 0L
        }

        register<ChatMessageEvent> {
            if (! LocationUtils.inDungeon) return@register
            if (event.unformattedText == "[NPC] Mort: Here, I found this map when I first entered the dungeon.") {
                startNano = System.nanoTime()
                startTicks = DungeonListener.currentTime
            }
        }

        register<DungeonEvent.RunEndedEvent> {
            val endNano = System.nanoTime()
            val endTicks = DungeonListener.dungeonEndTime ?: DungeonListener.currentTime

            val lagSeconds = if (startNano > 0L) {
                val realSecs = (endNano - startNano) / 1_000_000_000.0
                val idealSecs = (endTicks - startTicks) / 20.0
                (realSecs - idealSecs).coerceAtLeast(0.0)
            } else {
                val start = DungeonListener.dungeonStartTime ?: return@register
                val total = DualTime(endTicks) - start
                val subMs = (endNano % 1_000_000L) / 1_000_000_000.0
                (((total.real - total.ticks * 50) / 1000.0) + subMs).coerceAtLeast(0.0)
            }

            val formatted = String.format(Locale.US, "%.${precision.value}fs", lagSeconds)

            val announce = Runnable {
                if (PartyUtils.isInParty && PartyUtils.partySize > 1) {
                    val prefix = if (naTag.value) "[NA] " else ""
                    ChatUtils.sendPartyMessage("${prefix}Estimated Time Lost to Lag: $formatted")
                } else {
                    val msg = "Estimated Time Lost to Lag: &c$formatted"
                    if (naTag.value) ChatUtils.modMessage(msg) else ChatUtils.chat(msg)
                }
            }

            if (delay.value <= 0) announce.run()
            else ThreadUtils.scheduledTask(delay.value, announce)
        }
    }
}
