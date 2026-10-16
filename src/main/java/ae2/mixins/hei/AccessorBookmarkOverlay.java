package ae2.mixins.hei;

import mezz.jei.gui.ghost.GhostIngredientDragManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

// JEI
/** Accesses the separate ghost drag manager owned by plain JEI's bookmark overlay. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.overlay.bookmarks.BookmarkOverlay", remap = false)
public interface AccessorBookmarkOverlay {
    @Accessor("ghostIngredientDragManager")
    GhostIngredientDragManager ae2$getDragManager();
}
