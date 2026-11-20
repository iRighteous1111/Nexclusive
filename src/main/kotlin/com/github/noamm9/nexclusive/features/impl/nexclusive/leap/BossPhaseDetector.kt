package com.github.noamm9.nexclusive.features.impl.nexclusive.leap

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.impl.floor7.devices.I4Helper
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.utils.MathUtils.aabb
import com.github.noamm9.utils.dungeons.F7Listener
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket

object BossPhaseDetector: ISelfInit {
    var isMaxorDead = false
        private set
    var inStorm = false
        private set
    var isI4Done = false
        private set
    var leapedAfterI4 = false
        private set

    var lastLeapSection = 1
        private set
    private val leapIndices = IntArray(4)

    fun getLeapIndex(sectionIndex: Int) = leapIndices.getOrElse(sectionIndex) { 0 }

    private val pre4Box = aabb(62, 127, 34, 65, 130, 37)

    val inCorePhase get() = F7Listener.section.ordinal > 4
    val currentSection get() = LocationUtils.P3Section ?: F7Listener.section.ordinal.takeIf { it in 1 .. 4 } ?: 1
    val isGoldorPhase get() = ! inStorm && LocationUtils.F7Phase == 3
    val isStormPhase get() = inStorm || LocationUtils.F7Phase == 2

    override fun init() {
        EventBus.register<WorldChangeEvent> { reset() }
        EventBus.register<ChatMessageEvent> { onChat(event.unformattedText) }
        EventBus.register<MainThreadPacketReceivedEvent.Post> { onPacketPost(event) }
    }

    fun recordLeapSection() {
        lastLeapSection = currentSection
    }

    fun isInP3Section() = LocationUtils.P3Section != null

    fun isAtI4(): Boolean {
        val pos = mc.player?.position() ?: return false
        return LocationUtils.P3Section == 4 && (I4Helper.isOnDev() || pre4Box.contains(pos))
    }

    private fun onChat(msg: String) {
        if (! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return

        if (msg.startsWith("You have teleported to ") && msg.endsWith("!")) {
            if (isI4Done) leapedAfterI4 = true
            if (isGoldorPhase && lastLeapSection in 1 .. 4) leapIndices[lastLeapSection - 1] ++
            return
        }

        when (msg) {
            "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> {
                isMaxorDead = false
                inStorm = false
                isI4Done = false
                leapedAfterI4 = false
            }
            "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!" -> isMaxorDead = true
            "[BOSS] Storm: Pathetic Maxor, just like expected." -> {
                isMaxorDead = true
                inStorm = true
            }
            "[BOSS] Goldor: Who dares trespass into my domain?" -> {
                inStorm = false
                resetGoldorIndices()
            }
        }

        if (msg.contains("completed a device!")) {
            val isUser = I4Helper.DEVICE_DONE_REGEX.find(msg)?.groupValues?.get(1) == mc.user.name
            if (isUser && isAtI4()) isI4Done = true
        }
    }

    private fun onPacketPost(event: MainThreadPacketReceivedEvent.Post) {
        if (! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7 || LocationUtils.P3Section != 4 || isI4Done) return
        val packet = event.packet as? ClientboundSetEntityDataPacket ?: return
        val level = mc.level ?: return
        if (I4Helper.isOnDev() && level.getEntity(packet.id)?.name?.string == "Active") {
            isI4Done = true
        }
    }

    private fun resetGoldorIndices() {
        leapIndices.fill(0)
        lastLeapSection = 1
    }

    private fun reset() {
        isMaxorDead = false
        inStorm = false
        isI4Done = false
        leapedAfterI4 = false
        resetGoldorIndices()
    }
}
