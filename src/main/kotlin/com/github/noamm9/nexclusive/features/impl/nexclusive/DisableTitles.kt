package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.floor7.devices.I4Helper
import com.github.noamm9.utils.MathUtils.aabb
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.phys.Vec3

object DisableTitles: Feature(
    name = "Disable Titles",
    description = "Hides vanilla titles and Noamm's terminal titles while doing SS or I4."
) {
    private val disableOnSS by ToggleSetting("Disable on SS", true).withDescription("Hides titles while doing the Simon Says device.")
    private val disableOnI4 by ToggleSetting("Disable on I4", true).withDescription("Hides titles while doing the I4 (Sharp Shooter) device.")

    private val ssCenter = Vec3(110.5, 121.5, 93.5)
    private val i4Box = aabb(62, 127, 34, 65, 130, 37)

    private var ssDone = false
    private var i4Done = false

    override fun init() {
        register<WorldChangeEvent> { reset() }

        register<ChatMessageEvent> {
            if (LocationUtils.F7Phase != 3) return@register
            val msg = event.unformattedText
            if (! msg.contains("completed a device!")) return@register
            val completer = I4Helper.DEVICE_DONE_REGEX.find(msg)?.groupValues?.get(1) ?: return@register

            if (! ssDone && ((completer == mc.user.name && isAtSS()) || level.players().any { it.gameProfile.name == completer && it.position().distanceToSqr(ssCenter) <= 49.0 })) {
                ssDone = true
            }
            if (! i4Done && LocationUtils.P3Section == 4 && completer == mc.user.name) {
                i4Done = true
            }
        }

        register<MainThreadPacketReceivedEvent.Post> {
            if (LocationUtils.F7Phase != 3) return@register
            if (ssDone && i4Done) return@register
            val packet = event.packet as? ClientboundSetEntityDataPacket ?: return@register
            val entity = level.getEntity(packet.id) ?: return@register
            if (entity.name.string != "Active") return@register

            if (! i4Done && LocationUtils.P3Section == 4 && I4Helper.isOnDev()) i4Done = true
            if (! ssDone && LocationUtils.P3Section == 1 && isAtSS() && entity.position().distanceToSqr(ssCenter) <= 49.0) ssDone = true
        }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
    }

    private fun reset() {
        ssDone = false
        i4Done = false
    }

    private fun isAtSS() = LocationUtils.P3Section == 1 && player.position().distanceToSqr(ssCenter) <= 49.0
    private fun isAtI4() = LocationUtils.P3Section == 4 && (I4Helper.isOnDev() || i4Box.contains(player.position()))

    @JvmStatic
    fun shouldHideTitles(): Boolean {
        if (! enabled || LocationUtils.F7Phase != 3 || mc.player == null) return false
        if (disableOnSS.value && ! ssDone && isAtSS()) return true
        if (disableOnI4.value && ! i4Done && isAtI4()) return true
        return false
    }
}
