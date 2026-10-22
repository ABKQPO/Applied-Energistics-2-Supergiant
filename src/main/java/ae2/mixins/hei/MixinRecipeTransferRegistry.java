/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.mixins.hei;

import ae2.integration.modules.hei.StandardRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.config.Config;
import mezz.jei.recipes.RecipeTransferRegistry;
import mezz.jei.startup.StackHelper;
import mezz.jei.transfer.BasicRecipeTransferHandler;
import net.minecraft.inventory.Container;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes JEI's standard slot-info registration through the server-validated AE transfer path.
 */
@Mixin(value = RecipeTransferRegistry.class, remap = false)
public abstract class MixinRecipeTransferRegistry {
    @Shadow
    @Final
    private IRecipeTransferHandlerHelper handlerHelper;

    @Shadow
    @Final
    private StackHelper stackHelper;

    @Inject(method = "addRecipeTransferHandler(Lmezz/jei/api/recipe/transfer/IRecipeTransferInfo;)V",
        at = @At("HEAD"), cancellable = true)
    private <C extends Container> void ae2$replaceStandardHandler(IRecipeTransferInfo<C> recipeTransferInfo, CallbackInfo callback) {
        if (Config.isRecipeCategoryDisabled(recipeTransferInfo.getRecipeCategoryUid())) {
            callback.cancel();
            return;
        }
        IRecipeTransferHandler<C> fallback = new BasicRecipeTransferHandler<>(this.stackHelper, this.handlerHelper, recipeTransferInfo);
        IRecipeTransferHandler<C> handler = new StandardRecipeTransferHandler<>(recipeTransferInfo, fallback);
        ((RecipeTransferRegistry) (Object) this).addRecipeTransferHandler(handler, recipeTransferInfo.getRecipeCategoryUid());
        callback.cancel();
    }
}
