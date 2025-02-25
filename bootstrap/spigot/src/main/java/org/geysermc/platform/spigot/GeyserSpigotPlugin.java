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

package org.geysermc.platform.spigot;

import com.github.steveice10.mc.protocol.MinecraftConstants;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.common.PlatformType;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.bootstrap.GeyserBootstrap;
import org.geysermc.connector.command.CommandManager;
import org.geysermc.connector.common.AuthType;
import org.geysermc.connector.configuration.GeyserConfiguration;
import org.geysermc.connector.dump.BootstrapDumpInfo;
import org.geysermc.connector.network.translators.world.WorldManager;
import org.geysermc.connector.ping.GeyserLegacyPingPassthrough;
import org.geysermc.connector.ping.IGeyserPingPassthrough;
import org.geysermc.connector.utils.Constants;
import org.geysermc.connector.utils.FileUtils;
import org.geysermc.connector.utils.LanguageUtils;
import org.geysermc.geyser.adapters.spigot.SpigotAdapters;
import org.geysermc.platform.spigot.command.GeyserSpigotCommandExecutor;
import org.geysermc.platform.spigot.command.GeyserSpigotCommandManager;
import org.geysermc.platform.spigot.command.SpigotCommandSender;
import org.geysermc.platform.spigot.world.GeyserPistonListener;
import org.geysermc.platform.spigot.world.GeyserSpigot1_11CraftingListener;
import org.geysermc.platform.spigot.world.GeyserSpigotBlockPlaceListener;
import org.geysermc.platform.spigot.world.manager.*;

import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class GeyserSpigotPlugin extends JavaPlugin implements GeyserBootstrap {
    private GeyserSpigotCommandManager geyserCommandManager;
    private GeyserSpigotConfiguration geyserConfig;
    private GeyserSpigotInjector geyserInjector;
    private GeyserSpigotLogger geyserLogger;
    private IGeyserPingPassthrough geyserSpigotPingPassthrough;
    private GeyserSpigotWorldManager geyserWorldManager;

    private GeyserConnector connector;

    /**
     * The Minecraft server version, formatted as <code>1.#.#</code>
     */
    private String minecraftVersion;

    @Override
    public void onEnable() {
        // This is manually done instead of using Bukkit methods to save the config because otherwise comments get removed
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdir();
            }
            File configFile = FileUtils.fileOrCopiedFromResource(new File(getDataFolder(), "config.yml"), "config.yml", (x) -> x.replaceAll("generateduuid", UUID.randomUUID().toString()));
            this.geyserConfig = FileUtils.loadConfig(configFile, GeyserSpigotConfiguration.class);
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, LanguageUtils.getLocaleStringLog("geyser.config.failed"), ex);
            ex.printStackTrace();
        }

        try {
            // Required for the Cloudburst Network dependency to initialize.
            Class.forName("io.netty.channel.kqueue.KQueue");
        } catch (ClassNotFoundException e) {
            // While we could support these older versions, the downside is not having KQueue working at all
            // And since there are alternative ways to get Geyser working for these aging platforms, it's not worth it.
            getLogger().severe("*********************************************");
            getLogger().severe("");
            getLogger().severe(LanguageUtils.getLocaleStringLog("geyser.bootstrap.unsupported_server.header"));
            getLogger().severe(LanguageUtils.getLocaleStringLog("geyser.bootstrap.unsupported_server.message", "1.12.2"));
            getLogger().severe("");
            getLogger().severe("*********************************************");

            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // By default this should be localhost but may need to be changed in some circumstances
        if (this.geyserConfig.getRemote().getAddress().equalsIgnoreCase("auto")) {
            geyserConfig.setAutoconfiguredRemote(true);
            // Don't use localhost if not listening on all interfaces
            if (!Bukkit.getIp().equals("0.0.0.0") && !Bukkit.getIp().equals("")) {
                geyserConfig.getRemote().setAddress(Bukkit.getIp());
            }
            geyserConfig.getRemote().setPort(Bukkit.getPort());
        }

        if (geyserConfig.getBedrock().isCloneRemotePort()) {
            geyserConfig.getBedrock().setPort(Bukkit.getPort());
        }

        this.geyserLogger = new GeyserSpigotLogger(getLogger(), geyserConfig.isDebugMode());
        GeyserConfiguration.checkGeyserConfiguration(geyserConfig, geyserLogger);

        // Remove this in like a year
        if (Bukkit.getPluginManager().getPlugin("floodgate-bukkit") != null) {
            geyserLogger.severe(LanguageUtils.getLocaleStringLog("geyser.bootstrap.floodgate.outdated", Constants.FLOODGATE_DOWNLOAD_LOCATION));
            this.getPluginLoader().disablePlugin(this);
            return;
        }

        if (geyserConfig.getRemote().getAuthType() == AuthType.FLOODGATE && Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            geyserLogger.severe(LanguageUtils.getLocaleStringLog("geyser.bootstrap.floodgate.not_installed") + " " + LanguageUtils.getLocaleStringLog("geyser.bootstrap.floodgate.disabling"));
            this.getPluginLoader().disablePlugin(this);
            return;
        } else if (geyserConfig.isAutoconfiguredRemote() && Bukkit.getPluginManager().getPlugin("floodgate") != null) {
            // Floodgate installed means that the user wants Floodgate authentication
            geyserLogger.debug("Auto-setting to Floodgate authentication.");
            geyserConfig.getRemote().setAuthType(AuthType.FLOODGATE);
        }

        geyserConfig.loadFloodgate(this);

        // Turn "(MC: 1.16.4)" into 1.16.4.
        this.minecraftVersion = Bukkit.getServer().getVersion().split("\\(MC: ")[1].split("\\)")[0];

        this.connector = GeyserConnector.start(PlatformType.SPIGOT, this);

        if (geyserConfig.isLegacyPingPassthrough()) {
            this.geyserSpigotPingPassthrough = GeyserLegacyPingPassthrough.init(connector);
        } else {
            this.geyserSpigotPingPassthrough = new GeyserSpigotPingPassthrough(geyserLogger);
        }

        this.geyserCommandManager = new GeyserSpigotCommandManager(connector);

        boolean isViaVersion = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
        if (isViaVersion) {
            try {
                // Ensure that we have the latest 4.0.0 changes and not an older ViaVersion version
                Class.forName("com.viaversion.viaversion.api.ViaManager");
            } catch (ClassNotFoundException e) {
                geyserLogger.warning(LanguageUtils.getLocaleStringLog("geyser.bootstrap.viaversion.too_old",
                        "https://ci.viaversion.com/job/ViaVersion/"));
                isViaVersion = false;
                if (this.geyserConfig.isDebugMode()) {
                    e.printStackTrace();
                }
            }
        }
        // Used to determine if Block.getBlockData() is present.
        boolean isLegacy = !isCompatible(Bukkit.getServer().getVersion(), "1.13.0");
        if (isLegacy)
            geyserLogger.debug("Legacy version of Minecraft (1.12.2 or older) detected; falling back to ViaVersion for block state retrieval.");

        boolean isPre1_12 = !isCompatible(Bukkit.getServer().getVersion(), "1.12.0");
        // Set if we need to use a different method for getting a player's locale
        SpigotCommandSender.setUseLegacyLocaleMethod(isPre1_12);

        // We want to do this late in the server startup process to allow plugins such as ViaVersion and ProtocolLib
        // To do their job injecting, then connect into *that*
        this.geyserInjector = new GeyserSpigotInjector(isViaVersion);
        this.geyserInjector.initializeLocalChannel(this);

        if (Boolean.parseBoolean(System.getProperty("Geyser.UseDirectAdapters", "true"))) {
            try {
                String name = Bukkit.getServer().getClass().getPackage().getName();
                String nmsVersion = name.substring(name.lastIndexOf('.') + 1);
                SpigotAdapters.registerWorldAdapter(nmsVersion);
                if (isViaVersion && isViaVersionNeeded()) {
                    if (isLegacy) {
                        // Pre-1.13
                        this.geyserWorldManager = new GeyserSpigot1_12NativeWorldManager(this);
                    } else {
                        // Post-1.13
                        this.geyserWorldManager = new GeyserSpigotLegacyNativeWorldManager(this);
                    }
                } else {
                    // No ViaVersion
                    this.geyserWorldManager = new GeyserSpigotNativeWorldManager(this);
                }
                geyserLogger.debug("Using NMS adapter: " + this.geyserWorldManager.getClass() + ", " + nmsVersion);
            } catch (Exception e) {
                if (geyserConfig.isDebugMode()) {
                    geyserLogger.debug("Error while attempting to find NMS adapter. Most likely, this can be safely ignored. :)");
                    e.printStackTrace();
                }
            }
        } else {
            geyserLogger.debug("Not using NMS adapter as it is disabled via system property.");
        }
        if (this.geyserWorldManager == null) {
            // No NMS adapter
            if (isLegacy && isViaVersion) {
                // Use ViaVersion for converting pre-1.13 block states
                this.geyserWorldManager = new GeyserSpigot1_12WorldManager(this);
            } else if (isLegacy) {
                // Not sure how this happens - without ViaVersion, we don't know any block states, so just assume everything is air
                this.geyserWorldManager = new GeyserSpigotFallbackWorldManager(this);
            } else {
                // Post-1.13
                this.geyserWorldManager = new GeyserSpigotWorldManager(this);
            }
            geyserLogger.debug("Using default world manager: " + this.geyserWorldManager.getClass());
        }
        GeyserSpigotBlockPlaceListener blockPlaceListener = new GeyserSpigotBlockPlaceListener(connector, this.geyserWorldManager);
        Bukkit.getServer().getPluginManager().registerEvents(blockPlaceListener, this);

        Bukkit.getServer().getPluginManager().registerEvents(new GeyserPistonListener(connector, this.geyserWorldManager), this);

        if (isPre1_12) {
            // Register events needed to send all recipes to the client
            Bukkit.getServer().getPluginManager().registerEvents(new GeyserSpigot1_11CraftingListener(connector), this);
        }

        this.getCommand("geyser").setExecutor(new GeyserSpigotCommandExecutor(connector));
    }

    @Override
    public void onDisable() {
        if (connector != null) {
            connector.shutdown();
        }
        if (geyserInjector != null) {
            geyserInjector.shutdown();
        }
    }

    @Override
    public GeyserSpigotConfiguration getGeyserConfig() {
        return geyserConfig;
    }

    @Override
    public GeyserSpigotLogger getGeyserLogger() {
        return geyserLogger;
    }

    @Override
    public CommandManager getGeyserCommandManager() {
        return this.geyserCommandManager;
    }

    @Override
    public IGeyserPingPassthrough getGeyserPingPassthrough() {
        return geyserSpigotPingPassthrough;
    }

    @Override
    public WorldManager getWorldManager() {
        return this.geyserWorldManager;
    }

    @Override
    public Path getConfigFolder() {
        return getDataFolder().toPath();
    }

    @Override
    public BootstrapDumpInfo getDumpInfo() {
        return new GeyserSpigotDumpInfo();
    }

    @Override
    public String getMinecraftServerVersion() {
        return this.minecraftVersion;
    }

    @Override
    public SocketAddress getSocketAddress() {
        return this.geyserInjector.getServerSocketAddress();
    }

    public boolean isCompatible(String version, String whichVersion) {
        int[] currentVersion = parseVersion(version);
        int[] otherVersion = parseVersion(whichVersion);
        int length = Math.max(currentVersion.length, otherVersion.length);
        for (int index = 0; index < length; index = index + 1) {
            int self = (index < currentVersion.length) ? currentVersion[index] : 0;
            int other = (index < otherVersion.length) ? otherVersion[index] : 0;

            if (self != other) {
                return (self - other) > 0;
            }
        }
        return true;
    }

    private int[] parseVersion(String versionParam) {
        versionParam = (versionParam == null) ? "" : versionParam;
        if (versionParam.contains("(MC: ")) {
            versionParam = versionParam.split("\\(MC: ")[1];
            versionParam = versionParam.split("\\)")[0];
        }
        String[] stringArray = versionParam.split("[_.-]");
        int[] temp = new int[stringArray.length];
        for (int index = 0; index <= (stringArray.length - 1); index = index + 1) {
            String t = stringArray[index].replaceAll("\\D", "");
            try {
                temp[index] = Integer.parseInt(t);
            } catch (NumberFormatException ex) {
                temp[index] = 0;
            }
        }
        return temp;
    }

    /**
     * @return the server version before ViaVersion finishes initializing
     */
    public ProtocolVersion getServerProtocolVersion() {
        return ProtocolVersion.getClosest(this.minecraftVersion);
    }

    /**
     * This function should not run unless ViaVersion is installed on the server.
     *
     * @return true if there is any block mappings difference between the server and client.
     */
    private boolean isViaVersionNeeded() {
        ProtocolVersion serverVersion = getServerProtocolVersion();
        List<ProtocolPathEntry> protocolList = Via.getManager().getProtocolManager().getProtocolPath(MinecraftConstants.PROTOCOL_VERSION,
                serverVersion.getVersion());
        if (protocolList == null) {
            // No translation needed!
            return false;
        }
        for (int i = protocolList.size() - 1; i >= 0; i--) {
            MappingData mappingData = protocolList.get(i).getProtocol().getMappingData();
            if (mappingData != null) {
                return true;
            }
        }
        // All mapping data is null, which means client and server block states are the same
        return false;
    }
}
