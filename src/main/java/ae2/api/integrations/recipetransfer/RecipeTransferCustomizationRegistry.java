/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.api.integrations.recipetransfer;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Optional server-side customization factories for standard recipe-transfer targets. Standard containers need no
 * registration; the registry is only for targets that want to provide a network source, recipe identity, or a custom
 * normal input protocol.
 */
public final class RecipeTransferCustomizationRegistry {
    private static final Object2ObjectOpenHashMap<Class<?>, Factory> FACTORIES = new Object2ObjectOpenHashMap<>();

    private RecipeTransferCustomizationRegistry() {
    }

    public static <C extends Container> void register(Class<C> containerClass,
                                                      BiFunction<? super C, EntityPlayerMP,
                                                          ? extends IRecipeTransferCustomization> factory) {
        Objects.requireNonNull(containerClass, "containerClass");
        Objects.requireNonNull(factory, "factory");
        FACTORIES.put(containerClass, (container, player) -> factory.apply(containerClass.cast(container), player));
    }

    @Nullable
    public static IRecipeTransferCustomization find(Container container, EntityPlayerMP player) {
        Factory factory = FACTORIES.get(container.getClass());
        if (factory != null) {
            return factory.create(container, player);
        }
        ObjectIterator<Object2ObjectMap.Entry<Class<?>, Factory>> iterator = FACTORIES.object2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().isInstance(container)) {
                return entry.getValue().create(container, player);
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface Factory {
        IRecipeTransferCustomization create(Container container, EntityPlayerMP player);
    }
}
