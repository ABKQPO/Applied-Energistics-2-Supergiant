package ae2.integration.modules.hei;

import ae2.core.localization.HeiText;
import ae2.core.localization.ItemModText;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntCollections;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.gui.recipes.RecipeLayout;
import mezz.jei.gui.recipes.RecipeTransferButton;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.config.GuiUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class CraftingRecipeTransferUserError implements IRecipeTransferError {
    private final IRecipeLayout recipeLayout;
    private final List<String> tooltip;
    private final IntCollection missingGuiSlots;
    private final IntCollection craftableGuiSlots;
    private final IntCollection networkGuiSlots;
    private final boolean transferEnabled;

    private CraftingRecipeTransferUserError(IRecipeLayout recipeLayout,
                                            List<String> tooltip,
                                            IntCollection missingGuiSlots,
                                            IntCollection craftableGuiSlots,
                                            IntCollection networkGuiSlots,
                                            boolean transferEnabled) {
        this.recipeLayout = recipeLayout;
        this.tooltip = tooltip;
        this.missingGuiSlots = IntCollections.unmodifiable(missingGuiSlots);
        this.craftableGuiSlots = IntCollections.unmodifiable(craftableGuiSlots);
        this.networkGuiSlots = IntCollections.unmodifiable(networkGuiSlots);
        this.transferEnabled = transferEnabled;
    }

    static IRecipeTransferError create(IRecipeLayout recipeLayout, CraftingRecipeTransferAnalysis analysis) {
        return create(recipeLayout, analysis, true);
    }

    static IRecipeTransferError create(IRecipeLayout recipeLayout, CraftingRecipeTransferAnalysis analysis,
                                       boolean transferEnabled) {
        List<String> tooltip = new ObjectArrayList<>(6);
        tooltip.add(HeiText.MoveItems.getLocal());

        if (analysis.outcome() == CraftingRecipeTransferAnalysis.Outcome.READY) {
            throw new IllegalArgumentException("Ready transfers do not need a user-facing error");
        }

        if (analysis.hasImmediatelyAvailableIngredients()) {
            tooltip.add(TextFormatting.RED + ItemModText.RecipeTransferImportsAvailable.getLocal());
        }
        if (analysis.hasNetworkAvailableIngredients()) {
            tooltip.add(TextFormatting.GREEN + ItemModText.RecipeTransferNetworkAvailable.getLocal());
        }
        if (analysis.hasPotentiallyCraftableMissingIngredients()) {
            tooltip.add(TextFormatting.RED + ItemModText.RecipeTransferRequestsCraftableMissing.getLocal());
        }
        if (analysis.hasUncraftableMissingIngredients()) {
            if (analysis.hasPotentiallyCraftableMissingIngredients()) {
                tooltip.add(TextFormatting.RED + ItemModText.RecipeTransferLeavesMissing.getLocal());
            } else if (!analysis.hasImmediatelyAvailableIngredients()) {
                tooltip.add(TextFormatting.RED + ItemModText.NoItems.getLocal());
            } else {
                tooltip.add(TextFormatting.RED + ItemModText.RecipeTransferLeavesMissing.getLocal());
            }
        }

        return new CraftingRecipeTransferUserError(recipeLayout, tooltip, analysis.getMissingGuiSlots(),
            analysis.getCraftableGuiSlots(), analysis.getNetworkGuiSlots(), transferEnabled);
    }

    @Override
    public @NotNull Type getType() {
        updateTransferButton();
        return Type.USER_FACING;
    }

    @Override
    public void showError(Minecraft minecraft, int mouseX, int mouseY, @NotNull IRecipeLayout recipeLayout, int recipeX,
                          int recipeY) {
        int screenWidth = minecraft.currentScreen != null ? minecraft.currentScreen.width : minecraft.displayWidth;
        int screenHeight = minecraft.currentScreen != null ? minecraft.currentScreen.height : minecraft.displayHeight;

        RecipeTransferHighlightHelper.drawHighlights(minecraft, recipeLayout, missingGuiSlots, craftableGuiSlots,
            networkGuiSlots, recipeX, recipeY);

        GuiUtils.drawHoveringText(tooltip, mouseX, mouseY, screenWidth, screenHeight, 150,
            minecraft.fontRenderer);
    }

    private void updateTransferButton() {
        if (!(this.recipeLayout instanceof RecipeLayout concreteLayout)) {
            return;
        }

        RecipeTransferButton button = concreteLayout.getRecipeTransferButton();
        if (button != null) {
            button.enabled = this.transferEnabled;
            button.visible = true;
        }
    }
}
