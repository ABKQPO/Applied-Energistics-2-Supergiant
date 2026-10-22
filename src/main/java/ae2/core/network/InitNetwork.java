package ae2.core.network;

import ae2.core.Tags;
import ae2.core.network.bidirectional.ConfigValuePacket;
import ae2.core.network.clientbound.AdvancedMemoryCardP2PSnapshotPacket;
import ae2.core.network.clientbound.AssemblerAnimationPacket;
import ae2.core.network.clientbound.BlockTransitionEffectPacket;
import ae2.core.network.clientbound.CellTerminalSubnetHighlightPacket;
import ae2.core.network.clientbound.CellTerminalSyncChunkPacket;
import ae2.core.network.clientbound.CellTerminalSyncPacket;
import ae2.core.network.clientbound.ClearCompassCachePacket;
import ae2.core.network.clientbound.ClearPatternAccessTerminalPacket;
import ae2.core.network.clientbound.CompassResponsePacket;
import ae2.core.network.clientbound.CraftConfirmPlanPacket;
import ae2.core.network.clientbound.CraftingJobStatusPacket;
import ae2.core.network.clientbound.CraftingStatusPacket;
import ae2.core.network.clientbound.CraftingSupplierLocationsPacket;
import ae2.core.network.clientbound.CraftingTreeDataPacket;
import ae2.core.network.clientbound.ExportedGridContent;
import ae2.core.network.clientbound.FlowLocationsPacket;
import ae2.core.network.clientbound.FlowRatesPacket;
import ae2.core.network.clientbound.GuiDataSyncPacket;
import ae2.core.network.clientbound.ItemTransitionEffectPacket;
import ae2.core.network.clientbound.LightningPacket;
import ae2.core.network.clientbound.MEInventoryUpdatePacket;
import ae2.core.network.clientbound.MatterCannonPacket;
import ae2.core.network.clientbound.MockExplosionPacket;
import ae2.core.network.clientbound.NetworkAmountResultPacket;
import ae2.core.network.clientbound.NetworkConfigInitPacket;
import ae2.core.network.clientbound.NetworkDataUpdatePacket;
import ae2.core.network.clientbound.NetworkStatusPacket;
import ae2.core.network.clientbound.OpenGuiPacket;
import ae2.core.network.clientbound.PatternAccessTerminalChunkPacket;
import ae2.core.network.clientbound.PatternAccessTerminalInfoPacket;
import ae2.core.network.clientbound.PatternAccessTerminalPacket;
import ae2.core.network.clientbound.ProfileDataUpdatePacket;
import ae2.core.network.clientbound.ProviderDirectoryPagePacket;
import ae2.core.network.clientbound.ProviderMappingPagePacket;
import ae2.core.network.clientbound.RecipeTransferPendingSyncPacket;
import ae2.core.network.clientbound.RecursiveIngredientReserveAmountPacket;
import ae2.core.network.clientbound.RequesterSyncPacket;
import ae2.core.network.clientbound.RestorePreviousGuiPacket;
import ae2.core.network.clientbound.SetLinkStatusPacket;
import ae2.core.network.clientbound.TickConfigInitPacket;
import ae2.core.network.serverbound.AnalyserGenericPacket;
import ae2.core.network.serverbound.CableBusPartLeftClickPacket;
import ae2.core.network.serverbound.ColorApplicatorSelectColorPacket;
import ae2.core.network.serverbound.ConfigButtonPacket;
import ae2.core.network.serverbound.ConfigValueServerPacket;
import ae2.core.network.serverbound.ConfirmAutoCraftPacket;
import ae2.core.network.serverbound.CycleWirelessTerminalPacket;
import ae2.core.network.serverbound.FillCraftingGridFromRecipePacket;
import ae2.core.network.serverbound.GuiActionPacket;
import ae2.core.network.serverbound.HeiIngredientActionPacket;
import ae2.core.network.serverbound.HotkeyPacket;
import ae2.core.network.serverbound.InventoryActionPacket;
import ae2.core.network.serverbound.MEInteractionPacket;
import ae2.core.network.serverbound.MouseWheelPacket;
import ae2.core.network.serverbound.NetworkAmountQueryPacket;
import ae2.core.network.serverbound.NetworkConfigSavePacket;
import ae2.core.network.serverbound.QuickMovePatternPacket;
import ae2.core.network.serverbound.RecipeTransferPacket;
import ae2.core.network.serverbound.RequestClosestMeteoritePacket;
import ae2.core.network.serverbound.RequesterSlotUpdatePacket;
import ae2.core.network.serverbound.RequesterUpdatePacket;
import ae2.core.network.serverbound.SelectKeyTypePacket;
import ae2.core.network.serverbound.SelectWirelessTerminalPacket;
import ae2.core.network.serverbound.SetRecursiveIngredientReserveAmountPacket;
import ae2.core.network.serverbound.SwapSlotsPacket;
import ae2.core.network.serverbound.SwitchCraftingTreePacket;
import ae2.core.network.serverbound.SwitchGuisPacket;
import ae2.core.network.serverbound.TickConfigSavePacket;
import ae2.core.network.serverbound.TickProfilerRequestPacket;
import ae2.core.network.serverbound.TraceCraftingSupplierPacket;
import ae2.core.network.serverbound.UpdateHoldingCtrlPacket;
import ae2.core.network.serverbound.WirelessTerminalPickBlockPacket;
import ae2.core.network.serverbound.WirelessTerminalSettingsPacket;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class InitNetwork {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Tags.MOD_ID);

    private static int nextPacketId;
    private static boolean initialized;

    private InitNetwork() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        registerClientbound(CraftConfirmPlanPacket.class);
        registerClientbound(CraftingJobStatusPacket.class);
        registerClientbound(CraftingSupplierLocationsPacket.class);
        registerClientbound(CraftingStatusPacket.class);
        registerClientbound(ClearCompassCachePacket.class);
        registerClientbound(CompassResponsePacket.class);
        registerClientbound(ExportedGridContent.class);
        registerClientbound(ClearPatternAccessTerminalPacket.class);
        registerClientbound(AssemblerAnimationPacket.class);
        registerClientbound(BlockTransitionEffectPacket.class);
        registerClientbound(MockExplosionPacket.class);
        registerClientbound(GuiDataSyncPacket.Handler.class, GuiDataSyncPacket.class);
        registerClientbound(ItemTransitionEffectPacket.class);
        registerClientbound(LightningPacket.class);
        registerClientbound(MatterCannonPacket.class);
        registerClientbound(MEInventoryUpdatePacket.class);
        registerClientbound(NetworkStatusPacket.class);
        registerClientbound(OpenGuiPacket.class);
        registerClientbound(PatternAccessTerminalPacket.class);
        registerClientbound(PatternAccessTerminalChunkPacket.class);
        registerClientbound(PatternAccessTerminalInfoPacket.class);
        registerClientbound(RestorePreviousGuiPacket.class);
        registerClientbound(SetLinkStatusPacket.class);
        registerClientbound(CraftingTreeDataPacket.class);
        registerClientbound(RequesterSyncPacket.class);
        registerClientbound(NetworkConfigInitPacket.class);
        registerClientbound(TickConfigInitPacket.class);
        registerClientbound(NetworkDataUpdatePacket.class);
        registerClientbound(ProfileDataUpdatePacket.class);
        registerClientbound(RecursiveIngredientReserveAmountPacket.class);
        registerClientbound(RecipeTransferPendingSyncPacket.class);
        registerClientbound(NetworkAmountResultPacket.class);
        registerClientbound(AdvancedMemoryCardP2PSnapshotPacket.class);
        registerClientbound(CellTerminalSyncPacket.class);
        registerClientbound(CellTerminalSyncChunkPacket.class);
        registerClientbound(CellTerminalSubnetHighlightPacket.class);
        registerClientbound(ProviderDirectoryPagePacket.class);
        registerClientbound(ProviderMappingPagePacket.class);
        registerClientbound(FlowRatesPacket.class);
        registerClientbound(FlowLocationsPacket.class);
        registerClientbound(ConfigValuePacket.class);

        registerServerbound(ColorApplicatorSelectColorPacket.class);
        registerServerbound(CableBusPartLeftClickPacket.class);
        registerServerbound(ConfigButtonPacket.class);
        registerServerbound(ConfirmAutoCraftPacket.class);
        registerServerbound(CycleWirelessTerminalPacket.class);
        registerServerbound(FillCraftingGridFromRecipePacket.class);
        registerServerbound(RecipeTransferPacket.class);
        registerServerbound(GuiActionPacket.class);
        registerServerbound(HeiIngredientActionPacket.class);
        registerServerbound(NetworkAmountQueryPacket.class);
        registerServerbound(RequestClosestMeteoritePacket.class);
        registerServerbound(HotkeyPacket.class);
        registerServerbound(InventoryActionPacket.class);
        registerServerbound(MEInteractionPacket.class);
        registerServerbound(MouseWheelPacket.class);
        registerServerbound(QuickMovePatternPacket.class);
        registerServerbound(SelectKeyTypePacket.class);
        registerServerbound(SelectWirelessTerminalPacket.class);
        registerServerbound(SwapSlotsPacket.class);
        registerServerbound(SwitchGuisPacket.class);
        registerServerbound(TraceCraftingSupplierPacket.class);
        registerServerbound(UpdateHoldingCtrlPacket.class);
        registerServerbound(WirelessTerminalPickBlockPacket.class);
        registerServerbound(WirelessTerminalSettingsPacket.class);
        registerServerbound(SetRecursiveIngredientReserveAmountPacket.class);
        registerServerbound(SwitchCraftingTreePacket.class);
        registerServerbound(RequesterSlotUpdatePacket.class);
        registerServerbound(RequesterUpdatePacket.class);
        registerServerbound(AnalyserGenericPacket.class);
        registerServerbound(NetworkConfigSavePacket.class);
        registerServerbound(TickConfigSavePacket.class);
        registerServerbound(TickProfilerRequestPacket.class);
        registerServerbound(ConfigValueServerPacket.class);
    }

    public static <T extends ClientboundPacket> void registerClientbound(Class<T> packet) {
        registerClientbound(AppEngPayloadHandler.Client.class, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends ClientboundPacket> void registerClientbound(
        Class<? extends IMessageHandler> handler, Class<T> packet) {
        CHANNEL.registerMessage((Class) handler, packet, nextPacketId++, Side.CLIENT);
    }

    public static <T extends ServerboundPacket> void registerServerbound(Class<T> packet) {
        registerServerbound(AppEngPayloadHandler.Server.class, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends ServerboundPacket> void registerServerbound(
        Class<? extends IMessageHandler> handler, Class<T> packet) {
        CHANNEL.registerMessage((Class) handler, packet, nextPacketId++, Side.SERVER);
    }

    public static void sendToAllNearExcept(EntityPlayer excluded, double x, double y, double z, double distance,
                                           World world, ClientboundPacket packet) {
        if (world == null || packet == null || world.isRemote) {
            return;
        }

        double distanceSq = distance * distance;
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP) || player == excluded) {
                continue;
            }
            double dx = player.posX - x;
            double dy = player.posY - y;
            double dz = player.posZ - z;
            if (dx * dx + dy * dy + dz * dz <= distanceSq) {
                CHANNEL.sendTo(packet, (EntityPlayerMP) player);
            }
        }
    }

    public static void sendToClient(EntityPlayerMP player, ClientboundPacket packet) {
        CHANNEL.sendTo(packet, player);
    }

    public static void sendToServer(ServerboundPacket packet) {
        CHANNEL.sendToServer(packet);
    }
}
