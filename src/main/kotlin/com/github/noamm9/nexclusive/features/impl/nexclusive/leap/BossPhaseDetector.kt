package com.github.noamm9.nexclusive.features.impl.nexclusive.leap

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.features.impl.floor7.devices.I4Helper
import com.github.noamm9.utils.MathUtils.aabb
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket

object BossPhaseDetector {
    var isMaxorDead = false
        private set
    var inStorm = false
        private set
    var isI4Done = false
        private set
    var inCorePhase = false
        private set

    private var goldorSection = 1
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

    private val pre4Box = aabb(62, 127, 34, 65, 130, 37)

    val currentSection: Int
        get() = LocationUtils.P3Section ?: goldorSection

    val isGoldorPhase: Boolean
        get() = ! inStorm && LocationUtils.F7Phase == 3

    val isStormPhase: Boolean
        get() = inStorm || LocationUtils.F7Phase == 2

    fun recordLeapSection() {
        lastLeapSection = currentSection
    }

    fun isInP3Section(): Boolean = LocationUtils.P3Section != null

    fun onChat(msg: String) {
        if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return

        if (msg.startsWith("You have teleported to ") && msg.endsWith("!")) {
            isI4Done = false
            if (isGoldorPhase) {
                when (lastLeapSection) {
                    1 -> s1LeapIndex++
                    2 -> s2LeapIndex++
                    3 -> s3LeapIndex++
                    4 -> s4LeapIndex++
                }
            }
            return
        }

        when (msg) {
            "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> {
                isMaxorDead = false
                inStorm = false
            }
            "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!" -> isMaxorDead = true
            "[BOSS] Storm: Pathetic Maxor, just like expected." -> {
                isMaxorDead = true
                inStorm = true
            }
            "[BOSS] Goldor: Who dares trespass into my domain?" -> {
                inStorm = false
                inCorePhase = false
                goldorSection = 1
                resetGoldorIndices()
            }
            "The gate has been destroyed!" -> if (isGoldorPhase && goldorSection < 4) goldorSection++
            "The Core entrance is opening!" -> {
                goldorSection = 4
                inCorePhase = true
            }
        }

        if (msg.contains("completed a device!")) {
            val isUser = I4Helper.DEVICE_DONE_REGEX.find(msg)?.groupValues?.get(1) == mc.user.name
            if (isUser && (I4Helper.isOnDev() || pre4Box.contains(mc.player?.position() ?: return))) {
                isI4Done = true
            }
        }
    }

    fun onPacketPost(event: MainThreadPacketReceivedEvent.Post) {
        if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return
        if (! isGoldorPhase || isI4Done) return
        val packet = event.packet as? ClientboundSetEntityDataPacket ?: return
        val level = mc.level ?: return
        if (I4Helper.isOnDev() && level.getEntity(packet.id)?.name?.string == "Active") {
            isI4Done = true
        }
    }

    private fun resetGoldorIndices() {
        s1LeapIndex = 0
        s2LeapIndex = 0
        s3LeapIndex = 0
        s4LeapIndex = 0
        lastLeapSection = 1
    }

    fun reset() {
        isMaxorDead = false
        inStorm = false
        isI4Done = false
        inCorePhase = false
        goldorSection = 1
        resetGoldorIndices()
    }
}
