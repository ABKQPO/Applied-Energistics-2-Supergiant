package ae2.core.network.bidirectional;

import ae2.api.config.Setting;
import ae2.api.util.IConfigManager;
import ae2.api.util.IConfigurableObject;
import ae2.core.network.ClientboundPacket;
import ae2.core.network.NetworkPacketHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class ConfigValuePacket extends ClientboundPacket {
    private static final int MAX_SETTING_NAME_LENGTH = 64;
    private static final int MAX_SETTING_VALUE_LENGTH = 64;

    private String name;
    private String value;
    private boolean invalid;

    public ConfigValuePacket() {
    }

    public ConfigValuePacket(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public <T extends Enum<T>> ConfigValuePacket(Setting<T> setting, T value) {
        this(setting.getName(), value.name());
        if (!setting.getValues().contains(value)) {
            throw new IllegalStateException(value + " not a valid Value for " + setting);
        }
    }

    public <T extends Enum<T>> ConfigValuePacket(Setting<T> setting, IConfigManager configManager) {
        this(setting, setting.getValue(configManager));
    }

    @Override
    protected void read(ByteBuf buf) {
        try {
            PacketBuffer packetBuffer = new PacketBuffer(buf);
            this.name = packetBuffer.readString(MAX_SETTING_NAME_LENGTH);
            this.value = packetBuffer.readString(MAX_SETTING_VALUE_LENGTH);
        } catch (RuntimeException e) {
            this.invalid = true;
            NetworkPacketHelper.warnMalformedPacket(e, getClass().getSimpleName(),
                "Ignoring malformed config Value packet");
        }
    }

    @Override
    protected void write(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.name);
        ByteBufUtils.writeUTF8String(buf, this.value);
    }

    @SideOnly(Side.CLIENT)
    public void handleClient(Minecraft minecraft) {
        if (this.invalid) {
            return;
        }
        if (minecraft.player != null && minecraft.player.openContainer instanceof IConfigurableObject configurableObject) {
            loadSetting(configurableObject);
        }
    }

    private void loadSetting(IConfigurableObject configurableObject) {
        var configManager = configurableObject.getConfigManager();
        for (var setting : configManager.getSettings()) {
            if (setting.getName().equals(this.name)) {
                setting.setFromString(configManager, this.value);
                break;
            }
        }
    }

}
