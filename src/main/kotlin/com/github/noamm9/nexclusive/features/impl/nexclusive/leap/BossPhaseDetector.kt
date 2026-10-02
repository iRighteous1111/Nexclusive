package com.github.noamm9.nexclusive.features.impl.nexclusive.leap

import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket

object BossPhaseDetector {
    private val mc get() = Minecraft.getInstance()

    enum class Phase {
        NONE,
        MAXOR,
        STORM,
        GOLDOR,
        NECRON,
        P5
    }

    var currentPhase = Phase.NONE
        private set

    // Maxor
    var isMaxorDead = false
        private set

    // Storm sequence: 0 = Checkpoint (Purple), 1 = Yellow Crusher, 2 = SSC
    var stormStep = 0
        private set
    private var oofCount = 0

    // Goldor sections (1 = S1, 2 = S2, 3 = S3, 4 = S4)
    var goldorSection = 1
        private set
    var s1LeapIndex = 0
        private set
    var s2LeapIndex = 0
        private set
    var s3LeapIndex = 0
        private set
    var s4LeapIndex = 0
        private set

    private var s1GateBlown = false
    private var s2GateBlown = false
    private var s3GateBlown = false

    // Necron (P4)
    var isMiddleActive = false
        private set
    var isPre4Done = false
        private set

    // P5 (M7)
    var isP5StartActive = false
        private set
    var isRelicActive = false
        private set
    private var p5StartTime = 0L
    private var relicPickupTime = 0L

    private val termCompletedRegex = Regex("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)$")
    private val pre4Box = net.minecraft.world.phys.AABB(62.0, 127.0, 34.0, 65.0, 130.0, 37.0)

    fun init() {
        EventBus.register<WorldChangeEvent> {
            reset()
        }

        EventBus.register<TickEvent.Start> {
            if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) {
                if (currentPhase != Phase.NONE) reset()
                return@register
            }
            updateStageFromCoordinates()
        }

        EventBus.register<ChatMessageEvent> {
            if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return@register
            val msg = event.unformattedText

            // Teleport tracking
            if (msg.startsWith("You have teleported to ") && msg.endsWith("!")) {
                when (currentPhase) {
                    Phase.STORM -> {
                        if (stormStep < 2) stormStep++
                    }
                    Phase.GOLDOR -> {
                        when (goldorSection) {
                            1 -> s1LeapIndex++
                            2 -> s2LeapIndex++
                            3 -> s3LeapIndex++
                            4 -> s4LeapIndex++
                        }
                    }
                    else -> {}
                }
            }

            // Maxor
            if (msg == "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!") {
                currentPhase = Phase.MAXOR
                isMaxorDead = false
            } else if (msg == "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!") {
                isMaxorDead = true
            }

            // Storm
            else if (msg == "[BOSS] Storm: Pathetic Maxor, just like expected.") {
                currentPhase = Phase.STORM
                isMaxorDead = true
                stormStep = 0
                oofCount = 0
            } else if (msg == "[BOSS] Storm: Oof" || msg == "[BOSS] Storm: Ouch, that hurt!") {
                oofCount++
                if (oofCount == 1 && stormStep == 0) {
                    stormStep = 1 // Purple pad crushed -> advance to Yellow Crusher
                } else if (oofCount >= 2) {
                    stormStep = 2 // Yellow pad crushed -> advance to SSC
                }
            } else if (msg == "[BOSS] Storm: I should have known that I stood no chance.") {
                stormStep = 2
            }

            // Goldor
            else if (msg == "[BOSS] Goldor: Who dares trespass into my domain?") {
                currentPhase = Phase.GOLDOR
                goldorSection = 1
                s1LeapIndex = 0
                s2LeapIndex = 0
                s3LeapIndex = 0
                s4LeapIndex = 0
                s1GateBlown = false
                s2GateBlown = false
                s3GateBlown = false
            } else if (msg == "The Core entrance is opening!") {
                goldorSection = 4
            }

            // Terminal progress
            termCompletedRegex.find(msg)?.destructured?.let { (_, _, _, cur, tot) ->
                val current = cur.toIntOrNull() ?: 0
                val total = tot.toIntOrNull() ?: 0
                if (current > 0 && current == total) {
                    // Objectives completed for this section
                    if (currentPhase == Phase.GOLDOR && goldorSection < 4) {
                        goldorSection++
                    }
                }
            }

            // Pre4 / I4 device check
            if (msg.contains("completed a device!") && isAtPre4()) {
                isPre4Done = true
            }

            // Necron
            if (msg == "[BOSS] Necron: I'm afraid, your journey ends now.") {
                currentPhase = Phase.NECRON
            } else if (msg == "[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself.") {
                isMiddleActive = true
            }

            // P5 (M7)
            if (msg == "[BOSS] Necron: All this, for nothing...") {
                currentPhase = Phase.P5
                isP5StartActive = true
                p5StartTime = System.currentTimeMillis()
            } else if (msg.contains("picked the Corrupted") && msg.contains("Relic!")) {
                isRelicActive = true
                relicPickupTime = System.currentTimeMillis()
            }
        }

        EventBus.register<MainThreadPacketReceivedEvent.Pre> {
            if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return@register
            val packet = event.packet
            if (packet !is ClientboundSetSubtitleTextPacket) return@register
            val text = packet.text.unformattedText

            if (text == "The gate has been destroyed!" || text == "The gate will open in 5 seconds!") {
                if (currentPhase == Phase.GOLDOR || LocationUtils.F7Phase == 3) {
                    when (goldorSection) {
                        1 -> s1GateBlown = true
                        2 -> s2GateBlown = true
                        3 -> s3GateBlown = true
                    }
                    if (goldorSection < 4) {
                        goldorSection++
                    }
                }
            }
        }
    }

    private fun updateStageFromCoordinates() {
        val player = mc.player ?: return
        val y = player.y

        // Update phase from Y coordinate if available
        val phaseFromY = when {
            y > 210 -> Phase.MAXOR
            y > 155 -> Phase.STORM
            y > 100 -> Phase.GOLDOR
            y > 45 -> Phase.NECRON
            else -> Phase.P5
        }
        if (currentPhase == Phase.NONE || currentPhase != phaseFromY) {
            currentPhase = phaseFromY
        }

        // Goldor stage detection using quoi's precise coordinates
        if (currentPhase == Phase.GOLDOR) {
            val x = player.x
            val z = player.z
            val coordSection = when {
                x in 89.0..113.0 && z in 30.0..122.0 -> 1
                x in 19.0..111.0 && z in 121.0..145.0 -> 2
                x in -6.0..19.0 && z in 51.0..143.0 -> 3
                x in -2.0..90.0 && z in 27.0..51.0 -> 4
                else -> null
            }
            if (coordSection != null && coordSection > goldorSection) {
                goldorSection = coordSection
            }
        }

        // P5 Start window timeout
        if (isP5StartActive && System.currentTimeMillis() - p5StartTime > 5000L) {
            isP5StartActive = false
        }

        // Relic window timeout
        if (isRelicActive && System.currentTimeMillis() - relicPickupTime > 4000L) {
            isRelicActive = false
        }
    }

    fun isAtPre4(): Boolean {
        val player = mc.player ?: return false
        return pre4Box.contains(player.position())
    }

    fun reset() {
        currentPhase = Phase.NONE
        isMaxorDead = false
        stormStep = 0
        oofCount = 0

        goldorSection = 1
        s1LeapIndex = 0
        s2LeapIndex = 0
        s3LeapIndex = 0
        s4LeapIndex = 0

        s1GateBlown = false
        s2GateBlown = false
        s3GateBlown = false

        isMiddleActive = false
        isPre4Done = false

        isP5StartActive = false
        isRelicActive = false
        p5StartTime = 0L
        relicPickupTime = 0L
    }
}
