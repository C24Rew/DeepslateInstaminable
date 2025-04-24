package com.c24rew;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.HeightLimitView;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.enchantment.EnchantmentHelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

public class Deepslateinstamine implements ModInitializer {
    public static final String MOD_ID = "deepslate-instaminable";
    private static final Set<Block> DEEPSLATE_BLOCKS = new HashSet<>();
    private static final Set<Block> BEACON_BASE_BLOCKS = new HashSet<>();
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private final Map<UUID, BeaconBlockEntity> playersInBeaconArea = new HashMap<>();
    private final Map<UUID, Integer> playersWithSuperHaste = new HashMap<>();
    private final Map<UUID, Long> playerEffectExpirations = new HashMap<>();
    private final Set<UUID> playersWithActiveSearch = ConcurrentHashMap.newKeySet();
    private static final int MIN_SEARCH_INTERVAL = 20 * 5;
    private final Map<UUID, Long> lastSearchTimeByPlayer = new HashMap<>();
    private final BeaconSpatialIndex beaconSpatialIndex = new BeaconSpatialIndex();
    private long lastFullCacheUpdate = 0;
    private static final int FULL_CACHE_UPDATE_INTERVAL = 20 * 60 * 5;
    private final ThreadLocal<Set<BlockPos>> checkedBlocks = ThreadLocal.withInitial(HashSet::new);
    private final ThreadLocal<Set<BlockPos>> tempBlockPosSet = ThreadLocal.withInitial(HashSet::new);
    private final ThreadLocal<Set<BeaconBlockEntity>> tempBeaconSet = ThreadLocal.withInitial(HashSet::new);
    private static final String BEACON_CACHE_FILE = "beacons.dat";
    private final Map<BlockPos, Long> verifiedBeaconCache = new HashMap<>();
    private static final long BEACON_VALIDATION_INTERVAL = 20 * 30;
    private boolean cacheChanged = false;
    private final Map<RegistryKey<World>, Set<BlockPos>> beaconCache = new ConcurrentHashMap<>();

    private final Map<UUID, Long> lastLocationSearchTime = new HashMap<>();
    private static final int SEARCH_COOLDOWN_TICKS = 20 * 5;

    private static class BeaconRange {
        final int minX, minY, minZ;
        final int maxX, maxY, maxZ;
        
        BeaconRange(BlockPos beaconPos, int range) {
            minX = beaconPos.getX() - range;
            maxX = beaconPos.getX() + range;
            minY = beaconPos.getY() - range;
            maxY = Integer.MAX_VALUE;
            minZ = beaconPos.getZ() - range;
            maxZ = beaconPos.getZ() + range;
        }
        
        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX &&
                   pos.getY() >= minY && pos.getY() <= maxY &&
                   pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    private final Map<BlockPos, BeaconRange> beaconRangeCache = new HashMap<>();

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Deepslate Instaminable");

        initializeBlocks();
        loadBeaconCache();
        registerEvents();
    }
    
    private void initializeBlocks() {
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_COAL_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_DIAMOND_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_GOLD_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_COPPER_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_EMERALD_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_IRON_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_LAPIS_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_REDSTONE_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.COBBLED_DEEPSLATE);
        DEEPSLATE_BLOCKS.add(Blocks.GRAVEL);
        DEEPSLATE_BLOCKS.add(Blocks.TUFF);
        
        BEACON_BASE_BLOCKS.add(Blocks.IRON_BLOCK);
        BEACON_BASE_BLOCKS.add(Blocks.GOLD_BLOCK);
        BEACON_BASE_BLOCKS.add(Blocks.DIAMOND_BLOCK);
        BEACON_BASE_BLOCKS.add(Blocks.EMERALD_BLOCK);
        BEACON_BASE_BLOCKS.add(Blocks.NETHERITE_BLOCK);
    }
    
    private void registerEvents() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && DEEPSLATE_BLOCKS.contains(state.getBlock())) {
                handleDeepslateBreak(serverPlayer);
            }
            
            if (blockEntity instanceof BeaconBlockEntity && world instanceof ServerWorld) {
                removeBeaconFromCache((ServerWorld)world, pos);
            }
        });
        
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long currentTick = server.getTicks();
            
            updatePlayersWithSuperHaste(server);
            
            if (currentTick % 20 == 0) {
                updatePlayersInBeaconArea(server);
            }
            
            if (currentTick % 600 == 0) {
                boolean hayJugadoresConHerramientas = false;
                
                for (ServerWorld world : server.getWorlds()) {
                    for (ServerPlayerEntity player : world.getPlayers()) {
                        if (isHoldingNetheriteTool(player)) {
                            hayJugadoresConHerramientas = true;
                            break;
                        }
                    }
                    if (hayJugadoresConHerramientas) break;
                }
                
                if (hayJugadoresConHerramientas) {
                    server.execute(() -> {
                        for (ServerWorld world : server.getWorlds()) {
                            if (beaconSpatialIndex.hasAny(world.getRegistryKey())) {
                                validateBeaconCache(world);
                            }
                        }
                    });
                }
            }
            
            if (currentTick - lastFullCacheUpdate >= FULL_CACHE_UPDATE_INTERVAL) {
                boolean necesitaActualizacion = false;
                
                for (ServerWorld world : server.getWorlds()) {
                    for (ServerPlayerEntity player : world.getPlayers()) {
                        // Only consider players with netherite tools but NO haste effect yet
                        if (isHoldingNetheriteTool(player) && 
                            player.getStatusEffect(StatusEffects.HASTE) == null) {
                            necesitaActualizacion = true;
                            break;
                        }
                    }
                    if (necesitaActualizacion) break;
                }
                
                if (necesitaActualizacion) {
                    server.execute(() -> {
                        for (ServerWorld world : server.getWorlds()) {
                            for (ServerPlayerEntity player : world.getPlayers()) {
                                // Only search for players with netherite tools and NO haste effect
                                if (isHoldingNetheriteTool(player) && 
                                    player.getStatusEffect(StatusEffects.HASTE) == null) {
                                    initBeaconCacheForSpecificLocation(world, player.getBlockPos(), player.getUuid());
                                }
                            }
                        }
                    });
                }
                
                lastFullCacheUpdate = currentTick;
            }
            
            if (currentTick % 18000 == 0) {
                saveBeaconCacheIfNeeded();
            }
        });
        
        ServerWorldEvents.LOAD.register((server, world) -> {
            server.execute(() -> {
                if (beaconSpatialIndex.hasAny(world.getRegistryKey())) {
                    validateBeaconCache(world);
                } else {
                    initBeaconCacheForWorld(world);
                }
            });
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Saving beacon cache before server shutdown...");
            saveBeaconCacheIfNeeded();
        });
    }
    
    private void checkLoadedChunksForNewBeacons(net.minecraft.server.MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            Set<BlockPos> existingBeacons = beaconSpatialIndex.getNearbyBeacons(world.getRegistryKey(), BlockPos.ORIGIN, Integer.MAX_VALUE);
            
            for (ServerPlayerEntity player : world.getPlayers()) {
                UUID playerId = player.getUuid();
                
                if (playersWithActiveSearch.contains(playerId)) {
                    continue;
                }
                
                long currentTime = server.getTicks();
                Long lastSearchTime = lastSearchTimeByPlayer.getOrDefault(playerId, 0L);
                if (currentTime - lastSearchTime < MIN_SEARCH_INTERVAL) {
                    continue;
                }
                
                playersWithActiveSearch.add(playerId);
                
                long startTimeMs = System.currentTimeMillis();
                LOGGER.debug("Checking loaded chunks for beacons near {} in {}...", 
                    player.getName().getString(), world.getRegistryKey().getValue());
                
                try {
                    BlockPos playerPos = player.getBlockPos();
                    int searchRadius = 80;
                    
                    lastSearchTimeByPlayer.put(playerId, currentTime);
                } finally {
                    long endTimeMs = System.currentTimeMillis();
                    long durationMs = endTimeMs - startTimeMs;
                    LOGGER.debug("Chunk verification for {} completed in {}ms", 
                        player.getName().getString(), durationMs);
                    
                    playersWithActiveSearch.remove(playerId);
                }
            }
        }
    }

    private void updateBeaconCache(net.minecraft.server.MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            initBeaconCacheForWorld(world);
        }
    }
    
    private void initBeaconCacheForWorld(ServerWorld world) {
        Set<BlockPos> beacons = new HashSet<>();
        
        for (ServerPlayerEntity player : world.getPlayers()) {
            UUID playerId = player.getUuid();
            
            if (playersWithActiveSearch.contains(playerId)) {
                continue;
            }
            
            playersWithActiveSearch.add(playerId);
            
            long startTimeMs = System.currentTimeMillis();

            try {
                BlockPos playerPos = player.getBlockPos();
                int searchRadius = 200;
                
            } finally {
                long endTimeMs = System.currentTimeMillis();
                long durationMs = endTimeMs - startTimeMs;
                
                playersWithActiveSearch.remove(playerId);
            }
        }

        LOGGER.debug("Beacon cache initialized for {}: {} beacons found", world.getRegistryKey().getValue(), beacons.size());
    }

    private void initBeaconCacheForSpecificLocation(ServerWorld world, BlockPos playerPos, UUID playerId) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(playerId);
        if (player == null || !hasHasteEffect(player)) {
            return;
        }

        // 10 second cooldown between searches
        long currentTick = player.getServer().getTicks();
        Long lastSearch = lastLocationSearchTime.getOrDefault(playerId, 0L);
        if (currentTick - lastSearch < SEARCH_COOLDOWN_TICKS) {
            return;
        }
        lastLocationSearchTime.put(playerId, currentTick);

        // try to find a cached beacon first
        Set<BlockPos> cachedPositions = beaconSpatialIndex.getNearbyBeacons(
            world.getRegistryKey(), playerPos, 3);
        BeaconBlockEntity nearestCached = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : cachedPositions) {
            BlockEntity e = world.getBlockEntity(pos);
            if (e instanceof BeaconBlockEntity beacon
             && isBeaconActive(beacon)
             && isPlayerInBeaconRange(player, beacon)) {
                double d = pos.getSquaredDistance(playerPos);
                if (d < bestDist) {
                    bestDist = d;
                    nearestCached = beacon;
                }
            }
        }
        if (nearestCached != null) {
            playersInBeaconArea.put(playerId, nearestCached);
            applyAndCacheHasteEffect(player, 7, 10);
            playersWithSuperHaste.put(playerId, 10);

            beaconSpatialIndex.add(world.getRegistryKey(), nearestCached.getPos());
            cacheChanged = true;
            return;
        }

        long startTimeMs = System.currentTimeMillis();
        int searchRadius = 80;
        int beaconsFound = 0;
        BeaconBlockEntity nearestBeacon = null;
        double closestDistance = Double.MAX_VALUE;
        Set<BlockPos> beacons = new HashSet<>();

        for (int chunkX = (playerPos.getX() - searchRadius) >> 4;
             chunkX <= (playerPos.getX() + searchRadius) >> 4; chunkX++) {
            for (int chunkZ = (playerPos.getZ() - searchRadius) >> 4;
                 chunkZ <= (playerPos.getZ() + searchRadius) >> 4; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;

                int minY = Math.max(-60, playerPos.getY() - 51);
                int maxY = Math.min(world.getHeight(), playerPos.getY() + 51);

                for (int y = minY; y <= maxY; y += 5) {
                    int initialSize = beacons.size();
                    Set<BeaconBlockEntity> foundBeacons = new HashSet<>();
                    scanChunkForBeaconsWithEntities(
                        world,
                        new BlockPos(chunkX << 4, y, chunkZ << 4),
                        16,
                        beacons,
                        foundBeacons
                    );
                    if (beacons.size() > initialSize) {
                        beaconsFound += beacons.size() - initialSize;
                        ServerPlayerEntity playerEntity =
                            world.getServer().getPlayerManager().getPlayer(playerId);
                        for (BeaconBlockEntity beacon : foundBeacons) {
                            if (playerEntity == null) continue;
                            double distance = beacon.getPos().getSquaredDistance(playerPos);
                            if (isPlayerInBeaconRange(playerEntity, beacon)
                             && distance < closestDistance) {
                                closestDistance = distance;
                                nearestBeacon = beacon;
                            }
                        }
                    }
                }
            }
        }
        long durationMs = System.currentTimeMillis() - startTimeMs;

        if (nearestBeacon != null) {
            beaconSpatialIndex.add(world.getRegistryKey(), nearestBeacon.getPos());
            cacheChanged = true;

            ServerPlayerEntity playerEntity =
                world.getServer().getPlayerManager().getPlayer(playerId);
            if (playerEntity != null) {
                playersInBeaconArea.put(playerId, nearestBeacon);
                applyAndCacheHasteEffect(playerEntity, 7, 10);
                playersWithSuperHaste.put(playerId, 10);
            }
        }

        if (beaconsFound > 0 && nearestBeacon != null) {
            LOGGER.info(
                "Search in specific location completed in {}ms. Beacons found: {}",
                durationMs, beaconsFound
            );
        }
    }
    
    private void scanChunkForBeaconsWithEntities(ServerWorld world, BlockPos startPos, int size, 
                                           Set<BlockPos> beacons, Set<BeaconBlockEntity> foundEntities) {
        Set<BlockPos> checked = tempBlockPosSet.get();
        checked.clear();
        
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                BlockPos pos = startPos.add(x, 0, z);
                
                // Skip if already checked to avoid redundant work
                if (checked.contains(pos)) {
                    continue;
                }
                checked.add(pos);
                
                Block block = world.getBlockState(pos).getBlock();
                
                if (block == Blocks.BEACON) {
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (blockEntity instanceof BeaconBlockEntity beacon && isBeaconActive(beacon)) {
                        BlockPos immutablePos = pos.toImmutable();
                        beacons.add(immutablePos);
                        foundEntities.add(beacon);
                        
                        searchNearbyBeaconsWithEntities(world, pos, beacons, foundEntities, 3);
                    }
                } 
                else if (BEACON_BASE_BLOCKS.contains(block)) {
                    for (int y = 1; y <= 5; y++) {
                        BlockPos upPos = pos.up(y);
                        
                        // Skip if already checked
                        if (checked.contains(upPos)) {
                            continue;
                        }
                        checked.add(upPos);
                        
                        if (world.getBlockState(upPos).getBlock() == Blocks.BEACON) {
                            BlockEntity entity = world.getBlockEntity(upPos);
                            if (entity instanceof BeaconBlockEntity beacon && isBeaconActive(beacon)) {
                                BlockPos immutablePos = upPos.toImmutable();
                                beacons.add(immutablePos);
                                foundEntities.add(beacon);
                                
                                searchNearbyBeaconsWithEntities(world, upPos, beacons, foundEntities, 3);
                            }
                            break;
                        }
                    }
                }
            }
        }
        
        // We're done with the set, clear it for the next use
        checked.clear();
    }

    private void searchNearbyBeaconsWithEntities(ServerWorld world, BlockPos beaconPos, 
                                           Set<BlockPos> beacons, Set<BeaconBlockEntity> foundEntities, int searchSize) {
        int radius = searchSize / 2;
        // Create a new local HashSet instead of reusing the ThreadLocal set
        // This prevents skipping beacon checks due to positions already marked as checked by the outer method
        Set<BlockPos> checked = new HashSet<>();
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                
                BlockPos checkPos = beaconPos.add(dx, 0, dz);
                
                // Skip positions we've already checked
                if (checked.contains(checkPos)) {
                    continue;
                }
                checked.add(checkPos);
                
                if (world.getBlockState(checkPos).getBlock() == Blocks.BEACON) {
                    BlockEntity entity = world.getBlockEntity(checkPos);
                    if (entity instanceof BeaconBlockEntity beacon && isBeaconActive(beacon)) {
                        BlockPos immutablePos = checkPos.toImmutable();
                        beacons.add(immutablePos);
                        foundEntities.add(beacon);
                    }
                }
            }
        }
    }
    
    private void removeBeaconFromCache(ServerWorld world, BlockPos pos) {
        beaconSpatialIndex.remove(world.getRegistryKey(), pos);
        cacheChanged = true;
    }
    
    private void addBeaconToCache(ServerWorld world, BlockPos pos) {
        beaconSpatialIndex.add(world.getRegistryKey(), pos);
        cacheChanged = true; // Mark cache as changed so it will be saved
    }
    
    private void saveBeaconCacheIfNeeded() {
        if (cacheChanged) {
            saveBeaconCache();
            cacheChanged = false;
        }
    }

    private BeaconBlockEntity findNearestBeaconInRange(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        
        if (!isHoldingNetheriteTool(player)) {
            return null;
        }
        
        ServerWorld world = player.getServerWorld();
        BlockPos playerPos = player.getBlockPos();
        Set<BlockPos> worldBeacons = beaconSpatialIndex.getNearbyBeacons(world.getRegistryKey(), playerPos, 3);
        
        if (playersWithActiveSearch.contains(playerId)) {
            return null;
        }
        
        BeaconBlockEntity cachedBeacon = playersInBeaconArea.get(playerId);
        if (cachedBeacon != null && isBeaconActive(cachedBeacon) && isPlayerInBeaconRange(player, cachedBeacon)) {
            return cachedBeacon;
        }
        
        long currentTime = player.getServer().getTicks();
        Long lastSearchTime = lastSearchTimeByPlayer.getOrDefault(playerId, 0L);
        if (currentTime - lastSearchTime < MIN_SEARCH_INTERVAL) {
            return null;
        }
        
        if (worldBeacons == null || worldBeacons.isEmpty()) {
            lastSearchTimeByPlayer.put(playerId, currentTime);
            
            long startTimeMs = System.currentTimeMillis();
            
            initBeaconCacheForSpecificLocation(world, playerPos, playerId);
            worldBeacons = beaconSpatialIndex.getNearbyBeacons(world.getRegistryKey(), playerPos, 3);
            
            long endTimeMs = System.currentTimeMillis();
            LOGGER.debug("Beacon cache initialization took {}ms", (endTimeMs - startTimeMs));
            
            if (worldBeacons == null || worldBeacons.isEmpty()) {
                return null;
            }
        }
        
        playersWithActiveSearch.add(playerId);
        try {
            BeaconBlockEntity closestBeacon = null;
            double closestDistance = Double.MAX_VALUE;
            
            Set<BlockPos> beaconsToCheck = new HashSet<>(worldBeacons);
            Set<BlockPos> beaconsToRemove = new HashSet<>();
            
            for (BlockPos beaconPos : beaconsToCheck) {
                if (Math.abs(beaconPos.getX() - playerPos.getX()) > 51 || 
                    Math.abs(beaconPos.getZ() - playerPos.getZ()) > 51) {
                    continue;
                }
                
                if (!isValidBeacon(world, beaconPos, currentTime)) {
                    beaconsToRemove.add(beaconPos);
                    continue;
                }
                
                BlockEntity entity = world.getBlockEntity(beaconPos);
                if (entity instanceof BeaconBlockEntity beacon && isPlayerInBeaconRange(player, beacon)) {
                    double distance = beaconPos.getSquaredDistance(playerPos);
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closestBeacon = beacon;
                    }
                }
            }
            
            if (!beaconsToRemove.isEmpty()) {
                for (BlockPos pos : beaconsToRemove) {
                    removeBeaconFromCache(world, pos);
                }
            }
            
            return closestBeacon;
        } finally {
            playersWithActiveSearch.remove(playerId);
        }
    }
    


    private void updatePlayersInBeaconArea(net.minecraft.server.MinecraftServer server) {
        Map<UUID, BeaconBlockEntity> newPlayersInBeaconArea = new HashMap<>();
        Set<UUID> playersToRemoveEffects = new HashSet<>(playersInBeaconArea.keySet());
        long currentTick = server.getTicks();
        
        for (ServerWorld world : server.getWorlds()) {
            for (ServerPlayerEntity player : world.getPlayers()) {
                UUID playerId = player.getUuid();
                playersToRemoveEffects.remove(playerId);
                
                if (!isHoldingNetheriteTool(player)) {
                    continue;
                }
                
                // Only keep tracking players who already have a valid beacon
                BeaconBlockEntity currentBeacon = playersInBeaconArea.get(playerId);
                if (currentBeacon != null && isBeaconActive(currentBeacon) && isPlayerInBeaconRange(player, currentBeacon)) {
                    newPlayersInBeaconArea.put(playerId, currentBeacon);
                    
                    // Apply normal beacon haste if player has no effect
                    StatusEffectInstance currentEffect = player.getStatusEffect(StatusEffects.HASTE);
                    if (currentEffect == null) {
                        applyAndCacheHasteEffect(player, 1, 120);
                    }
                }
                // We only search for new beacons when breaking deepslate
            }
        }
        
        // Remove effects for players no longer in beacon range
        for (UUID playerId : playersToRemoveEffects) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                player.removeStatusEffect(StatusEffects.HASTE);
                playerEffectExpirations.remove(playerId);
                playersWithSuperHaste.remove(playerId);
            }
        }
        
        playersInBeaconArea.clear();
        playersInBeaconArea.putAll(newPlayersInBeaconArea);
    }
    
    private void updatePlayersWithSuperHaste(net.minecraft.server.MinecraftServer server) {
        playersWithSuperHaste.entrySet().removeIf(entry -> {
            UUID playerId = entry.getKey();
            int ticksLeft = entry.getValue() - 1;
            
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) return true;
            
            // Check if player is still in beacon range - if not, remove effect
            BeaconBlockEntity beacon = playersInBeaconArea.get(playerId);
            if (beacon == null || !isBeaconActive(beacon) || !isPlayerInBeaconRange(player, beacon)) {
                player.removeStatusEffect(StatusEffects.HASTE);
                playerEffectExpirations.remove(playerId);
                LOGGER.debug("Removing Super Haste from player {} - out of beacon range", player.getName().getString());
                return true;
            }
            
            // Normal expiration handling
            if (ticksLeft <= 0) {
                player.removeStatusEffect(StatusEffects.HASTE);
                
                if (beacon != null && isBeaconActive(beacon) && isPlayerInBeaconRange(player, beacon)) {
                    player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.HASTE,
                        120,
                        1,
                        false,
                        true,
                        true
                    ));
                    playerEffectExpirations.put(playerId, server.getTicks() + 120L);
                }
                
                return true;
            } else {
                entry.setValue(ticksLeft);
                return false;
            }
        });
    }
    
    private void handleDeepslateBreak(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        if (!isHoldingNetheriteTool(player)) {
            LOGGER.debug("Player {} without netherite tool when breaking deepslate", player.getName().getString());
            return;
        }
        
        StatusEffectInstance currentEffect = player.getStatusEffect(StatusEffects.HASTE);
        
        if (currentEffect != null && currentEffect.getAmplifier() >= 7) {
            player.removeStatusEffect(StatusEffects.HASTE);
            player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.HASTE, 20, 7, false, true, true
            ));
            playerEffectExpirations.put(playerId, player.getServer().getTicks() + 20L);
            playersWithSuperHaste.put(playerId, 20);
            return;
        }
        
        if (currentEffect != null && currentEffect.getAmplifier() == 1) {
            BeaconBlockEntity beacon = playersInBeaconArea.get(playerId);
            if (beacon != null && isBeaconActive(beacon) && isPlayerInBeaconRange(player, beacon)) {
                player.removeStatusEffect(StatusEffects.HASTE);
                player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.HASTE, 20, 7, false, true, true
                ));
                playerEffectExpirations.put(playerId, player.getServer().getTicks() + 20L);
                playersWithSuperHaste.put(playerId, 20);
                return;
            }
        }
        
        BeaconBlockEntity beacon = playersInBeaconArea.get(playerId);
        if (beacon == null || !isBeaconActive(beacon) || !isPlayerInBeaconRange(player, beacon)) {
            ServerWorld world = player.getServerWorld();
            BlockPos playerPos = player.getBlockPos();
            long currentTime = player.getServer().getTicks();
            Long lastSearch = lastSearchTimeByPlayer.getOrDefault(playerId, 0L);
            if (currentTime - lastSearch < MIN_SEARCH_INTERVAL / 20) {
                return;
            }
            lastSearchTimeByPlayer.put(playerId, currentTime);
            
            Set<BlockPos> worldBeacons = beaconSpatialIndex.getNearbyBeacons(
                world.getRegistryKey(), playerPos, 3
            );
            if (!worldBeacons.isEmpty()) {
                beacon = findNearestBeaconInRange(player);
            }
            if (beacon == null) {
                initBeaconCacheForSpecificLocation(world, playerPos, playerId);
                return;
            } else {
                playersInBeaconArea.put(playerId, beacon);
            }
        }
        
        player.removeStatusEffect(StatusEffects.HASTE);
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.HASTE, 20, 7, false, true, true
        ));
        playerEffectExpirations.put(playerId, player.getServer().getTicks() + 20L);
        playersWithSuperHaste.put(playerId, 20);
        LOGGER.debug("Applied Super Haste to player {} for breaking deepslate",
            player.getName().getString()
        );
    }
    
    private void applyAndCacheHasteEffect(ServerPlayerEntity player, int amplifier, int duration) {
        UUID playerId = player.getUuid();
        long expirationTime = player.getServer().getTicks() + duration;
        
        // Get current haste effect to check its level
        StatusEffectInstance currentEffect = player.getStatusEffect(StatusEffects.HASTE);
        
        // Always apply if the current effect is null, expired, or the new amplifier is higher
        Long currentExpiration = playerEffectExpirations.getOrDefault(playerId, 0L);
        if (currentExpiration <= player.getServer().getTicks() || 
            currentEffect == null || 
            currentEffect.getAmplifier() < amplifier) {
            
            player.removeStatusEffect(StatusEffects.HASTE);
            player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.HASTE,
                duration,
                amplifier,
                false,
                true,
                true
            ));
            
            playerEffectExpirations.put(playerId, expirationTime);
        }
    }

    private boolean isValidBeacon(ServerWorld world, BlockPos pos, long currentTick) {
        Long lastVerified = verifiedBeaconCache.get(pos);
        
        if (lastVerified != null && currentTick - lastVerified < BEACON_VALIDATION_INTERVAL) {
            return true;
        }
        
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return lastVerified != null;
        }
        
        BlockEntity entity = world.getBlockEntity(pos);
        boolean valid = entity instanceof BeaconBlockEntity && isBeaconActive((BeaconBlockEntity)entity);
        
        if (valid) {
            verifiedBeaconCache.put(pos.toImmutable(), currentTick);
        } else if (lastVerified != null) {
            verifiedBeaconCache.remove(pos);
        }
        
        return valid;
    }

    private void saveBeaconCache() {
        try {
            File dataDir = new File("config/deepslate-instamine");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }
            
            File cacheFile = new File(dataDir, "beacons.dat");
            
            BeaconCacheData cacheData = new BeaconCacheData();
            
            for (Map.Entry<RegistryKey<World>, Map<ChunkPos, Set<BlockPos>>> entry : beaconSpatialIndex.chunkToBeaconsMap.entrySet()) {
                String worldKey = entry.getKey().getValue().toString();
                Map<ChunkPos, Set<BlockPos>> worldChunks = entry.getValue();
                
                WorldBeaconData worldData = new WorldBeaconData();
                
                for (Set<BlockPos> chunkBeacons : worldChunks.values()) {
                    for (BlockPos pos : chunkBeacons) {
                        BeaconPos beaconPos = new BeaconPos(pos.getX(), pos.getY(), pos.getZ());
                        worldData.beacons.add(beaconPos);
                    }
                }
                
                cacheData.worlds.put(worldKey, worldData);
            }
            
            Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
                
            try (FileWriter writer = new FileWriter(cacheFile)) {
                gson.toJson(cacheData, writer);
            }
            
        } catch (IOException e) {
            LOGGER.error("Error saving beacon cache to JSON", e);
        }
    }
    
    private void loadBeaconCache() {
        try {
            File dataDir = new File("config/deepslate-instamine");
            File cacheFile = new File(dataDir, "beacons.dat");
            
            if (!cacheFile.exists()) {
                LOGGER.info("No beacon cache file found. Starting with empty cache.");
                return;
            }
            
            Gson gson = new Gson();
            
            BeaconCacheData cacheData;
            try (FileReader reader = new FileReader(cacheFile)) {
                cacheData = gson.fromJson(reader, BeaconCacheData.class);
            }
            
            if (cacheData == null || cacheData.worlds == null) {
                LOGGER.warn("Corrupt or empty beacon cache file. Starting with empty cache.");
                return;
            }
            
            beaconSpatialIndex.chunkToBeaconsMap.clear();
            
            for (Map.Entry<String, WorldBeaconData> entry : cacheData.worlds.entrySet()) {
                String key = entry.getKey();
                WorldBeaconData worldData = entry.getValue();
                
                if (worldData.beacons == null) {
                    LOGGER.warn("Null beacon data for world {}, skipping...", key);
                    continue;
                }
                
                try {
                    String[] parts = key.split(":", 2);
                    String namespace = parts.length > 1 ? parts[0] : "minecraft";
                    String path = parts.length > 1 ? parts[1] : key;
                    
                    Identifier worldId = Identifier.of(namespace, path);
                    
                    RegistryKey<World> worldKey = RegistryKey.of(
                        RegistryKey.ofRegistry(Identifier.of("minecraft", "dimension")),
                        worldId
                    );
                    
                    Map<ChunkPos, Set<BlockPos>> worldChunks = new HashMap<>();
                    
                    for (BeaconPos beaconPos : worldData.beacons) {
                        BlockPos pos = new BlockPos(beaconPos.x, beaconPos.y, beaconPos.z).toImmutable();
                        ChunkPos chunkPos = new ChunkPos(pos);
                        worldChunks.computeIfAbsent(chunkPos, k -> new HashSet<>()).add(pos);
                    }
                    
                    beaconSpatialIndex.chunkToBeaconsMap.put(worldKey, worldChunks);
                    
                    LOGGER.debug("Loaded {} beacons in dimension {}", worldData.beacons.size(), key);
                } catch (Exception e) {
                    LOGGER.warn("Error processing world key {}: {}", key, e.getMessage());
                }
            }
            
            LOGGER.info("JSON beacon cache successfully loaded. Worlds loaded: {}", beaconSpatialIndex.chunkToBeaconsMap.size());
            LOGGER.info("Verificando beacons almacenados en caché...");
            for (Map.Entry<RegistryKey<World>, Map<ChunkPos, Set<BlockPos>>> entry : 
                    beaconSpatialIndex.chunkToBeaconsMap.entrySet()) {
                RegistryKey<World> worldKey = entry.getKey();
                int totalBeacons = 0;
                
                for (Map.Entry<ChunkPos, Set<BlockPos>> chunkEntry : entry.getValue().entrySet()) {
                    totalBeacons += chunkEntry.getValue().size();
                }
                
                LOGGER.info("Dimensión {}: {} beacons cargados de caché", 
                    worldKey.getValue(), totalBeacons);
            }
        } catch (IOException e) {
            LOGGER.error("Error loading beacon cache", e);
        }
    }

    private void validateBeaconCache(ServerWorld world) {
        Map<ChunkPos, Set<BlockPos>> worldChunks = beaconSpatialIndex.chunkToBeaconsMap.get(world.getRegistryKey());
        if (worldChunks == null || worldChunks.isEmpty()) return;
        
        Set<BlockPos> toRemove = new HashSet<>();
        int validCount = 0;
        
        for (Map.Entry<ChunkPos, Set<BlockPos>> chunkEntry : worldChunks.entrySet()) {
            for (BlockPos pos : chunkEntry.getValue()) {
                if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                    continue;
                }
                
                BlockEntity entity = world.getBlockEntity(pos);
                if (!(entity instanceof BeaconBlockEntity) || !isBeaconActive((BeaconBlockEntity)entity)) {
                    toRemove.add(pos);
                } else {
                    validCount++;
                }
            }
        }
        
        for (BlockPos pos : toRemove) {
            beaconSpatialIndex.remove(world.getRegistryKey(), pos);
        }
    }
    
    private boolean hasHasteEffect(ServerPlayerEntity player) {
        StatusEffectInstance hasteEffect = player.getStatusEffect(StatusEffects.HASTE);
        return hasteEffect != null && hasteEffect.getAmplifier() >= 1;
    }
    
    private boolean isHoldingNetheriteTool(ItemStack itemStack) {
        return itemStack.isOf(Items.NETHERITE_PICKAXE) ||
               itemStack.isOf(Items.NETHERITE_AXE) ||
               itemStack.isOf(Items.NETHERITE_SHOVEL);
    }
    
    private boolean isHoldingNetheriteTool(ServerPlayerEntity player) {
        return isHoldingNetheriteTool(player.getMainHandStack());
    }
    
    private boolean isBeaconActive(BeaconBlockEntity beacon) {
        return beacon != null && !beacon.getBeamSegments().isEmpty();
    }
    
    private boolean isPlayerInBeaconRange(ServerPlayerEntity player, BeaconBlockEntity beacon) {
        if (beacon == null) return false;
        
        BlockPos beaconPos = beacon.getPos();
        BeaconRange range = beaconRangeCache.computeIfAbsent(beaconPos, pos -> new BeaconRange(pos, 51));
        
        return range.contains(player.getBlockPos());
    }

    private static class BeaconCacheData {
        Map<String, WorldBeaconData> worlds = new HashMap<>();
    }

    private static class WorldBeaconData {
        List<BeaconPos> beacons = new ArrayList<>();
    }

    private static class BeaconPos {
        int x;
        int y;
        int z;
        
        BeaconPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        BlockPos toBlockPos() {
            return new BlockPos(x, y, z).toImmutable();
        }
    }

    private static class BeaconSpatialIndex {
        private final Map<RegistryKey<World>, Map<ChunkPos, Set<BlockPos>>> chunkToBeaconsMap = new ConcurrentHashMap<>();

        public void add(RegistryKey<World> world, BlockPos beacon) {
            ChunkPos chunkPos = new ChunkPos(beacon);
            chunkToBeaconsMap
                .computeIfAbsent(world, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkPos, k -> ConcurrentHashMap.<BlockPos>newKeySet())
                .add(beacon.toImmutable());
        }

        public void remove(RegistryKey<World> world, BlockPos beacon) {
            Map<ChunkPos, Set<BlockPos>> worldMap = chunkToBeaconsMap.get(world);
            if (worldMap == null) return;

            ChunkPos chunkPos = new ChunkPos(beacon);
            Set<BlockPos> chunkBeacons = worldMap.get(chunkPos);
            if (chunkBeacons != null) {
                chunkBeacons.remove(beacon);
                if (chunkBeacons.isEmpty()) {
                    worldMap.remove(chunkPos);
                }
            }
        }

        public Set<BlockPos> getNearbyBeacons(RegistryKey<World> world, BlockPos pos, int chunkRadius) {
            Map<ChunkPos, Set<BlockPos>> worldMap = chunkToBeaconsMap.get(world);
            if (worldMap == null) return Collections.emptySet();

            // Special­‐case: petición de “todos” los beacons
            if (chunkRadius == Integer.MAX_VALUE) {
                Set<BlockPos> all = new HashSet<>();
                for (Set<BlockPos> chunkBeacons : worldMap.values()) {
                    all.addAll(chunkBeacons);
                }
                return all;
            }

            Set<BlockPos> result = new HashSet<>();
            ChunkPos center = new ChunkPos(pos);
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    ChunkPos check = new ChunkPos(center.x + dx, center.z + dz);
                    Set<BlockPos> chunkBeacons = worldMap.get(check);
                    if (chunkBeacons != null) {
                        result.addAll(chunkBeacons);
                    }
                }
            }
            return result;
        }

        public boolean hasAny(RegistryKey<World> world) {
            Map<ChunkPos, Set<BlockPos>> wm = chunkToBeaconsMap.get(world);
            return wm != null && !wm.isEmpty();
        }
    }
}