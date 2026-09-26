package com.github.noamm9.nexclusive.interfaces;

import net.minecraft.network.protocol.Packet;

public interface INexConnection {
    void sendPacketImmediately(Packet<?> packet);
}
