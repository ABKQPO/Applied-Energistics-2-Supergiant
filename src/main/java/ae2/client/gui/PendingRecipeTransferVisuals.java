/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.ForgeHooksClient;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Shared visuals for materials that are still being crafted for a recipe-transfer target.
 */
public final class PendingRecipeTransferVisuals {
    private static final float GHOST_ALPHA = 0.45F;

    private PendingRecipeTransferVisuals() {
    }

    public static void drawPendingItem(RenderItem renderItem, ItemStack stack, int x, int y) {
        drawGhostItem(renderItem, stack, x, y);
        drawSpinner(x + 8, y + 8);
    }

    /**
     * Draws the baked item model with translucent vertex colors, matching the normal item renderer's GUI transform.
     */
    private static void drawGhostItem(RenderItem renderItem, ItemStack stack, int x, int y) {
        var model = renderItem.getItemModelWithOverrides(stack, null, null);

        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.translate(x, y, 100.0F + renderItem.zLevel);
        GlStateManager.translate(8.0F, 8.0F, 0.0F);
        GlStateManager.scale(1.0F, -1.0F, 1.0F);
        GlStateManager.scale(16.0F, 16.0F, 16.0F);
        boolean gui3d = model.isGui3d();
        model = ForgeHooksClient.handleCameraTransforms(model, ItemCameraTransforms.TransformType.GUI, false);
        if (gui3d) {
            RenderHelper.enableGUIStandardItemLighting();
        } else {
            GlStateManager.disableLighting();
        }
        GlStateManager.translate(-0.5F, -0.5F, -0.5F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, GHOST_ALPHA);

        emitGhostQuads(model, stack, minecraft.getItemColors());

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private static void emitGhostQuads(IBakedModel model, ItemStack stack, ItemColors itemColors) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);
        for (var facing : EnumFacing.VALUES) {
            emitGhostQuads(buffer, model.getQuads(null, facing, 0L), stack, itemColors);
        }
        emitGhostQuads(buffer, model.getQuads(null, null, 0L), stack, itemColors);
        tessellator.draw();
    }

    private static void emitGhostQuads(BufferBuilder buffer, List<BakedQuad> quads, ItemStack stack,
                                       ItemColors itemColors) {
        for (int quadIndex = 0; quadIndex < quads.size(); quadIndex++) {
            BakedQuad quad = quads.get(quadIndex);
            int[] data = quad.getVertexData();
            int stride = data.length / 4;
            if (stride != 7) {
                continue;
            }

            int tintIndex = quad.getTintIndex();
            int tint = tintIndex == -1 ? -1 : itemColors.colorMultiplier(stack, tintIndex);
            float red = tint == -1 ? 1.0F : (tint >> 16 & 0xFF) / 255.0F;
            float green = tint == -1 ? 1.0F : (tint >> 8 & 0xFF) / 255.0F;
            float blue = tint == -1 ? 1.0F : (tint & 0xFF) / 255.0F;

            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * stride;
                int normal = data[offset + 6];
                buffer.pos(
                        Float.intBitsToFloat(data[offset]),
                        Float.intBitsToFloat(data[offset + 1]),
                        Float.intBitsToFloat(data[offset + 2]))
                    .color(red, green, blue, GHOST_ALPHA)
                    .tex(
                        Float.intBitsToFloat(data[offset + 4]),
                        Float.intBitsToFloat(data[offset + 5]))
                    .normal(
                        (byte) (normal & 0xFF) / 127.0F,
                        (byte) (normal >> 8 & 0xFF) / 127.0F,
                        (byte) (normal >> 16 & 0xFF) / 127.0F)
                    .endVertex();
            }
        }
    }

    private static void drawSpinner(int x, int y) {
        float angle = (System.currentTimeMillis() % 1000L) / 1000.0F * 360.0F;

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.rotate(angle, 0.0F, 0.0F, 1.0F);

        for (int i = 0; i < SPINNER_X.length; i++) {
            int alpha = SPINNER_ALPHA[i];
            int dotColor = (alpha << 24) | 0xFFFFFF;
            int dx = SPINNER_X[i];
            int dy = SPINNER_Y[i];
            Gui.drawRect(dx - 1, dy - 1, dx + 1, dy + 1, dotColor);
        }

        GlStateManager.popMatrix();
    }

    private static final int[] SPINNER_X = { 5, 2, -2, -5, -2, 2 };
    private static final int[] SPINNER_Y = { 0, 4, 4, 0, -4, -4 };
    private static final int[] SPINNER_ALPHA = { 255, 222, 189, 156, 123, 90 };
}
