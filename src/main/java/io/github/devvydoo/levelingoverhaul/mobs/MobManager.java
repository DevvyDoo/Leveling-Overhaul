package io.github.devvydoo.levelingoverhaul.mobs;

import com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent;
import io.github.devvydoo.levelingoverhaul.LevelingOverhaul;
import io.github.devvydoo.levelingoverhaul.listeners.monitors.PlayerNametags;
import io.github.devvydoo.levelingoverhaul.mobs.custommobs.CustomMob;
import io.github.devvydoo.levelingoverhaul.mobs.custommobs.MobCorruptedSkeleton;
import io.github.devvydoo.levelingoverhaul.player.LeveledPlayer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Biome;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class MobManager implements Listener {

    private String LEVEL_COLOR = (ChatColor.GRAY + "" + ChatColor.BOLD);
    private String HOSTILE_MOB_COLOR = ChatColor.RED.toString();
    private String NEUTRAL_MOB_COLOR = ChatColor.WHITE.toString();
    private String TAMED_MOB_COLOR = ChatColor.GREEN.toString();
    private String BOSS_MOB_COLOR = ChatColor.DARK_PURPLE.toString();

    private LevelingOverhaul plugin;
    private HashMap<LivingEntity, MobStatistics> entityToLevelMap = new HashMap<>();
    private HashMap<LivingEntity, CustomMob> entityToCustomMobInstanceMap = new HashMap<>();
    private final int MOB_CLEANUP_DELAY = 20 * 60 * 5;

    /**
     * We need to make our plugin able to recover on a world that already has entities setup.
     *
     * @param worlds - A list of World objects that are currently on the server
     */
    public MobManager(LevelingOverhaul plugin, List<World> worlds) {
        this.plugin = plugin;
        // Loop through all the worlds
        plugin.getLogger().info("Starting mob manager...");
        int times = 0;
        long start = System.currentTimeMillis();
        for (World w : worlds) {
            // Loop through all the entities
            for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class)) {
                if (e instanceof Player || e instanceof ArmorStand)
                    continue;
                times++;
                this.entityToLevelMap.put(e, getMobStatistics(e));
            }
        }

        plugin.getLogger().info("Finished Mob Manager startup! " + times + " mobs successfully checked: " + (System.currentTimeMillis() - start) + "ms");

        // Make a task that runs every minute that cleans up mobs
        new BukkitRunnable() {
            @Override
            public void run() {
                for (LivingEntity e : new ArrayList<>(entityToLevelMap.keySet())) {
                    if (e.isDead() || entityToLevelMap.get(e).getLevel() == 1 || (e.getType().equals(EntityType.ARMOR_STAND) && e.isCustomNameVisible())) {
                        entityToLevelMap.remove(e);
                    }
                }
            }
        }.runTaskTimerAsynchronously(this.plugin, MOB_CLEANUP_DELAY, MOB_CLEANUP_DELAY);
    }

    public HashMap<LivingEntity, MobStatistics> getEntityToLevelMap() {
        return entityToLevelMap;
    }

    /**
     * Can be used to spawn a mob with a pre-determined level, overriding natural flow for a normal mob spawn
     *
     * @return The entity created, allowing for further customization
     */
    public LivingEntity spawnLeveledMob(Location location, EntityType entityType, String name, int level){

        if (level < 1)
            throw new IllegalArgumentException("Mob level cannot be less than 1!");

        assert entityType.getEntityClass() != null;

        LivingEntity entity = (LivingEntity) location.getWorld().spawn(location, entityType.getEntityClass());

        entityToLevelMap.put(entity, new MobStatistics(entity, level, name));
        setEntityAttributes(entity, level);
        setEntityNametag(entity);
        return entity;

    }

    public int getMobLevel(LivingEntity mob) {
        if (mob instanceof Player) {
            return ((Player) mob).getLevel();
        }
        try {
            return this.entityToLevelMap.get(mob).getLevel();
        } catch (NullPointerException e) {
            MobStatistics stats = getMobStatistics(mob);
            this.entityToLevelMap.put(mob, stats);
            return stats.getLevel();
        }
    }

    private int getAveragePlayerLevel(LivingEntity entity, int distance, boolean wantYModifier, int levelCap) {
        int totalLevels = 1;
        int numPlayers = 0;
        for (Player p : entity.getWorld().getPlayers()) {
            if (p.getGameMode().equals(GameMode.SPECTATOR) || p.getGameMode().equals(GameMode.CREATIVE)) {
                continue;
            }
            if (p.getLocation().distance(entity.getLocation()) < distance) {
                numPlayers++;
                totalLevels += p.getLevel();
            }
        }
        int yModifier = wantYModifier && entity.getLocation().getY() < 50 ? (int) (12 - entity.getLocation().getY() / 7) : 0;
        if (numPlayers > 0) {
            int level = (int) (totalLevels / numPlayers / 1.2 + (Math.random() * 5 - 3 + yModifier));
            if (level < 1)
                level = 1;
            return Math.min(level, levelCap);
        }
        return Math.min((int) (Math.random() * 5 + yModifier), levelCap);
    }

    private MobStatistics getMobStatistics(LivingEntity entity) {
        MobStatistics stats;

        if (entityToLevelMap.containsKey(entity))
            return entityToLevelMap.get(entity);

        try {
            stats = new MobStatistics(entity);
        } catch (NullPointerException | NumberFormatException | ArrayIndexOutOfBoundsException error) {
            stats = new MobStatistics(entity, this.calculateEntityLevel(entity), entity.getName());
        }
        return stats;
    }

    /**
     * Simply syncs up mob with what stats it should have, generates new stats if not contained
     *
     * @param entity The entity to update
     */
    private void updateMobWithStatistics(LivingEntity entity){
        MobStatistics statistics = getMobStatistics(entity);
        setEntityAttributes(entity, statistics.getLevel());
        setEntityNametag(entity);
    }

    /**
     * Pass in an entity, and update what its nametag should be. Call this method if no HP modification is being made
     *
     * @param entity - LivingEntity to give a name to
     */
    private void setEntityNametag(LivingEntity entity) {
        this.setEntityNametag(entity, 0);
    }

    /**
     * Pass in an entity and how much their HP is changing by. This will update their nametag to what is should be
     *
     * @param entity         - LivingEntity to give a name to
     * @param hpModification - Some cases we need do modify how much HP the entity has, so this is an adjustment
     */
    private void setEntityNametag(LivingEntity entity, double hpModification) {

        // Try to retrieve the entity, if we fail, it means the entity is not registered

        MobStatistics mobStats = getMobStatistics(entity);
        int level = mobStats.getLevel();

        // Now we need to see if our nametag should be white or red determining if they're hostile
        String nametagColor;
        if (entity instanceof Boss)
            nametagColor = BOSS_MOB_COLOR;
        else if (entity instanceof Monster)
            nametagColor = HOSTILE_MOB_COLOR;
        else if (entity instanceof Tameable && ((Tameable) entity).isTamed())
            nametagColor = TAMED_MOB_COLOR;
        else
            nametagColor = NEUTRAL_MOB_COLOR;

        // Do we need to make an hp modification?
        double entityHP = entity.getHealth() + entity.getAbsorptionAmount();

        if (hpModification != 0)
            entityHP += hpModification;

        if (entityHP <= 0)
            entityHP = 0;

        String hpTextColor = PlayerNametags.getChatColorFromHealth(entityHP, entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());

        // We should be good to set their name
        entity.setCustomName(null);
        entity.setCustomName(LEVEL_COLOR + "Lv. " + level + " " + nametagColor + ChatColor.stripColor(mobStats.getName()) + " " + ChatColor.DARK_RED + "❤" + hpTextColor + (int) entityHP);
    }

    /**
     * This method is called under the 3 conditions
     * - An entity was just spawned, and needs stats like level, hp, etc
     * - The plugin was just loaded, so we need to iterate through all current entities and register their stats if needed
     * - An entity was damaged that was not registered somehow
     *
     * @param entity The entity to calculate a level for
     * @return an int representing the level of the entity
     */
    private int calculateEntityLevel(LivingEntity entity) {

        // We need to do 2 things, first, calculate what level the entity should be. Then setup their statistics

        int level;

        switch (entity.getType()) {

            // Early game mobs, stick with player level but cap at 30
            case ZOMBIE:
            case SPIDER:
            case SKELETON:
            case CREEPER:
                level = getAveragePlayerLevel(entity, 250, true, 80);
                break;

            // Caves
            case CAVE_SPIDER:
            case SLIME:
            case WITCH:
                level = 7 + (int)(Math.random() * 8);
                break;

            // Desert mobs ~15-20
            case HUSK:
            case STRAY:
                level = 15 + (int)(Math.random() * 5);
                break;

            // Ocean mobs ~25-30
            case GUARDIAN:
            case DROWNED:
                level = 25 + (int)(Math.random() * 5);
                break;
            case ELDER_GUARDIAN:
                level = 35;
                break;

            // Village and pillage ~35-40
            case VILLAGER:
            case PILLAGER:
            case VINDICATOR:
            case VEX:
            case RAVAGER:
            case IRON_GOLEM:
            case ZOMBIE_VILLAGER:
            case ILLUSIONER:
            case EVOKER:
                level = 35 + (int)(Math.random() * 5);
                break;

            // Stronghold
            case SILVERFISH:
                level = 55 + (int)(Math.random() * 5);
                break;

            case ENDERMAN:
                if (entity.getWorld().getEnvironment().equals(World.Environment.NORMAL)) { level = (int) (Math.random() * 16 + 40); }  // ~ 40 - 55 in overworld
                else if (entity.getWorld().getEnvironment().equals(World.Environment.NETHER)) { level = (int) (Math.random() * 25 + 45); } // ~ 45 - 70 in nether
                else {
                    Biome biome = entity.getLocation().getBlock().getBiome();
                    if (biome.equals(Biome.THE_END)) { level = (int) (Math.random() * 5 + 58); }
                    else if (biome.equals(Biome.END_HIGHLANDS)) { level = (int) (Math.random() * 5 + 67); }
                    else if (biome.equals(Biome.END_MIDLANDS)) { level = (int) (Math.random() * 5 + 63); }
                    else { level = (int) (Math.random() * 5 + 70); }
                }
                break;

            case SHULKER:
            case ENDERMITE:
                level = (int) (Math.random() * 6 + 60);
                break;

            case WITHER:  // TODO: Give custom logic
                level = 80;
                break;
            case ENDER_DRAGON:
                level = 2;
                int totalPlayerLevels = 0;
                int numPlayers = entity.getWorld().getPlayers().size();
                for (Player p : entity.getWorld().getPlayers()) {
                    totalPlayerLevels += p.getLevel();
                }
                if (numPlayers > 0)
                    level += totalPlayerLevels / numPlayers;
                break;

            // Nether plains
            case PIG_ZOMBIE:
            case MAGMA_CUBE:
                level = 38 + (int)(Math.random() * 7);
                break;
            case GHAST:
                level = 42 + (int)(Math.random() * 7);
                break;

            // Nether fortress
            case BLAZE:
                level = 45 + (int)(Math.random() * 5);
                break;
            case WITHER_SKELETON:
                level = 50 + (int)(Math.random() * 5);
                break;

            case POLAR_BEAR:
            case TRADER_LLAMA:
                level = 15;
                break;

            case PHANTOM:
                Phantom phantom = (Phantom) entity;
                if (phantom.getSpawningEntity() != null) {
                    Player target = plugin.getServer().getPlayer(phantom.getSpawningEntity());
                    if (target != null) {
                        level = target.getLevel();
                        break;
                    }
                }
                level = 10;
                break;


            case WOLF:
            case CAT:
            case PARROT:
            case HORSE:
            case SKELETON_HORSE:
            case ZOMBIE_HORSE:
            case LLAMA:
            case MULE:
            case DONKEY:
                Tameable tamedEntity = (Tameable) entity;
                level = tamedEntity.getOwner() != null && tamedEntity.getOwner() instanceof Player ? ((Player)tamedEntity.getOwner()).getLevel() : (int)(Math.random() * 5 + 10);
                break;

            case BEE:
            case WANDERING_TRADER:
            case FOX:
                level = (int)(Math.random() * 5 + 3);
                break;

            case PIG:
            case COW:
            case MUSHROOM_COW:
            case SHEEP:
            case PANDA:
            case SQUID:
            case DOLPHIN:
                level =  Math.random() < .5 ? 2 : 3;
                break;

            case CHICKEN:
            case SALMON:
            case RABBIT:
            case COD:
            case BAT:
            case OCELOT:
            case SNOWMAN:
            case PUFFERFISH:
            case TROPICAL_FISH:
            case TURTLE:
            case ARMOR_STAND:
                level = 1;
                break;

            default:
                level = 1;
                plugin.getLogger().warning("Entity " + entity + " was not defined to have a level in MobManager. Defaulting to level 1");
                break;
        }

        // Now that we have a level calculated, let's setup the mob's stats in a similar fashion but in a different method, we do this because it's a little more readable and easier to maintain
        this.setEntityAttributes(entity, level);

        // Now we have to return the level back
        return level;
    }

    /***
     * Sets up entity attributes upon being spawned, or when its stats are first calculated
     *
     * @param entity The entity to calculate stats for
     * @param level The level the entity is/ is supposed to be
     */
    private void setEntityAttributes(LivingEntity entity, int level){

        switch (entity.getType()){

            case ZOMBIE:
            case ZOMBIE_VILLAGER:
            case HUSK:
            case DROWNED:
                if (entity.getEquipment() != null) {
                    if (level >= 20 && level < 30)
                        entity.getEquipment().setItemInMainHand(new ItemStack(Material.WOODEN_SWORD));
                    else if (level <= 30)
                        entity.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_SWORD));
                    else if (level <= 35)
                        entity.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                    else if (level <= 40)
                        entity.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
                    else {
                        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
                        sword.addEnchantment(Enchantment.DURABILITY, 1);
                        entity.getEquipment().setItemInMainHand(sword);
                        entity.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                    }
                    entity.getEquipment().setItemInMainHandDropChance(0);  // NEVER ALLOW IT TO DROP
                }
                break;

            case CAVE_SPIDER:
            case SPIDER:
                if (level > 30) { entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 9999, level / 30)); }
                break;

            case SKELETON:
                if (entity.getEquipment() != null && level > 25) {
                    ItemStack bow = new ItemStack(Material.BOW);
                    bow.addUnsafeEnchantment(Enchantment.DURABILITY, 1);
                    entity.getEquipment().setItemInMainHand(bow);
                    entity.getEquipment().setItemInMainHandDropChance(0);
                    entity.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                }
                break;

            case CREEPER:
                Creeper creeper = (Creeper) entity;
                if (level > 30 && level < 60) {
                    if (Math.random() < level / 100.) {
                        creeper.setPowered(true);
                    }
                } else if (level >= 60) {
                    creeper.setPowered(true);
                }
                creeper.setMaxFuseTicks((int) (level > 80 ? 2 : 40 - level / 3.));
                break;

            case ENDERMAN:
                if (entity.getEquipment() != null) {
                    ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
                    sword.addUnsafeEnchantment(Enchantment.KNOCKBACK, 3);
                    entity.getEquipment().setItemInMainHand(sword);
                    entity.getEquipment().setItemInMainHandDropChance(0);
                }
                break;

            case WITHER:  // TODO: Give custom logic
            case ELDER_GUARDIAN: // TODO: Give custom logic
            case ENDER_DRAGON:
                break;

            case PIG_ZOMBIE:
                if (entity.getEquipment() != null) {
                    ItemStack goldSword = new ItemStack(Material.GOLDEN_SWORD);
                    goldSword.addUnsafeEnchantment(Enchantment.DURABILITY, 1);
                    entity.getEquipment().setItemInMainHand(goldSword);
                    entity.getEquipment().setItemInMainHandDropChance(0);
                }
                break;

            case WITHER_SKELETON:
                if (entity.getEquipment() != null) {
                    ItemStack stoneSword = new ItemStack(Material.STONE_SWORD);
                    stoneSword.addUnsafeEnchantment(Enchantment.DURABILITY, 1);
                    entity.getEquipment().setItemInMainHand(stoneSword);
                    entity.getEquipment().setItemInMainHandDropChance(0);
                }
                break;

            default:
                plugin.getLogger().finest("Entity " + entity + " was not defined to have attributes in MobManager. Defaulting to vanilla stats");
                break;
        }

        if (entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double expectedHP = calculateEntityHealth(entity, level);
            Objects.requireNonNull(entity.getAttribute(Attribute.GENERIC_MAX_HEALTH)).setBaseValue(expectedHP);
            entity.setHealth(expectedHP);
        }
    }

    /**
     * Calculates how much HP an entity should have based on a level
     *
     * @param entity The entity to calculate HP for
     * @param level The level the entity will be / already is
     * @return A double amount representing max HP
     */
    private double calculateEntityHealth(LivingEntity entity, int level){

        double baseHP =  level * level + 40;

        double multiplier;

        switch (entity.getType()) {

            // Passive mobs
            case SHEEP:
            case COW:
            case PIG:
            case MULE:
            case MUSHROOM_COW:
            case HORSE:
            case SKELETON_HORSE:
            case SQUID:
            case DONKEY:
            case DOLPHIN:
            case TURTLE:
            case VILLAGER:
            case ZOMBIE_HORSE:
            case TRADER_LLAMA:
            case WANDERING_TRADER:
                multiplier = .75;
                break;

            case OCELOT:
            case PARROT:
            case TROPICAL_FISH:
            case SNOWMAN:
            case CHICKEN:
            case RABBIT:
            case SALMON:
            case BAT:
            case CAT:
            case COD:
                multiplier = .4;
                break;

            // Babies
            case SILVERFISH:
            case BEE:
            case VEX:
            case ENDERMITE:
            case PUFFERFISH:
                multiplier = .5;
                break;

            // Small tier
            case CREEPER:
            case EVOKER:
            case SPIDER:
            case CAVE_SPIDER:
            case SHULKER:
            case PHANTOM:
            case GHAST:
            case POLAR_BEAR:
            case PANDA:
            case FOX:
            case WOLF:
            case LLAMA:
                multiplier = .8;
                break;


            // Mid tier
            case HUSK:
            case ZOMBIE:
            case ZOMBIE_VILLAGER:
            case DROWNED:
            case SKELETON:
            case STRAY:
            case BLAZE:
            case ILLUSIONER:
            case PIG_ZOMBIE:
            case VINDICATOR:
            case PILLAGER:
            case GUARDIAN:
            case WITCH:
                multiplier = 1;
                break;

            // High tier
            case ENDERMAN:
            case WITHER_SKELETON:
            case RAVAGER:
            case IRON_GOLEM:
                multiplier = 1.35;
                break;

            // Special cases
            case SLIME:
            case MAGMA_CUBE:
                int size = ((Slime) entity).getSize() + 1;
                multiplier = .2 + size * .2;
                break;

            case ENDER_DRAGON:
            case WITHER:
            case GIANT:
                multiplier = 300;
                for (Player ignored : entity.getLocation().getNearbyPlayers(500))
                    multiplier += Math.random() * 50 + 100;
                break;

            default:
                plugin.getLogger().warning("Came across unexpected entity for HP calculation: " + entity.getType());
                multiplier = 1;
                break;
        }

        multiplier += ((Math.random() - .5) / 10.);
        return baseHP * multiplier;
    }

    /**
     * Adds a LivingEntity to the map of statistics so we can retrieve things such as level easier
     *
     * @param event EntitySpawnEvent
     */
    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {

        // This can happen?
        if (event.getEntityType().equals(EntityType.ARMOR_STAND))
            return;

        // We only care about living entities
        if (!(event.getEntity() instanceof LivingEntity))
            return;

        // We don't care about players or armor stands
        if (event.getEntity() instanceof Player || event.getEntity() instanceof ArmorStand)
            return;

        LivingEntity entity = (LivingEntity) event.getEntity();

        getMobStatistics(entity);  // Will handle putting the mob in the entity map if needed
        this.setEntityNametag(entity);
    }

    /**
     * Mainly updates entity nametags when hit
     *
     * @param event EntityDamageEvent
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDamage(EntityDamageEvent event) {

        // We only care about living entities
        if (!(event.getEntity() instanceof LivingEntity))
            return;

        // We don't care about Players
        if (event.getEntity() instanceof Player || event.getEntity() instanceof ArmorStand)
            return;

        // Marked
        event.getEntity().setCustomNameVisible(true);

        // Update nametag
        this.setEntityNametag((LivingEntity) event.getEntity(), event.getFinalDamage() * -1);

    }

    /**
     * Cleans up entities from memory when they die
     *
     * @param event EntityDeathEvent
     */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        // Remove the entity from our map if needed.
        this.entityToLevelMap.remove(event.getEntity());

        if (entityToCustomMobInstanceMap.containsKey(event.getEntity())){
            CustomMob mob = entityToCustomMobInstanceMap.get(event.getEntity());
            List<ItemStack> drops = event.getDrops();
            drops.clear();
            drops.addAll(mob.getLootTable().roll());
        }
    }

    /**
     * Updates the level of a mob when tamed to the level of the owner
     *
     * @param event The EntityTameEvent we are listening to
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityTamed(EntityTameEvent event){

        if (event.getOwner() instanceof Player){
            int newEntitylevel = ((Player)event.getOwner()).getLevel();  // Gets the level of the player who tamed
            getMobStatistics(event.getEntity()).setLevel(newEntitylevel);  // Update the level of the mob in memory
            updateMobWithStatistics(event.getEntity());  // Apply the changes
            event.getEntity().getWorld().playSound(event.getEntity().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .5f, .5f);
        }

    }

    @EventHandler
    public void onCustomMobSpawn(CreatureSpawnEvent event){

        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM || Math.random() > .2)
            return;

        long start = System.currentTimeMillis();

        System.out.println("spawning a skeleboi because of " + event.getEntity().getName() + " at location " + event.getEntity().getLocation().toVector());

        Location entityLocation = event.getLocation();
        World.Environment environment = entityLocation.getWorld().getEnvironment();
        Biome biome = entityLocation.getWorld().getBiome(entityLocation.getBlockX(), entityLocation.getBlockY(), entityLocation.getBlockZ());

        // What should we do in the end?
        switch (environment) {

            case THE_END:
                LivingEntity cs = spawnLeveledMob(entityLocation, EntityType.STRAY, "Corrupted Skeleton", 70);
                MobCorruptedSkeleton mcs = new MobCorruptedSkeleton(EntityType.STRAY);
                mcs.setup(cs);
                entityToCustomMobInstanceMap.put(cs, mcs);
                break;

        }

        System.out.println("done, time taken: " + (System.currentTimeMillis() - start) + "ms");


    }

}
