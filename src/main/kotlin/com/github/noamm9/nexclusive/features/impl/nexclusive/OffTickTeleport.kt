package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.commands.CommandBuilder
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.init.types.ICommandProvider
import com.github.noamm9.nexclusive.interfaces.INexConnection
import com.github.noamm9.nexclusive.mixins.accessors.IClientPacketListenerInvoker
import com.github.noamm9.nexclusive.mixins.accessors.IEntityInvoker
import com.github.noamm9.nexclusive.mixins.accessors.IMinecraftAccessor
import com.github.noamm9.ui.clickgui.ClickGuiScreen
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.GameType
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.util.ArrayDeque
import java.util.HashSet
import java.util.Queue
import kotlin.jvm.optionals.getOrNull

object OffTickTeleport : Feature(
    name = "Off-Tick Teleport (OTT)",
    jsonName = "OffTickTeleport",
    description = "Right-click in GUI to configure Fast Teleport (use), Packet Processing, and After Entity Tick."
), ICommandProvider {

    val fastTeleportSetting by ToggleSetting("Fast Teleport", true)
        .section("Teleport")
        .withDescription("Immediately sends teleport item usage packets off-tick on right click (/ott use).")

    val packetProcessingSetting by ToggleSetting("Packet Processing", true)
        .section("Networking")
        .withDescription("Buffers outgoing packets during right clicks and flushes them on tick (/ott processing).")

    val afterEntityTickSetting by ToggleSetting("After Entity Tick", false)
        .withDescription("Only applies incoming position updates after entities have ticked (/ott afterEntityTick).")

    // Getters for Mixins
    fun getFastTeleport(): Boolean = fastTeleportSetting.value
    fun getPacketProcessing(): Boolean = packetProcessingSetting.value
    fun getAfterEntityTick(): Boolean = afterEntityTickSetting.value
    fun getEnabled(): Boolean = enabled

    private val prefix: MutableComponent = Component.empty()
        .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
        .append(Component.literal("o"))
        .append(Component.literal("t"))
        .append(Component.literal("t"))
        .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))

    private var scheduledPosition: ClientboundPlayerPositionPacket? = null
    private var collecting: Boolean = true
    private val packetQueue: Queue<Packet<*>> = ArrayDeque()
    private var skip: Boolean = false

    private var serverSlot: Int = 0
    private var swappedThisTick: Boolean = false

    private val TELEPORT = setOf("ASPECT_OF_THE_LEECH_1", "ASPECT_OF_THE_LEECH_2", "ASPECT_OF_THE_LEECH_3")
    private val ETHER = setOf("ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID", "ETHERWARP_CONDUIT")
    private val WITHER_BLADES = setOf("NECRON_BLADE", "SCYLLA", "HYPERION", "VALKYRIE", "ASTRAEA")

    override fun init() {
        register<MainThreadPacketReceivedEvent.Pre> {
            if (enabled && packetProcessingSetting.value) {
                onReceive(event.packet)
            }
        }
    }

    override fun CommandBuilder.command() {
        setName("ott")

        literal("gui") {
            runs {
                mc.execute {
                    val screen = ClickGuiScreen()
                    mc.setScreen(screen)
                    screen.openFeatureWindow(OffTickTeleport)
                }
            }
        }

        literal("use") {
            runs {
                fastTeleportSetting.value = !fastTeleportSetting.value
                chat("Toggled use to %s", fastTeleportSetting.value)
            }
        }

        literal("processing") {
            runs {
                packetProcessingSetting.value = !packetProcessingSetting.value
                chat("Toggled processing to %s", packetProcessingSetting.value)
            }
        }

        literal("afterEntityTick") {
            runs {
                afterEntityTickSetting.value = !afterEntityTickSetting.value
                chat("Toggled afterEntityTick to %s", afterEntityTickSetting.value)
            }
        }

        runs {
            chat("Off-Tick Teleport: Fast Teleport=%s, Packet Processing=%s, After Entity Tick=%s (Use '/ott gui' or right-click in ClickGUI)",
                fastTeleportSetting.value, packetProcessingSetting.value, afterEntityTickSetting.value)
        }
    }

    override fun onDisable() {
        super.onDisable()
        releaseIfAvailable()
        synchronized(this) {
            packetQueue.clear()
            scheduledPosition = null
            collecting = false
            skip = false
            swappedThisTick = false
        }
    }

    fun onReceive(packet: Packet<*>) {
        if (packet is ClientboundPlayerPositionPacket) {
            schedulePosition(packet)
        }
    }

    fun onSend(packet: Packet<*>, ci: CallbackInfo) {
        if (packet is ServerboundSetCarriedItemPacket) {
            serverSlot = packet.slot
            swappedThisTick = true
        } else if (packet is ServerboundClientTickEndPacket) {
            swappedThisTick = false
        }

        if (mc.packetProcessor().isSameThread) {
            synchronized(this) {
                if (!collecting || skip) {
                    skip = false
                    return
                }

                packetQueue.add(packet)
                ci.cancel()
            }
        }
    }

    fun startBuffering() {
        synchronized(this) {
            if (packetQueue.isNotEmpty()) {
                return
            }

            collecting = true
        }
    }

    fun releaseIfAvailable() {
        synchronized(this) {
            collecting = false

            while (true) {
                val packet = packetQueue.poll() ?: break
                mc.connection?.send(packet)
            }
        }
    }

    private fun schedulePosition(pos: ClientboundPlayerPositionPacket) {
        synchronized(this) {
            scheduledPosition = pos
        }
    }

    fun applyPosition() {
        val player = mc.player ?: return
        val pos = scheduledPosition ?: return

        synchronized(this) {
            if (!player.isPassenger) {
                val newChange = PositionMoveRotation(pos.change().position(), pos.change().deltaMovement(), 0.0f, 0.0f)
                val relatives = HashSet(pos.relatives()).apply {
                    add(Relative.X_ROT)
                    add(Relative.Y_ROT)
                    add(Relative.ROTATE_DELTA)
                }

                IClientPacketListenerInvoker.invokeSetValuesFromPositionPacket(newChange, relatives, player, false)
                (player as? IEntityInvoker)?.invokeSetOldPos()
            }
            scheduledPosition = null
        }
    }

    fun fastTeleport(): Boolean {
        val player = mc.player ?: return false
        if (mc.level == null) return false

        if (isDesynced()) {
            return false
        }

        val item = player.mainHandItem
        val skyblockID = getID(item) ?: return false

        val isEtherwarp = ETHER.contains(skyblockID)
        val isHype = TELEPORT.contains(skyblockID) || (WITHER_BLADES.contains(skyblockID) && getCustomData(item).getListOrEmpty("ability_scroll").size == 3)

        if ((!isEtherwarp || player.lastSentInput.shift != player.input.keyPresses.shift) && !isHype) {
            return false
        }

        synchronized(this) {
            skip = true
            sendItemUse(player.yRot, player.xRot)
        }

        releaseIfAvailable()

        (mc as? IMinecraftAccessor)?.setRightClickDelay(5)

        return true
    }

    private fun isDesynced(): Boolean {
        return getNextUpdateIndex() != serverSlot
    }

    private fun getNextUpdateIndex(): Int {
        if (swappedThisTick) return serverSlot
        val player = mc.player ?: return 0
        return player.inventory.selectedSlot
    }

    fun getCustomData(item: ItemStack): CompoundTag {
        return item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
    }

    fun getID(item: ItemStack): String? {
        return getCustomData(item).getString("id").getOrNull()
    }

    fun sendItemUse(yaw: Float, pitch: Float) {
        val player = mc.player ?: return
        if (player.gameMode() == GameType.SPECTATOR) return
        if (mc.gameMode == null || mc.level == null || mc.connection == null) return
        val conn = mc.connection?.connection as? INexConnection ?: return
        conn.sendPacketImmediately(ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, yaw, pitch))
    }

    fun chat(message: String, vararg objects: Any?) {
        if (mc.player != null) {
            mc.execute {
                mc.gui.chat.addClientSystemMessage(prefix.copy().append(Component.literal(String.format(message, *objects))))
            }
        }
    }
}
