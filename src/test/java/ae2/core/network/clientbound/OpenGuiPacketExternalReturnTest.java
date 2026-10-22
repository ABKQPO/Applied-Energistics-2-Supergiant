/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.core.network.clientbound;

import net.minecraft.client.gui.GuiScreen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenGuiPacketExternalReturnTest {
    @Test
    void capturesAnExternalScreenBeforeMarkingItAsASwitchSource() {
        GuiScreen screen = new GuiScreen() {
        };

        assertTrue(OpenGuiPacket.prepareGuiSwitch(screen, true));
        assertFalse(OpenGuiPacket.prepareGuiSwitch(screen, true));
        assertFalse(OpenGuiPacket.prepareGuiSwitch(screen, false));
    }
}
