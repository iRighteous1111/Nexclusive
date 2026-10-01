package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.DropdownSetting
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
    description = "Prevents Simon Says and SS skip clicks from breaking during low TPS."
) {
    private val mode by DropdownSetting("Mode", 0, listOf("TPS Check", "Queue Clicks"))

    // Mode 0: TPS Check
    private val tpsThreshold by SliderSetting("TPS Threshold", 19.0, 10.0, 20.0, 0.5).section("TPS Check").showIf { mode.value == 0 }

    // Mode 1: Queue Clicks
    private val extraDelay by SliderSetting("Extra Delay", 0, 0, 2, 1, "t").section("Queue Clicks").showIf { mode.value == 1 }
    private val resyncTimeout by SliderSetting("Resync Timeout", 400, 150, 1000, 25, "ms").showIf { mode.value == 1 }
    private val preserveRhythm by ToggleSetting("Preserve Rhythm", true).showIf { mode.value == 1 }

    // General
    private val displayQueue by ToggleSetting("Display Queue", false)

    private data class QueuedClick(val isLeft: Boolean, val userDelta: Long, val timestamp: Long)

    private val queue = ArrayDeque<QueuedClick>()
    private var lastUserClickTime = 0L
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
        val userDelta = if (lastUserClickTime == 0L) 0L else (now - lastUserClickTime).coerceIn(0L, 500L)
        lastUserClickTime = now

        when (mode.value) {
            0 -> { // TPS Check Mode
                val tps = ServerUtils.tps
                // If TPS is healthy and tick has advanced, pass through immediately
                if (queue.isEmpty() && tps >= tpsThreshold.value.toFloat() && ticksElapsed > 0) {
                    lastSentTick = currentTick
                    lastSentTime = now
                    return
                }

                // Low TPS or same tick burst: intercept and queue to prevent corruption
                event.cancel()
                if (queue.size < 4) {
                    queue.add(QueuedClick(isLeft, userDelta, now))
                }
            }

            1 -> { // Queue Clicks Mode
                val tps = ServerUtils.tps.coerceIn(5f, 20f)
                val safeTickMs = ((1000f / tps) * (1 + extraDelay.value)).toLong().coerceIn(35L, 250L)

                // If TPS is normal, no queue active, and tick boundary met: pass through
                if (queue.isEmpty() && tps >= 19.5f && ticksElapsed > extraDelay.value && msElapsed >= safeTickMs) {
                    lastSentTick = currentTick
                    lastSentTime = now
                    return
                }

                // Otherwise, buffer input rhythm safely
                event.cancel()
                if (queue.size < 4) {
                    queue.add(QueuedClick(isLeft, userDelta, now))
                }
            }
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
        val timeoutMs = if (mode.value == 0) 350L else resyncTimeout.value.toLong()

        while (queue.isNotEmpty() && now - queue.peek().timestamp > timeoutMs) {
            queue.poll()
        }
        if (queue.isEmpty()) return

        val currentTick = DungeonListener.currentTime
        val ticksElapsed = currentTick - lastSentTick
        val msElapsed = now - lastSentTime
        val tps = ServerUtils.tps.coerceIn(5f, 20f)

        val nextClick = queue.peek() ?: return

        val canDispatch = when (mode.value) {
            0 -> { // TPS Check: dispatch on next available server tick
                ticksElapsed > 0
            }
            1 -> { // Queue Clicks: safe server tick interval + optional user rhythm
                val safeTickMs = ((1000f / tps) * (1 + extraDelay.value)).toLong().coerceIn(35L, 250L)
                val requiredDelay = if (preserveRhythm.value) maxOf(safeTickMs, nextClick.userDelta) else safeTickMs
                ticksElapsed > extraDelay.value && msElapsed >= requiredDelay
            }
            else -> false
        }

        if (canDispatch) {
            val target = SimonSaysBridge.getValidButton()
            if (target == null) {
                resetQueue()
                return
            }

            if (! SimonSaysBridge.isLookingAtButton(target)) {
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
        lastUserClickTime = 0L
        lastSentTick = - 1L
        lastSentTime = 0L
        dispatchedClicks = 0
    }
}
