package io.github.devvydoo.levelingoverhaul.managers;

import io.github.devvydoo.levelingoverhaul.LevelingOverhaul;
import io.github.devvydoo.levelingoverhaul.util.DamagePopup;
import io.github.devvydoo.levelingoverhaul.util.HealingPopup;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

public class DamagePopupManager implements Listener {

    private final LevelingOverhaul plugin;

    public DamagePopupManager(LevelingOverhaul plugin) {
        this.plugin = plugin;
        for (World world : plugin.getServer().getWorlds())
            for (ArmorStand armorStand : world.getEntitiesByClass(ArmorStand.class))
                if (armorStand.isMarker() && armorStand.isCustomNameVisible())
                    armorStand.remove();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityGotHit(EntityDamageEvent event) {
        if (event.getDamage() > 0 && event.getEntity() instanceof LivingEntity && !(event.getEntity() instanceof ArmorStand))
            new DamagePopup(plugin, event.getFinalDamage(), (LivingEntity) event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityGotHealed(EntityRegainHealthEvent event) {
        if (event.getAmount() > 0 && event.getEntity() instanceof LivingEntity && !(event.getEntity() instanceof ArmorStand))
            new HealingPopup(plugin, event.getAmount(), (LivingEntity)event.getEntity());
    }


}
