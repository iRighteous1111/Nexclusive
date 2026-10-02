package com.github.noamm9.nexclusive.features.impl.nexclusive.leap

import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket

object BossPhaseDetector {
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

    // Storm
    var isPyActive = false
        private set
    var isSscActive = false
        private set
    private var pyEndTime = 0L

    // Goldor sections
    var goldorSection = 1
        private set
    var s1LeapCount = 0
        private set
    var s2LeapCount = 0
        private set
    var s3LeapCount = 0
        private set
    var s4LeapCount = 0
        private set

    // P5 Relics
    private var p5StartTime = 0L
    private var relicSpawnTime = 0L
    private var lastRelicPickupTime = 0L

    fun init() {
        EventBus.register<WorldChangeEvent> {
            reset()
        }

        EventBus.register<ChatMessageEvent> {
            if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return@register
            val msg = event.unformattedText

            // Teleport tracking for leap counts
            if (msg.startsWith("You have teleported to ") && msg.endsWith("!")) {
                if (currentPhase == Phase.GOLDOR || LocationUtils.F7Phase == 3) {
                    when (goldorSection) {
                        1 -> s1LeapCount++
                        2 -> s2LeapCount++
                        3 -> s3LeapCount++
                        4 -> s4LeapCount++
                    }
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
                isPyActive = false
                isSscActive = false
            } else if (msg == "[BOSS] Storm: ENERGY HEED MY CALL!" || msg == "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!") {
                currentPhase = Phase.STORM
                isPyActive = true
                isSscActive = false
                pyEndTime = System.currentTimeMillis() + 5750L
            } else if (msg == "[BOSS] Storm: Oof" || msg == "[BOSS] Storm: Ouch, that hurt!") {
                if (isPyActive) {
                    // Stunned on purple pad: keep active for 2s then switch to SSC
                    pyEndTime = System.currentTimeMillis() + 2000L
                }
            } else if (msg == "[BOSS] Storm: I should have known that I stood no chance.") {
                isPyActive = false
                isSscActive = false
            }

            // Goldor
            else if (msg == "[BOSS] Goldor: Who dares trespass into my domain?") {
                currentPhase = Phase.GOLDOR
                goldorSection = 1
                s1LeapCount = 0
                s2LeapCount = 0
                s3LeapCount = 0
                s4LeapCount = 0
            } else if (msg == "The Core entrance is opening!") {
                goldorSection = 4
            }

            // Necron
            else if (msg == "[BOSS] Necron: I'm afraid, your journey ends now.") {
                currentPhase = Phase.NECRON
            }

            // P5 (M7)
            else if (msg == "[BOSS] Necron: All this, for nothing...") {
                currentPhase = Phase.P5
                p5StartTime = System.currentTimeMillis()
                relicSpawnTime = System.currentTimeMillis() + 2100L
            } else if (msg.contains("picked the Corrupted") && msg.contains("Relic!")) {
                lastRelicPickupTime = System.currentTimeMillis()
            }
        }

        EventBus.register<MainThreadPacketReceivedEvent.Pre> {
            if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return@register
            val packet = event.packet
            if (packet !is ClientboundSetSubtitleTextPacket) return@register
            val text = packet.text.unformattedText

            if (text == "The gate has been destroyed!" || text == "The gate will open in 5 seconds!") {
                if (currentPhase == Phase.GOLDOR || LocationUtils.F7Phase == 3) {
                    if (goldorSection < 4) {
                        goldorSection++
                    }
                }
            }
        }
    }

    fun updateState() {
        if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) {
            if (currentPhase != Phase.NONE) reset()
            return
        }

        // Keep currentPhase in sync with LocationUtils.F7Phase
        val phaseNum = LocationUtils.F7Phase
        if (phaseNum != null) {
            when (phaseNum) {
                1 -> if (currentPhase != Phase.MAXOR && ! isMaxorDead) currentPhase = Phase.MAXOR
                2 -> if (currentPhase != Phase.STORM) currentPhase = Phase.STORM
                3 -> {
                    if (currentPhase != Phase.GOLDOR) currentPhase = Phase.GOLDOR
                    val p3Sec = LocationUtils.P3Section
                    if (p3Sec != null && p3Sec > goldorSection) {
                        goldorSection = p3Sec
                    }
                }
                4 -> if (currentPhase != Phase.NECRON) currentPhase = Phase.NECRON
                5 -> if (currentPhase != Phase.P5) currentPhase = Phase.P5
            }
        }

        // Storm sub-phases
        if (currentPhase == Phase.STORM) {
            val now = System.currentTimeMillis()
            if (isPyActive && now > pyEndTime) {
                isPyActive = false
                isSscActive = true
            }
        }
    }

    fun isRelicWindowActive(): Boolean {
        if (currentPhase != Phase.P5 && LocationUtils.F7Phase != 5) return false
        val now = System.currentTimeMillis()

        // 1. Spawning window: from relic spawn until 3.0s after
        if (relicSpawnTime > 0L && now in (relicSpawnTime - 500L)..(relicSpawnTime + 3000L)) return true

        // 2. Pickup window: within 3.0s of picking up a relic
        if (lastRelicPickupTime > 0L && (now - lastRelicPickupTime) <= 3000L) return true

        return false
    }

    fun reset() {
        currentPhase = Phase.NONE
        isMaxorDead = false
        isPyActive = false
        isSscActive = false
        pyEndTime = 0L

        goldorSection = 1
        s1LeapCount = 0
        s2LeapCount = 0
        s3LeapCount = 0
        s4LeapCount = 0

        p5StartTime = 0L
        relicSpawnTime = 0L
        lastRelicPickupTime = 0L
    }
}
