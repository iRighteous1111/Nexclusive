package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.PlayerInteractEvent
import com.github.noamm9.event.impl.RenderOverlayEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.floor7.devices.SimonSays
import com.github.noamm9.nexclusive.utils.SimonSaysBridge
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.ServerUtils
import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import java.awt.Color
import java.util.ArrayDeque

object QSS: Feature(
    name = "Q-SS",
    description = "Queues Simon Says button clicks to prevent skips and clicks from failing during low TPS."
) {
    private val extraDelay by SliderSetting("Extra Delay", 0, 0, 2, 1, "t").withDescription("Extra server ticks to wait between queued clicks (0 = max speed, 1 click per server tick).")
    private val resyncTimeout by SliderSetting("Resync Timeout", 400, 150, 1000, 25, "ms").withDescription("Automatically flushes the queue if clicks stay buffered longer than this to prevent desync.")
    private val tpsSync by ToggleSetting("TPS Sync", true).withDescription("Dynamically adapts click spacing when server TPS drops.")
    private val displayQueue by ToggleSetting("Display Queue", false).withDescription("Shows remaining queued clicks on screen.")

    private data class QueuedClick(val isLeft: Boolean, val timestamp: Long)

    private val queue = ArrayDeque<QueuedClick>()
    private var lastSentTick = - 1L
    private var lastSentTime = 0L
    private var dispatchedClicks = 0

    override fun init() {
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK>(EventPriority.HIGHEST) {
            handleInteract(event, event.pos, isLeft = false)
        }

        register<PlayerInteractEvent.LEFT_CLICK.BLOCK>(EventPriority.HIGHEST) {
            handleInteract(event, event.pos, isLeft = true)
        }

        register<TickEvent.Server> {
            processQueue()
        }

        register<TickEvent.Start> {
            processQueue()
        }

        register<WorldChangeEvent> {
            resetQueue()
        }

        register<RenderOverlayEvent> {
            if (! enabled || ! displayQueue.value || queue.isEmpty()) return@register
            if (! SimonSaysBridge.isAtSSDevice()) return@register

            Resolution.push(event.context)
            val text = "§bQ-SS: §e${queue.size}"
            event.context.drawString(
                text,
                (Resolution.width / 2f) + 10f,
                (Resolution.height / 2f) + 10f,
                Color.WHITE,
                shadow = true
            )
            Resolution.pop(event.context)
        }
    }

    private fun handleInteract(event: PlayerInteractEvent, pos: BlockPos, isLeft: Boolean) {
        if (! enabled || mc.screen != null || ! SimonSays.enabled) return
        if (! SimonSaysBridge.isAtSSDevice() || ! SimonSaysBridge.isDeviceInClickingPhase()) {
            if (queue.isNotEmpty()) resetQueue()
            return
        }
        if (! SimonSaysBridge.isSSButton(pos)) return
        if (WorldUtils.getBlockAt(pos) != Blocks.STONE_BUTTON) return

        if (dispatchedClicks > 0) {
            dispatchedClicks --
            lastSentTick = DungeonListener.currentTime
            lastSentTime = System.currentTimeMillis()
            return
        }

        val currentTick = DungeonListener.currentTime
        val now = System.currentTimeMillis()
        val ticksElapsed = currentTick - lastSentTick
        val msElapsed = now - lastSentTime

        val minIntervalMs = if (tpsSync.value) {
            val tps = ServerUtils.tps.coerceIn(5f, 20f)
            ((1000f / tps) * (1 + extraDelay.value)).toLong().coerceIn(35L, 250L)
        } else {
            ((1 + extraDelay.value) * 50L).coerceAtLeast(35L)
        }

        if (queue.isEmpty() && ticksElapsed > extraDelay.value && msElapsed >= minIntervalMs) {
            lastSentTick = currentTick
            lastSentTime = now
            return
        }

        event.cancel()

        if (queue.size < 4) {
            queue.add(QueuedClick(isLeft, now))
        }
    }

    private fun processQueue() {
        if (! enabled || queue.isEmpty() || mc.screen != null) return
        if (! SimonSaysBridge.isAtSSDevice() || ! SimonSaysBridge.isDeviceInClickingPhase()) {
            resetQueue()
            return
        }

        if (SimonSaysBridge.getSolutionList().isNullOrEmpty()) {
            resetQueue()
            return
        }

        val now = System.currentTimeMillis()

        while (queue.isNotEmpty() && now - queue.peek().timestamp > resyncTimeout.value.toLong()) {
            queue.poll()
        }
        if (queue.isEmpty()) return

        val currentTick = DungeonListener.currentTime
        val ticksElapsed = currentTick - lastSentTick
        val msElapsed = now - lastSentTime

        val minIntervalMs = if (tpsSync.value) {
            val tps = ServerUtils.tps.coerceIn(5f, 20f)
            ((1000f / tps) * (1 + extraDelay.value)).toLong().coerceIn(35L, 250L)
        } else {
            ((1 + extraDelay.value) * 50L).coerceAtLeast(35L)
        }

        if (ticksElapsed > extraDelay.value && msElapsed >= minIntervalMs) {
            if (SimonSaysBridge.getValidButton() == null) {
                resetQueue()
                return
            }
            val click = queue.poll() ?: return
            dispatchedClicks ++
            lastSentTick = currentTick
            lastSentTime = now

            if (click.isLeft) {
                PlayerUtils.leftClick()
            } else {
                PlayerUtils.rightClick()
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        resetQueue()
    }

    private fun resetQueue() {
        queue.clear()
        lastSentTick = - 1L
        lastSentTime = 0L
        dispatchedClicks = 0
    }
}
