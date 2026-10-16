package ae2.mixins.hei;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

// JEI
/** Invokes plain JEI's two-argument drag completion method without reflective calls. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.ghost.GhostIngredientDragManager", remap = false)
public interface InvokerGhostIngredientDragManager {
    @Invoker("handleMouseClicked")
    boolean ae2$handleMouseClicked(int mouseX, int mouseY);
}
