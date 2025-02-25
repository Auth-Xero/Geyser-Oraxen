/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.registry.populator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.nbt.NbtMapBuilder;
import com.nukkitx.nbt.NbtType;
import com.nukkitx.nbt.NbtUtils;
import com.nukkitx.protocol.bedrock.data.BlockPropertyData;
import com.nukkitx.protocol.bedrock.data.SoundEvent;
import com.nukkitx.protocol.bedrock.data.inventory.ComponentItemData;
import com.nukkitx.protocol.bedrock.data.inventory.ItemData;
import com.nukkitx.protocol.bedrock.packet.StartGamePacket;
import com.nukkitx.protocol.bedrock.v448.Bedrock_v448;
import com.nukkitx.protocol.bedrock.v465.Bedrock_v465;
import com.nukkitx.protocol.bedrock.v471.Bedrock_v471;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.*;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.network.translators.item.StoredItemMappings;
import org.geysermc.connector.registry.BlockRegistries;
import org.geysermc.connector.registry.Registries;
import org.geysermc.connector.registry.type.*;
import org.geysermc.connector.utils.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Populates the item registries.
 */
public class ItemRegistryPopulator {
    public static ArrayList<String> itemMappings = new ArrayList<>();
    private static final Map<String, PaletteVersion> PALETTE_VERSIONS;
    static {
        PALETTE_VERSIONS = new Object2ObjectOpenHashMap<>();
        if (GeyserConnector.getInstance().getConfig().isExtendedWorldHeight()) {
            PALETTE_VERSIONS.put("1_17_10.caves_and_cliffs", new PaletteVersion(Bedrock_v448.V448_CODEC.getProtocolVersion(), Collections.emptyMap()));
        } else {
            PALETTE_VERSIONS.put("1_17_10", new PaletteVersion(Bedrock_v448.V448_CODEC.getProtocolVersion(), Collections.emptyMap()));
        }

        PALETTE_VERSIONS.put("1_17_30", new PaletteVersion(Bedrock_v465.V465_CODEC.getProtocolVersion(), Collections.emptyMap()));
        PALETTE_VERSIONS.put("1_17_40", new PaletteVersion(Bedrock_v471.V471_CODEC.getProtocolVersion(), Collections.emptyMap()));
    }

    private record PaletteVersion(int protocolVersion, Map<String, String> additionalTranslatedItems) {
    }

    public static Map<String, Integer> customIDs;

    public static void populate() {
        // Load item mappings from Java Edition to Bedrock Edition
        InputStream stream = FileUtils.getResource("mappings/items.json");

        TypeReference<Map<String, GeyserMappingItem>> mappingItemsType = new TypeReference<>() {
        };

        Map<String, GeyserMappingItem> items;
        try {
            items = GeyserConnector.JSON_MAPPER.readValue(stream, mappingItemsType);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java runtime item IDs", e);
        }

        /* Load item palette */
        for (Map.Entry<String, PaletteVersion> palette : PALETTE_VERSIONS.entrySet()) {
            stream = FileUtils.getResource(String.format("bedrock/runtime_item_states.%s.json", palette.getKey()));

            TypeReference<List<PaletteItem>> paletteEntriesType = new TypeReference<>() {
            };

            // Used to get the Bedrock namespaced ID (in instances where there are small differences)
            Object2IntMap<String> bedrockIdentifierToId = new Object2IntOpenHashMap<>();
            bedrockIdentifierToId.defaultReturnValue(Short.MIN_VALUE);

            List<String> itemNames = new ArrayList<>();

            List<PaletteItem> itemEntries;
            try {
                itemEntries = GeyserConnector.JSON_MAPPER.readValue(stream, paletteEntriesType);
            } catch (Exception e) {
                throw new AssertionError("Unable to load Bedrock runtime item IDs", e);
            }

            Map<String, StartGamePacket.ItemEntry> entries = new Object2ObjectOpenHashMap<>();

            for (PaletteItem entry : itemEntries) {
                entries.put(entry.getName(), new StartGamePacket.ItemEntry(entry.getName(), (short) entry.getId()));
                bedrockIdentifierToId.put(entry.getName(), entry.getId());
            }

            Object2IntMap<String> bedrockBlockIdOverrides = new Object2IntOpenHashMap<>();
            Object2IntMap<String> blacklistedIdentifiers = new Object2IntOpenHashMap<>();

            // Load creative items
            // We load this before item mappings to get overridden block runtime ID mappings
            stream = FileUtils.getResource(String.format("bedrock/creative_items.%s.json", palette.getKey()));

            JsonNode creativeItemEntries;
            try {
                creativeItemEntries = GeyserConnector.JSON_MAPPER.readTree(stream).get("items");
            } catch (Exception e) {
                throw new AssertionError("Unable to load creative items", e);
            }

            IntList boats = new IntArrayList();
            IntList buckets = new IntArrayList();
            IntList spawnEggs = new IntArrayList();
            List<ItemData> carpets = new ObjectArrayList<>();

            Int2ObjectMap<ItemMapping> mappings = new Int2ObjectOpenHashMap<>();
            // Temporary mapping to create stored items
            Map<String, ItemMapping> identifierToMapping = new Object2ObjectOpenHashMap<>();

            int netId = 1;
            List<ItemData> creativeItems = new ArrayList<>();
            for (JsonNode itemNode : creativeItemEntries) {
                int count = 1;
                int damage = 0;
                int blockRuntimeId = 0;
                NbtMap tag = null;
                JsonNode damageNode = itemNode.get("damage");
                if (damageNode != null) {
                    damage = damageNode.asInt();
                }
                JsonNode countNode = itemNode.get("count");
                if (countNode != null) {
                    count = countNode.asInt();
                }
                JsonNode blockRuntimeIdNode = itemNode.get("blockRuntimeId");
                if (blockRuntimeIdNode != null) {
                    blockRuntimeId = blockRuntimeIdNode.asInt();
                }
                JsonNode nbtNode = itemNode.get("nbt_b64");
                if (nbtNode != null) {
                    byte[] bytes = Base64.getDecoder().decode(nbtNode.asText());
                    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                    try {
                        tag = (NbtMap) NbtUtils.createReaderLE(bais).readTag();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                String identifier = itemNode.get("id").textValue();
                if (identifier.equals("minecraft:sculk_sensor") && !GeyserConnector.getInstance().getConfig().isExtendedWorldHeight()) {
                    // https://github.com/GeyserMC/Geyser/issues/2564
                    continue;
                }
                StartGamePacket.ItemEntry entry = entries.get(identifier);
                int id = -1;
                if (entry != null) {
                    id = entry.getId();
                }

                if (id == -1) {
                    throw new RuntimeException("Unable to find matching Bedrock item for " + identifier);
                }

                creativeItems.add(ItemData.builder()
                        .id(id)
                        .damage(damage)
                        .count(count)
                        .blockRuntimeId(blockRuntimeId)
                        .tag(tag)
                        .netId(netId++)
                        .build());

                if (blockRuntimeId != 0) {
                    // Add override for item mapping, unless it already exists... then we know multiple states can exist
                    if (!blacklistedIdentifiers.containsKey(identifier)) {
                        if (bedrockBlockIdOverrides.containsKey(identifier)) {
                            bedrockBlockIdOverrides.removeInt(identifier);
                            // Save this as a blacklist, but also as knowledge of what the block state name should be
                            blacklistedIdentifiers.put(identifier, blockRuntimeId);
                        } else {
                            // Unless there's multiple possibilities for this one state, let this be
                            bedrockBlockIdOverrides.put(identifier, blockRuntimeId);
                        }
                    }
                }
            }

            BlockMappings blockMappings = BlockRegistries.BLOCKS.forVersion(palette.getValue().protocolVersion());

            int itemIndex = 0;
            int javaFurnaceMinecartId = 0;
            boolean usingFurnaceMinecart = GeyserConnector.getInstance().getConfig().isAddNonBedrockItems();

            Set<String> javaOnlyItems = new ObjectOpenHashSet<>();
            Collections.addAll(javaOnlyItems, "minecraft:spectral_arrow", "minecraft:debug_stick",
                    "minecraft:knowledge_book", "minecraft:tipped_arrow", "minecraft:trader_llama_spawn_egg",
                    "minecraft:bundle");
            if (!usingFurnaceMinecart) {
                javaOnlyItems.add("minecraft:furnace_minecart");
            }
            if (!GeyserConnector.getInstance().getConfig().isExtendedWorldHeight()) {
                javaOnlyItems.add("minecraft:sculk_sensor");
            }
            // Java-only items for this version
            javaOnlyItems.addAll(palette.getValue().additionalTranslatedItems().keySet());

            for (Map.Entry<String, GeyserMappingItem> entry : items.entrySet()) {
                String javaIdentifier = entry.getKey().intern();
                GeyserMappingItem mappingItem;
                String replacementItem = palette.getValue().additionalTranslatedItems().get(javaIdentifier);
                if (replacementItem != null) {
                    mappingItem = items.get(replacementItem);
                } else {
                    // This items has a mapping specifically for this version of the game
                    mappingItem = entry.getValue();
                }
                if (javaIdentifier.equals("minecraft:sculk_sensor") && GeyserConnector.getInstance().getConfig().isExtendedWorldHeight()) {
                    mappingItem.setBedrockIdentifier("minecraft:sculk_sensor");
                }

                if (usingFurnaceMinecart && javaIdentifier.equals("minecraft:furnace_minecart")) {
                    javaFurnaceMinecartId = itemIndex;
                    itemIndex++;
                    continue;
                }
                String bedrockIdentifier = mappingItem.getBedrockIdentifier().intern();
                int bedrockId = bedrockIdentifierToId.getInt(bedrockIdentifier);
                if (bedrockId == Short.MIN_VALUE) {
                    throw new RuntimeException("Missing Bedrock ID in mappings: " + bedrockIdentifier);
                }
                int stackSize = mappingItem.getStackSize();

                int bedrockBlockId = -1;
                Integer firstBlockRuntimeId = entry.getValue().getFirstBlockRuntimeId();
                if (firstBlockRuntimeId != null) {
                    int blockIdOverride = bedrockBlockIdOverrides.getOrDefault(bedrockIdentifier, -1);
                    if (blockIdOverride != -1) {
                        // Straight from BDS is our best chance of getting an item that doesn't run into issues
                        bedrockBlockId = blockIdOverride;
                    } else {
                        // Try to get an example block runtime ID from the creative contents packet, for Bedrock identifier obtaining
                        int aValidBedrockBlockId = blacklistedIdentifiers.getOrDefault(bedrockIdentifier, -1);
                        if (aValidBedrockBlockId == -1) {
                            // Fallback
                            bedrockBlockId = blockMappings.getBedrockBlockId(firstBlockRuntimeId);
                        } else {
                            // As of 1.16.220, every item requires a block runtime ID attached to it.
                            // This is mostly for identifying different blocks with the same item ID - wool, slabs, some walls.
                            // However, in order for some visuals and crafting to work, we need to send the first matching block state
                            // as indexed by Bedrock's block palette
                            // There are exceptions! But, ideally, the block ID override should take care of those.
                            NbtMapBuilder requiredBlockStatesBuilder = NbtMap.builder();
                            String correctBedrockIdentifier = blockMappings.getBedrockBlockStates().get(aValidBedrockBlockId).getString("name");
                            boolean firstPass = true;
                            // Block states are all grouped together. In the mappings, we store the first block runtime ID in order,
                            // and the last, if relevant. We then iterate over all those values and get their Bedrock equivalents
                            customIDs = new HashMap<>();

                            Integer lastBlockRuntimeId = entry.getValue().getLastBlockRuntimeId() == null ? firstBlockRuntimeId : entry.getValue().getLastBlockRuntimeId();
                            for (int i = firstBlockRuntimeId; i <= lastBlockRuntimeId; i++) {
                                int bedrockBlockRuntimeId = blockMappings.getBedrockBlockId(i);
                                NbtMap blockTag = blockMappings.getBedrockBlockStates().get(bedrockBlockRuntimeId);
                                String bedrockName = blockTag.getString("name");
                                if (!bedrockName.equals(correctBedrockIdentifier)) {
                                    continue;
                                }
                                NbtMap states = blockTag.getCompound("states");

                                if (firstPass) {
                                    firstPass = false;
                                    if (states.size() == 0) {
                                        // No need to iterate and find all block states - this is the one, as there can't be any others
                                        bedrockBlockId = bedrockBlockRuntimeId;
                                        break;
                                    }
                                    requiredBlockStatesBuilder.putAll(states);
                                    continue;
                                }
                                for (Map.Entry<String, Object> nbtEntry : states.entrySet()) {
                                    Object value = requiredBlockStatesBuilder.get(nbtEntry.getKey());
                                    if (value != null && !nbtEntry.getValue().equals(value)) { // Null means this value has already been removed/deemed as unneeded
                                        // This state can change between different block states, and therefore is not required
                                        // to build a successful block state of this
                                        requiredBlockStatesBuilder.remove(nbtEntry.getKey());
                                    }
                                }
                                if (requiredBlockStatesBuilder.size() == 0) {
                                    // There are no required block states
                                    // E.G. there was only a direction property that is no longer in play
                                    // (States that are important include color for glass)
                                    break;
                                }
                            }

                            NbtMap requiredBlockStates = requiredBlockStatesBuilder.build();
                            if (bedrockBlockId == -1) {
                                int i = -1;
                                // We need to loop around again (we can't cache the block tags above) because Bedrock can include states that we don't have a pairing for
                                // in it's "preferred" block state - I.E. the first matching block state in the list
                                for (NbtMap blockTag : blockMappings.getBedrockBlockStates()) {
                                    i++;
                                    if (blockTag.getString("name").equals(correctBedrockIdentifier)) {
                                        NbtMap states = blockTag.getCompound("states");
                                        boolean valid = true;
                                        for (Map.Entry<String, Object> nbtEntry : requiredBlockStates.entrySet()) {
                                            if (!states.get(nbtEntry.getKey()).equals(nbtEntry.getValue())) {
                                                // A required block state doesn't match - this one is not valid
                                                valid = false;
                                                break;
                                            }
                                        }
                                        if (valid) {
                                            bedrockBlockId = i;
                                            break;
                                        }
                                    }
                                }
                                if (bedrockBlockId == -1) {
                                    throw new RuntimeException("Could not find a block match for " + entry.getKey());
                                }
                            }

                            // Because we have replaced the Bedrock block ID, we also need to replace the creative contents block runtime ID
                            // That way, creative items work correctly for these blocks
                            for (int j = 0; j < creativeItems.size(); j++) {
                                ItemData itemData = creativeItems.get(j);
                                if (itemData.getId() == bedrockId) {
                                    if (itemData.getDamage() != 0) {
                                        break;
                                    }
                                    NbtMap states = blockMappings.getBedrockBlockStates().get(itemData.getBlockRuntimeId()).getCompound("states");
                                    boolean valid = true;
                                    for (Map.Entry<String, Object> nbtEntry : requiredBlockStates.entrySet()) {
                                        if (!states.get(nbtEntry.getKey()).equals(nbtEntry.getValue())) {
                                            // A required block state doesn't match - this one is not valid
                                            valid = false;
                                            break;
                                        }
                                    }
                                    if (valid) {
                                        creativeItems.set(j, itemData.toBuilder().blockRuntimeId(bedrockBlockId).build());
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }

                ItemMapping.ItemMappingBuilder mappingBuilder = ItemMapping.builder()
                        .javaIdentifier(javaIdentifier)
                        .javaId(itemIndex)
                        .bedrockIdentifier(bedrockIdentifier)
                        .bedrockId(bedrockId)
                        .bedrockData(mappingItem.getBedrockData())
                        .bedrockBlockId(bedrockBlockId)
                        .stackSize(stackSize);

                if (mappingItem.getToolType() != null) {
                    if (mappingItem.getToolTier() != null) {
                        mappingBuilder = mappingBuilder.toolType(mappingItem.getToolType().intern())
                                .toolTier(mappingItem.getToolTier().intern());
                    } else {
                        mappingBuilder = mappingBuilder.toolType(mappingItem.getToolType().intern())
                                .toolTier("");
                    }
                }
                if (javaOnlyItems.contains(javaIdentifier)) {
                    // These items don't exist on Bedrock, so set up a variable that indicates they should have custom names
                    mappingBuilder = mappingBuilder.translationString((bedrockBlockId != -1 ? "block." : "item.") + entry.getKey().replace(":", "."));
                    GeyserConnector.getInstance().getLogger().debug("Adding " + entry.getKey() + " as an item that needs to be translated.");
                }

                ItemMapping mapping = mappingBuilder.build();

                if (javaIdentifier.contains("boat")) {
                    boats.add(bedrockId);
                } else if (javaIdentifier.contains("bucket") && !javaIdentifier.contains("milk")) {
                    buckets.add(bedrockId);
                } else if (javaIdentifier.contains("_carpet") && !javaIdentifier.contains("moss")) {
                    // This should be the numerical order Java sends as an integer value for llamas
                    carpets.add(ItemData.builder()
                            .id(mapping.getBedrockId())
                            .damage(mapping.getBedrockData())
                            .count(1)
                            .blockRuntimeId(mapping.getBedrockBlockId())
                            .build());
                } else if (javaIdentifier.startsWith("minecraft:music_disc_")) {
                    // The Java record level event uses the item ID as the "key" to play the record
                    Registries.RECORDS.register(itemIndex, SoundEvent.valueOf("RECORD_" +
                            javaIdentifier.replace("minecraft:music_disc_", "").toUpperCase(Locale.ENGLISH)));
                } else if (javaIdentifier.endsWith("_spawn_egg")) {
                    spawnEggs.add(mapping.getBedrockId());
                }

                mappings.put(itemIndex, mapping);
                identifierToMapping.put(javaIdentifier, mapping);

                itemNames.add(javaIdentifier);

                itemIndex++;
            }

            itemNames.add("minecraft:furnace_minecart");

            int lodestoneCompassId = entries.get("minecraft:lodestone_compass").getId();
            if (lodestoneCompassId == 0) {
                throw new RuntimeException("Lodestone compass not found in item palette!");
            }

            // Add the lodestone compass since it doesn't exist on java but we need it for item conversion
            ItemMapping lodestoneEntry = ItemMapping.builder()
                    .javaIdentifier("minecraft:lodestone_compass")
                    .bedrockIdentifier("minecraft:lodestone_compass")
                    .javaId(itemIndex)
                    .bedrockId(lodestoneCompassId)
                    .bedrockData(0)
                    .bedrockBlockId(-1)
                    .stackSize(1)
                    .build();
            mappings.put(itemIndex, lodestoneEntry);
            identifierToMapping.put(lodestoneEntry.getJavaIdentifier(), lodestoneEntry);

            ComponentItemData furnaceMinecartData = null;
            List<ComponentItemData> allitemdata = new ArrayList<>();
            if (usingFurnaceMinecart) {
                // Add the furnace minecart as a custom item
                int furnaceMinecartId = mappings.size() + 1;

                entries.put("geysermc:furnace_minecart", new StartGamePacket.ItemEntry("geysermc:furnace_minecart", (short) furnaceMinecartId, true));

                mappings.put(javaFurnaceMinecartId, ItemMapping.builder()
                        .javaIdentifier("geysermc:furnace_minecart")
                        .bedrockIdentifier("geysermc:furnace_minecart")
                        .javaId(javaFurnaceMinecartId)
                        .bedrockId(furnaceMinecartId)
                        .bedrockData(0)
                        .bedrockBlockId(-1)
                        .stackSize(1)
                        .build());

                creativeItems.add(ItemData.builder()
                        .netId(netId)
                        .id(furnaceMinecartId)
                        .count(1).build());

                NbtMapBuilder builder = NbtMap.builder();
                builder.putString("name", "geysermc:furnace_minecart")
                        .putInt("id", furnaceMinecartId);

                NbtMapBuilder itemProperties = NbtMap.builder();

                NbtMapBuilder componentBuilder = NbtMap.builder();
                // Conveniently, as of 1.16.200, the furnace minecart has a texture AND translation string already.
                // 1.17.30 moves the icon to the item properties section
                (palette.getValue().protocolVersion() >= Bedrock_v465.V465_CODEC.getProtocolVersion() ?
                        itemProperties : componentBuilder).putCompound("minecraft:icon", NbtMap.builder()
                        .putString("texture", "minecart_furnace")
                        .putString("frame", "0.000000")
                        .putInt("frame_version", 1)
                        .putString("legacy_id", "").build());
                componentBuilder.putCompound("minecraft:display_name", NbtMap.builder().putString("value", "item.minecartFurnace.name").build());

                // Indicate that the arm animation should play on rails
                List<NbtMap> useOnTag = Collections.singletonList(NbtMap.builder().putString("tags", "q.any_tag('rail')").build());
                componentBuilder.putCompound("minecraft:entity_placer", NbtMap.builder()
                        .putList("dispense_on", NbtType.COMPOUND, useOnTag)
                        .putString("entity", "minecraft:minecart")
                        .putList("use_on", NbtType.COMPOUND, useOnTag)
                        .build());

                // We always want to allow offhand usage when we can - matches Java Edition
                itemProperties.putBoolean("allow_off_hand", true);
                itemProperties.putBoolean("hand_equipped", false);
                itemProperties.putInt("max_stack_size", 1);
                itemProperties.putString("creative_group", "itemGroup.name.minecart");
                itemProperties.putInt("creative_category", 4); // 4 - "Items"

                componentBuilder.putCompound("item_properties", itemProperties.build());
                builder.putCompound("components", componentBuilder.build());
                furnaceMinecartData = new ComponentItemData("geysermc:furnace_minecart", builder.build());
                /*for (JsonNode blockState : BlockRegistryPopulator.blockStatesNode) {
                        if (blockState.has("when")) {
                            JsonNode when = blockState.get("when");
                            if (when.has("instrument") && when.has("note") && when.has("powered")) {
                                String instrument = when.get("instrument").asText();
                                int note = when.get("note").asInt();
                                boolean powered = when.get("powered").asBoolean();
                                // if (javaId.contains("minecraft:note_block[instrument=" + instrument + ",note=" + note + ",powered=" + powered + "]")) {
                                    if (blockState.has("apply")) {
                                        String model = blockState.get("apply").get("model").asText();
                                        int customBlockId = mappings.size() + 1;
                                        entries.put("geysermc:zzz_"+model, new StartGamePacket.ItemEntry("geysermc:zzz_"+model, (short) customBlockId, true));

                                        mappings.put(javaFurnaceMinecartId, ItemMapping.builder()
                                                .javaIdentifier("geysermc:zzz_"+model)
                                                .bedrockIdentifier("geysermc:zzz_"+model)
                                                .javaId(javaFurnaceMinecartId)
                                                .bedrockId(customBlockId)
                                                .bedrockData(0)
                                                .bedrockBlockId(-1)
                                                .stackSize(1)
                                                .build());

                                        creativeItems.add(ItemData.builder()
                                                .netId(netId)
                                                .id(customBlockId)
                                                .count(1).build());

                                        NbtMapBuilder builder1 = NbtMap.builder();
                                        builder1.putString("name","geysermc:zzz_"+model)
                                                .putInt("id", customBlockId);

                                        NbtMapBuilder itemProperties1 = NbtMap.builder();

                                        NbtMapBuilder componentBuilder1 = NbtMap.builder();
                                        // Conveniently, as of 1.16.200, the furnace minecart has a texture AND translation string already.
                                        // 1.17.30 moves the icon to the item properties section
                                        (palette.getValue().protocolVersion() >= Bedrock_v465.V465_CODEC.getProtocolVersion() ?
                                                itemProperties1 : componentBuilder1).putCompound("minecraft:icon", NbtMap.builder().putString("texture","zzz_"+model).build());
                                        componentBuilder1.putCompound("minecraft:display_name", NbtMap.builder().putString("value", "Custom Block"+customBlockId).build());

                                        // Indicate that the arm animation should play on rails

                                        // We always want to allow offhand usage when we can - matches Java Edition
                                        itemProperties1.putBoolean("allow_off_hand", true);
                                        itemProperties1.putBoolean("hand_equipped", false);
                                        itemProperties1.putInt("max_stack_size", 64);

                                        componentBuilder1.putCompound("item_properties", itemProperties1.build());
                                        builder1.putCompound("components", componentBuilder1.build());
                                        ComponentItemData customItemData1 = new ComponentItemData("geysermc:zzz_"+model, builder1.build());
                                        allitemdata.add(customItemData1);
                                        customIDs.put("geysermc:zzz_"+model, customBlockId);
                                    }
                               // }
                            }
                        }
                }*/

                int itemId = mappings.size() + 1;

                for (String sd : itemMappings) {
                    if (sd.contains(";")) {
                        String[] values = sd.split(";");

                        //int customModelData = Integer.parseInt(values[0]);
                        String texture = values[0];
                        boolean isTool = Boolean.parseBoolean(values[1]);
                        boolean is3DItem = Boolean.parseBoolean(values[2]);

                        ComponentItemData customItemData = null;

                        // Add a custom item
                        itemId = itemId + 1;
                        javaFurnaceMinecartId = itemIndex++;

                        entries.put("geysermc:" + texture, new StartGamePacket.ItemEntry("geysermc:" + texture, (short) itemId, true));

                        mappings.put(javaFurnaceMinecartId, ItemMapping.builder().javaIdentifier("geysermc:" + texture).bedrockIdentifier("geysermc:" + texture).javaId(javaFurnaceMinecartId).bedrockId(itemId).bedrockData(0).bedrockBlockId(-1).stackSize(64).build());

                        creativeItems.add(ItemData.builder()
                                .netId(netId)
                                .id(itemId)
                                .count(1).build());

                        NbtMapBuilder custombuilder = NbtMap.builder();
                        custombuilder.putString("name", "geysermc:" + texture)
                                .putInt("id", itemId);

                        NbtMapBuilder customitemProperties = NbtMap.builder();
                        NbtMapBuilder customComponentBuilder = NbtMap.builder();
                        // NbtMapBuilder renderOffsets = NbtMap.builder();
                        // Conveniently, as of 1.16.200, the furnace minecart has a texture AND translation string already.
                        // 1.17.30 moves the icon to the item properties section
                        /*if(!is3DItem) {
                            (palette.getValue().protocolVersion() >= Bedrock_v465.V465_CODEC.getProtocolVersion() ? customitemProperties : customComponentBuilder).putCompound("minecraft:icon", NbtMap.builder().putString("texture", texture).build());
                        }
                        else if (is3DItem){
                            customComponentBuilder.putCompound("minecraft:material_instances", NbtMap.builder().putCompound("materials", NbtMap.builder().putCompound("*", NbtMap.builder().putBoolean("ambient_occlusion", true).putBoolean("face_dimming", true).putString("texture", texture).putString("render_method", "opaque").build()).build()).build());
                        }*/
                        (palette.getValue().protocolVersion() >= Bedrock_v465.V465_CODEC.getProtocolVersion() ? customitemProperties : customComponentBuilder).putCompound("minecraft:icon", NbtMap.builder().putString("texture", texture).build());
                        customComponentBuilder.putCompound("minecraft:display_name", NbtMap.builder().putString("value", "Custom Item" + itemId).build());

                        List<NbtMap> useOnCustomTag = Collections.singletonList(NbtMap.builder().putString("tags", "q.any_tag('rail')").build());


                        // We always want to allow offhand usage when we can - matches Java Edition
                        customitemProperties.putBoolean("allow_off_hand", true);
                        customitemProperties.putBoolean("hand_equipped", isTool);
                        customitemProperties.putInt("max_stack_size", 64);
                        NbtMapBuilder wearable = NbtMap.builder();
                        String type = null;
                        if (texture.contains("_boots")) type = "feet";
                        if (texture.contains("_chestplate")) type = "chest";
                        if (texture.contains("_leggings")) type = "legs";
                        if (texture.contains("_helmet")) type = "head";
                        if (type != null) {
                            wearable.putBoolean("dispensable", true);
                            wearable.putString("slot", "slot.armor." + type);
                            customitemProperties.putCompound("minecraft:wearable", wearable.build());
                        }
                        //very hacky method
                        /*String tool = "tools";
                        if(texture.contains("sword"))type ="diamond_sword";
                        if(texture.contains("hoe"))type ="diamond_hoe";
                        if(texture.contains("pickaxe"))type ="diamond_pickaxe";
                        if(texture.contains("axe"))type ="diamond_axe";
                        if(texture.contains("shovel") || texture.contains("spade") )type ="diamond_shovel";*/
                        /*NbtMapBuilder final1 = NbtMap.builder();
                        NbtMapBuilder first_person = NbtMap.builder();
                        NbtMapBuilder third_person = NbtMap.builder();
                        NbtMapBuilder fp_rotation = NbtMap.builder();
                        fp_rotation.putFloat("x",0.0f);
                        fp_rotation.putFloat("y",0.0f);
                        fp_rotation.putFloat("z",0.0f);
                        NbtMapBuilder fp_position = NbtMap.builder();
                        fp_position.putFloat("x",0.0f);
                        fp_position.putFloat("y",0.0f);
                        fp_position.putFloat("z",0.0f);
                        NbtMapBuilder fp_scale = NbtMap.builder();
                        fp_scale.putFloat("x",0.05f);
                        fp_scale.putFloat("y",0.05f);
                        fp_scale.putFloat("z",0.05f);
                        first_person.putCompound("position",fp_position.build());
                        first_person.putCompound("rotation",fp_rotation.build());
                        first_person.putCompound("scale",fp_scale.build());
                        NbtMapBuilder tp_rotation = NbtMap.builder();
                        tp_rotation.putFloat("x",0.0f);
                        tp_rotation.putFloat("y",0.0f);
                        tp_rotation.putFloat("z",0.0f);
                        NbtMapBuilder tp_position = NbtMap.builder();
                        tp_position.putFloat("x",0.0f);
                        tp_position.putFloat("y",0.0f);
                        tp_position.putFloat("z",0.0f);
                        NbtMapBuilder tp_scale = NbtMap.builder();
                        tp_scale.putFloat("x",0.05f);
                        tp_scale.putFloat("y",0.05f);
                        tp_scale.putFloat("z",0.05f);
                        third_person.putCompound("position",tp_position.build());
                        third_person.putCompound("rotation",tp_rotation.build());
                        third_person.putCompound("scale",tp_scale.build());
                        final1.putCompound("first_person",first_person.build());
                        final1.putCompound("third_person",third_person.build());
                        renderOffsets.putCompound("main_hand",final1.build());
                        renderOffsets.putCompound("off_hand",final1.build());
                        componentBuilder.putCompound("minecraft:render_offsets", renderOffsets.build());*/
                        customComponentBuilder.putCompound("item_properties", customitemProperties.build());
                        custombuilder.putCompound("components", customComponentBuilder.build());
                        customItemData = new ComponentItemData("geysermc:" + texture, custombuilder.build());
                        allitemdata.add(customItemData);
                        customIDs.put(texture, itemId);
                    }
                }

            }

            ItemMappings itemMappings = ItemMappings.builder()
                    .items(mappings)
                    .creativeItems(creativeItems.toArray(new ItemData[0]))
                    .itemEntries(new ArrayList<>(entries.values()))
                    .itemNames(itemNames.toArray(new String[0]))
                    .storedItems(new StoredItemMappings(identifierToMapping))
                    .javaOnlyItems(javaOnlyItems)
                    .bucketIds(buckets)
                    .boatIds(boats)
                    .spawnEggIds(spawnEggs)
                    .carpets(carpets)
                    .furnaceMinecartData(furnaceMinecartData)
                    .customItems(allitemdata)
                    .build();
            Registries.ITEMS.register(palette.getValue().protocolVersion(), itemMappings);
        }

    }
}
