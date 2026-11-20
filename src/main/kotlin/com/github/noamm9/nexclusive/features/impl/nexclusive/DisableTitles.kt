package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.floor7.devices.I4Helper
import com.github.noamm9.nexclusive.features.impl.nexclusive.leap.BossPhaseDetector
import com.github.noamm9.nexclusive.utils.SimonSaysBridge
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket

object DisableTitles: Feature(
    name = "Disable Titles",
    description = "Hides vanilla titles and Noamm's terminal titles while doing SS or I4."
) {
    private val disableOnSS by ToggleSetting("Disable on SS", true).withDescription("Hides titles while doing the Simon Says device.")
    private val disableOnI4 by ToggleSetting("Disable on I4", true).withDescription("Hides titles while doing the I4 (Sharp Shooter) device.")

    private var ssDone = false

    override fun init() {
        register<WorldChangeEvent> { ssDone = false }

        register<ChatMessageEvent> {
            if (LocationUtils.F7Phase != 3 || ssDone) return@register
            val msg = event.unformattedText
            if (! msg.contains("completed a device!")) return@register
            val completer = I4Helper.DEVICE_DONE_REGEX.find(msg)?.groupValues?.get(1) ?: return@register

            if (level.players().any { it.gameProfile.name == completer && it.position().distanceToSqr(SimonSaysBridge.deviceCenter) <= 49.0 }) {
                ssDone = true
            }
        }

        register<MainThreadPacketReceivedEvent.Post> {
            if (LocationUtils.F7Phase != 3 || ssDone || ! isAtSS()) return@register
            val packet = event.packet as? ClientboundSetEntityDataPacket ?: return@register
            val entity = level.getEntity(packet.id) ?: return@register
            if (entity.name.string == "Active" && entity.position().distanceToSqr(SimonSaysBridge.deviceCenter) <= 49.0) {
                ssDone = true
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        ssDone = false
    }

    private fun isAtSS() = LocationUtils.P3Section == 1 && SimonSaysBridge.isAtSSDevice()

    @JvmStatic
    fun shouldHideTitles(): Boolean {
        if (! enabled || LocationUtils.F7Phase != 3 || mc.player == null) return false
        if (disableOnSS.value && ! ssDone && isAtSS()) return true
        if (disableOnI4.value && ! BossPhaseDetector.isI4Done && BossPhaseDetector.isAtI4()) return true
        return false
    }
}
