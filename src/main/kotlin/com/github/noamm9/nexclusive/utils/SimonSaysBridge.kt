package com.github.noamm9.nexclusive.utils

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.features.impl.floor7.devices.SimonSays
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.*
import java.lang.reflect.Field
import kotlin.math.abs

object SimonSaysBridge {
    private val deviceCenter = Vec3(110.5, 121.5, 93.5)
    private val buttonCheckPos = BlockPos(110, 120, 93)

    private val solutionField by lazy {
        runCatching {
            SimonSays::class.java.getDeclaredField("solution").apply { isAccessible = true }
        }.getOrNull()
    }

    private var buttonFieldCache: Field? = null
    private var numberFieldCache: Field? = null

    private var clickedSSButton: Any? = null
    private var clickedButtonPos: BlockPos? = null
    private var clickedSolutionSize = - 1
    private var clickTime = 0L

    private var wasInClickingPhase = false
    private var isFirstClickInPhase = true
    private var initialPhaseButton: BlockPos? = null
    private var lastTargetPos: BlockPos? = null

    val isFirstButton: Boolean
        get() {
            if (! isFirstClickInPhase) return false
            val num = getValidButtonNumber()
            if (num != null && num > 1) {
                isFirstClickInPhase = false
                return false
            }
            return true
        }

    fun updateTarget(targetButton: BlockPos): Boolean {
        if (! wasInClickingPhase) {
            wasInClickingPhase = true
            isFirstClickInPhase = true
            initialPhaseButton = targetButton
        }

        if (targetButton != lastTargetPos) {
            if (lastTargetPos != null && initialPhaseButton != null && targetButton != initialPhaseButton) {
                isFirstClickInPhase = false
            }
            lastTargetPos = targetButton
            resetClickedState()
            return true
        }
        return false
    }

    fun resetPhase() {
        wasInClickingPhase = false
        isFirstClickInPhase = true
        initialPhaseButton = null
        lastTargetPos = null
        resetClickedState()
    }

    fun isAtSSDevice(): Boolean {
        val player = mc.player ?: return false
        return LocationUtils.F7Phase == 3 && player.position().distanceToSqr(deviceCenter) <= 49.0
    }

    fun isDeviceInClickingPhase(): Boolean {
        val level = mc.level ?: return false
        val inPhase = level.getBlockState(buttonCheckPos).block == Blocks.STONE_BUTTON
        if (! inPhase && wasInClickingPhase) resetPhase()
        return inPhase
    }

    fun getSolutionList(): List<*>? {
        if (! SimonSays.enabled) return null
        return runCatching { solutionField?.get(SimonSays) as? List<*> }.getOrNull()
    }

    fun getValidSSButton(): Any? = getSolutionList()?.firstOrNull()

    fun getValidButtonNumber(): Int? {
        val first = getValidSSButton() ?: return null
        return runCatching {
            if (numberFieldCache == null) {
                numberFieldCache = first.javaClass.getDeclaredField("number").apply { isAccessible = true }
            }
            numberFieldCache?.getInt(first)
        }.getOrNull()
    }

    fun getValidButton(): BlockPos? {
        if (! isDeviceInClickingPhase()) return null
        val first = getValidSSButton() ?: return null
        val pos = runCatching {
            if (buttonFieldCache == null) {
                buttonFieldCache = first.javaClass.getDeclaredField("button").apply { isAccessible = true }
            }
            buttonFieldCache?.get(first) as? BlockPos
        }.getOrNull() ?: return null

        val level = mc.level ?: return null
        if (level.getBlockState(pos).block != Blocks.STONE_BUTTON) return null

        return pos
    }

    fun markTargetClicked() {
        val currentObj = getValidSSButton() ?: return
        val currentPos = getValidButton() ?: return
        clickedSSButton = currentObj
        clickedButtonPos = currentPos
        clickedSolutionSize = getSolutionList()?.size ?: - 1
        clickTime = System.currentTimeMillis()
        isFirstClickInPhase = false
    }

    fun isCurrentTargetClicked(): Boolean {
        if (clickedSSButton == null && clickedButtonPos == null) return false
        val now = System.currentTimeMillis()
        if (now - clickTime > 600L) {
            resetClickedState()
            return false
        }

        val currentList = getSolutionList() ?: return false
        val currentObj = currentList.firstOrNull() ?: return false
        val currentPos = getValidButton() ?: return false

        if (currentList.size != clickedSolutionSize || currentObj !== clickedSSButton) {
            resetClickedState()
            return false
        }

        return currentPos == clickedButtonPos
    }

    fun resetClickedState() {
        clickedSSButton = null
        clickedButtonPos = null
        clickedSolutionSize = - 1
        clickTime = 0L
    }

    fun getTargetPoint(targetButton: BlockPos): Vec3 =
        Vec3(targetButton.x + 0.9, targetButton.y + 0.5, targetButton.z + 0.5)

    fun getHitboxCenterOffset(targetButton: BlockPos): Vec2? {
        val player = mc.player ?: return null
        val eyePos = player.eyePosition
        val lookVec = player.lookAngle
        if (abs(lookVec.x) < 1e-5) return null

        val targetX = targetButton.x + 0.9
        val t = (targetX - eyePos.x) / lookVec.x
        if (t <= 0.0 || t > 6.0) return null

        val hitY = eyePos.y + t * lookVec.y
        val hitZ = eyePos.z + t * lookVec.z

        return Vec2((hitY - (targetButton.y + 0.5)).toFloat(), (hitZ - (targetButton.z + 0.5)).toFloat())
    }

    fun isLookingAtButton(targetButton: BlockPos): Boolean {
        val hit = mc.hitResult
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == targetButton) return true

        val player = mc.player ?: return false
        val eyePos = player.eyePosition
        val lookVec = player.lookAngle
        val endVec = eyePos.add(lookVec.scale(6.0))

        val aabb = AABB(
            targetButton.x + 0.85,
            targetButton.y + 0.35,
            targetButton.z + 0.30,
            targetButton.x + 1.02,
            targetButton.y + 0.65,
            targetButton.z + 0.70
        )
        return aabb.clip(eyePos, endVec).isPresent
    }

    fun isInTargetArea(targetButton: BlockPos, sizePercent: Number): Boolean {
        if (! isLookingAtButton(targetButton)) return false
        val percent = sizePercent.toDouble()
        if (percent >= 100.0) return true
        val offset = getHitboxCenterOffset(targetButton) ?: return true
        val ratio = (percent / 100.0).coerceIn(0.1, 1.0)
        return abs(offset.x) <= 0.15 * ratio && abs(offset.y) <= 0.20 * ratio
    }

    fun isInCenterArea(targetButton: BlockPos, sizePercent: Number): Boolean =
        isInTargetArea(targetButton, sizePercent)

    fun isSSButton(pos: BlockPos): Boolean =
        pos.x == 110 && pos.y in 120..123 && pos.z in 92..95
}
