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
import net.minecraft.world.phys.AABB

object BossPhaseDetector {
    private val mc get() = Minecraft.getInstance()

    enum class Phase { NONE, MAXOR, STORM, GOLDOR, NECRON, P5 }

    var currentPhase = Phase.NONE
        private set
    var isMaxorDead = false
        private set

    // Storm sequence: 0 = Checkpoint (Purple), 1 = Yellow Crusher, 2 = SSC
    var stormStep = 0
        private set
    private var oofCount = 0

    // Goldor section tracking (1..4)
    var goldorSection = 1
        private set
    var lastLeapSection = 1
        private set
    var s1LeapIndex = 0
        private set
    var s2LeapIndex = 0
        private set
    var s3LeapIndex = 0
        private set
    var s4LeapIndex = 0
        private set

    // Necron & P5 sub-states
    var isMiddleActive = false
        private set
    var isPre4Done = false
        private set
    var isP5StartActive = false
        private set
    var isRelicActive = false
        private set

    private var p5StartTime = 0L
    private var relicPickupTime = 0L
    private var gateDestroyedThisSection = false
    private val pre4Box = AABB(62.0, 127.0, 34.0, 65.0, 130.0, 37.0)
    private val termRegex = Regex("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)$")

    val playerSection: Int?
        get() {
            val player = mc.player ?: return null
            val x = player.x
            val z = player.z
            return when {
                x in 89.0..113.0 && z in 30.0..122.0 -> 1
                x in 19.0..111.0 && z in 121.0..145.0 -> 2
                x in -6.0..19.0 && z in 51.0..143.0 -> 3
                x in -2.0..90.0 && z in 27.0..51.0 -> 4
                else -> null
            }
        }

    fun recordLeapSection() {
        lastLeapSection = playerSection ?: goldorSection
    }

    fun isInPre4(): Boolean {
        val player = mc.player ?: return false
        return pre4Box.contains(player.position()) || playerSection == 4
    }

    fun isInP3Section(): Boolean {
        val player = mc.player ?: return false
        return player.y <= 155.0 && playerSection != null
    }

    fun init() {
        EventBus.register<WorldChangeEvent> { reset() }

        EventBus.register<TickEvent.Start> {
            if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) {
                if (currentPhase != Phase.NONE) reset()
                return@register
            }
            updateStage()
        }

        EventBus.register<ChatMessageEvent> {
            if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return@register
            handleChat(event.unformattedText)
        }

        EventBus.register<MainThreadPacketReceivedEvent.Pre> {
            if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return@register
            val packet = event.packet as? ClientboundSetSubtitleTextPacket ?: return@register
            val text = packet.text.unformattedText

            if (text == "The gate has been destroyed!" || text == "The gate will open in 5 seconds!") {
                onGateDestroyed()
            }
        }
    }

    private fun handleChat(msg: String) {
        if (msg.startsWith("You have teleported to ") && msg.endsWith("!")) {
            when (currentPhase) {
                Phase.STORM -> if (stormStep < 2) stormStep++
                Phase.GOLDOR -> when (lastLeapSection) {
                    1 -> s1LeapIndex++
                    2 -> s2LeapIndex++
                    3 -> s3LeapIndex++
                    4 -> s4LeapIndex++
                }
                else -> {}
            }
        }

        when (msg) {
            "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> { currentPhase = Phase.MAXOR; isMaxorDead = false }
            "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!" -> isMaxorDead = true
            "[BOSS] Storm: Pathetic Maxor, just like expected." -> { currentPhase = Phase.STORM; isMaxorDead = true; stormStep = 0; oofCount = 0 }
            "[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!" -> {
                oofCount++
                if (oofCount == 1 && stormStep == 0) stormStep = 1
                else if (oofCount >= 2) stormStep = 2
            }
            "[BOSS] Storm: I should have known that I stood no chance." -> stormStep = 2
            "[BOSS] Goldor: Who dares trespass into my domain?" -> { currentPhase = Phase.GOLDOR; goldorSection = 1; gateDestroyedThisSection = false; resetGoldorIndices() }
            "The gate has been destroyed!" -> onGateDestroyed()
            "The Core entrance is opening!" -> goldorSection = 4
            "[BOSS] Necron: I'm afraid, your journey ends now." -> currentPhase = Phase.NECRON
            "[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself." -> isMiddleActive = true
            "[BOSS] Necron: All this, for nothing..." -> { currentPhase = Phase.P5; isP5StartActive = true; p5StartTime = System.currentTimeMillis() }
        }

        termRegex.find(msg)?.destructured?.let { (_, _, _, cur, tot) ->
            if (cur.toIntOrNull() == tot.toIntOrNull() && cur.toIntOrNull() != null && currentPhase == Phase.GOLDOR && goldorSection < 4) {
                gateDestroyedThisSection = false
            }
        }

        if (msg.contains("completed a device!") && pre4Box.contains(mc.player?.position() ?: return)) {
            isPre4Done = true
        }

        if (msg.contains("picked the Corrupted") && msg.contains("Relic!")) {
            isRelicActive = true
            relicPickupTime = System.currentTimeMillis()
        }
    }

    private fun onGateDestroyed() {
        if (currentPhase == Phase.GOLDOR && goldorSection < 4 && ! gateDestroyedThisSection) {
            goldorSection++
            gateDestroyedThisSection = true
        }
    }

    private fun updateStage() {
        val player = mc.player ?: return
        val y = player.y

        if (currentPhase == Phase.NONE) {
            currentPhase = when {
                y > 210 -> Phase.MAXOR
                y > 155 -> Phase.STORM
                y > 100 -> Phase.GOLDOR
                y > 45 -> Phase.NECRON
                else -> Phase.P5
            }
        }

        if (isP5StartActive && System.currentTimeMillis() - p5StartTime > 5000L) isP5StartActive = false
        if (isRelicActive && System.currentTimeMillis() - relicPickupTime > 4000L) isRelicActive = false
    }

    private fun resetGoldorIndices() {
        s1LeapIndex = 0; s2LeapIndex = 0; s3LeapIndex = 0; s4LeapIndex = 0
        lastLeapSection = 1
    }

    fun reset() {
        currentPhase = Phase.NONE
        isMaxorDead = false
        stormStep = 0
        oofCount = 0
        goldorSection = 1
        lastLeapSection = 1
        gateDestroyedThisSection = false
        resetGoldorIndices()
        isMiddleActive = false
        isPre4Done = false
        isP5StartActive = false
        isRelicActive = false
        p5StartTime = 0L
        relicPickupTime = 0L
    }
}
