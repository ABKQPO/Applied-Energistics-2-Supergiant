package ae2.mixins.hei;

import mezz.jei.bookmarks.BookmarkItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// HEI
@Mixin(value = BookmarkItem.class, remap = false)
public interface AccessorBookmarkItem<I> {
    @Accessor("ingredient")
    I ae2$getIngredient();

    @Accessor("amount")
    long ae2$getAmount();

    @Accessor("amount")
    void ae2$setAmount(long amount);
}
