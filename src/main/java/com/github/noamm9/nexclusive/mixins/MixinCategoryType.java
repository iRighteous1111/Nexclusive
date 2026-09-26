package com.github.noamm9.nexclusive.mixins;

import com.github.noamm9.NoammAddons;
import com.github.noamm9.ui.clickgui.enums.CategoryType;
import kotlin.enums.EnumEntriesKt;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Mixin to inject the NEXCLUSIVE category into Noamm Addons CategoryType enum.
 */
@Mixin(CategoryType.class)
public class MixinCategoryType {

    @Shadow @Final @Mutable
    private static CategoryType[] $VALUES;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void addCustomCategory(CallbackInfo ci) {
        try {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);

            String enumName = "NEXCLUSIVE";
            ArrayList<CategoryType> valuesList = new ArrayList<>(Arrays.asList($VALUES));
            int newOrdinal = valuesList.size();

            CategoryType newCategory = (CategoryType) unsafe.allocateInstance(CategoryType.class);

            Field nameField = Enum.class.getDeclaredField("name");
            Field ordinalField = Enum.class.getDeclaredField("ordinal");

            long nameOffset = unsafe.objectFieldOffset(nameField);
            long ordinalOffset = unsafe.objectFieldOffset(ordinalField);

            unsafe.putObject(newCategory, nameOffset, enumName);
            unsafe.putInt(newCategory, ordinalOffset, newOrdinal);

            valuesList.add(newCategory);
            CategoryType[] newValuesArray = valuesList.toArray(new CategoryType[0]);

            $VALUES = newValuesArray;

            Field entriesField = CategoryType.class.getDeclaredField("$ENTRIES");
            Object base = unsafe.staticFieldBase(entriesField);
            long offset = unsafe.staticFieldOffset(entriesField);
            var newEntries = EnumEntriesKt.enumEntries(newValuesArray);
            unsafe.putObject(base, offset, newEntries);
        } catch (Exception e) {
            NoammAddons.logger.error("Error while adding NEXCLUSIVE category type", e);
            e.printStackTrace();
        }
    }
}
