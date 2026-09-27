package com.github.noamm9.nexclusive

import com.github.noamm9.NoammAddons
import net.fabricmc.api.ClientModInitializer

object Nexclusive: ClientModInitializer {
    override fun onInitializeClient() {
        NoammAddons.logger.info("Initialized $MOD_NAME addon.")
    }

    const val MOD_NAME = "Nexclusive"
}
