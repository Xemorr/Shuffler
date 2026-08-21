package me.xemor.shuffler.game;

import me.xemor.shuffler.sprite.SpriteMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import java.util.Set;

public class SearchingForFormatter {

    public static Component formatSearchMessage(Set<Material> materials) {
        if (materials.isEmpty()) {
            return Component.text("Nothing", NamedTextColor.GRAY);
        }

        TextComponent.Builder builder = Component.text();

        if (materials.size() == 1) {
            // Case: Single item - Name + Sprite
            Material mat = materials.iterator().next();
            builder.append(getFriendlyName(mat))
                    .append(Component.space())
                    .append(SpriteMap.get(mat));
        } else {
            // Case: Multiple items - Sprites only
            boolean first = true;
            for (Material mat : materials) {
                if (!first) {
                    builder.append(Component.space());
                }
                builder.append(SpriteMap.get(mat));
                first = false;
            }
        }

        return builder.build();
    }

    private static Component getFriendlyName(Material material) {
        return Component.translatable(material.translationKey());
    }
}