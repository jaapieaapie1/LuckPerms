/*
 * Copyright (c) 2016 Lucko (Luck) <luck@lucko.me>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.bukkit;

import lombok.Getter;
import me.lucko.luckperms.ApiHandler;
import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.Logger;
import me.lucko.luckperms.api.LuckPermsApi;
import me.lucko.luckperms.api.PlatformType;
import me.lucko.luckperms.api.context.ContextSet;
import me.lucko.luckperms.api.context.MutableContextSet;
import me.lucko.luckperms.bukkit.calculators.AutoOPListener;
import me.lucko.luckperms.bukkit.calculators.DefaultsProvider;
import me.lucko.luckperms.bukkit.vault.VaultHook;
import me.lucko.luckperms.common.LuckPermsPlugin;
import me.lucko.luckperms.common.api.ApiProvider;
import me.lucko.luckperms.common.calculators.CalculatorFactory;
import me.lucko.luckperms.common.commands.ConsecutiveExecutor;
import me.lucko.luckperms.common.commands.Sender;
import me.lucko.luckperms.common.config.LPConfiguration;
import me.lucko.luckperms.common.constants.Permission;
import me.lucko.luckperms.common.contexts.ContextManager;
import me.lucko.luckperms.common.contexts.ServerCalculator;
import me.lucko.luckperms.common.core.UuidCache;
import me.lucko.luckperms.common.data.Importer;
import me.lucko.luckperms.common.groups.GroupManager;
import me.lucko.luckperms.common.messaging.RedisMessaging;
import me.lucko.luckperms.common.runnables.ExpireTemporaryTask;
import me.lucko.luckperms.common.runnables.UpdateTask;
import me.lucko.luckperms.common.storage.Datastore;
import me.lucko.luckperms.common.storage.StorageFactory;
import me.lucko.luckperms.common.tracks.TrackManager;
import me.lucko.luckperms.common.users.UserManager;
import me.lucko.luckperms.common.utils.BufferedRequest;
import me.lucko.luckperms.common.utils.DebugHandler;
import me.lucko.luckperms.common.utils.LocaleManager;
import me.lucko.luckperms.common.utils.LogFactory;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Getter
public class LPBukkitPlugin extends JavaPlugin implements LuckPermsPlugin {
    private VaultHook vaultHook = null;

    private final Set<UUID> ignoringLogs = ConcurrentHashMap.newKeySet();
    private LPConfiguration configuration;
    private UserManager userManager;
    private GroupManager groupManager;
    private TrackManager trackManager;
    private Datastore datastore;
    private RedisMessaging redisMessaging = null;
    private UuidCache uuidCache;
    private ApiProvider apiProvider;
    private Logger log;
    private Importer importer;
    private ConsecutiveExecutor consecutiveExecutor;
    private DefaultsProvider defaultsProvider;
    private LocaleManager localeManager;
    private ContextManager<Player> contextManager;
    private WorldCalculator worldCalculator;
    private CalculatorFactory calculatorFactory;
    private BufferedRequest<Void> updateTaskBuffer;
    private boolean started = false;
    private DebugHandler debugHandler;

    @Override
    public void onEnable() {
        log = LogFactory.wrap(getLogger());
        debugHandler = new DebugHandler();

        getLog().info("Loading configuration...");
        configuration = new BukkitConfig(this);

        // setup the Bukkit defaults hook
        defaultsProvider = new DefaultsProvider();
        // give all plugins a chance to load their defaults, then refresh.
        getServer().getScheduler().runTaskLater(this, () -> defaultsProvider.refresh(), 1L);

        // register events
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new BukkitListener(this), this);

        // initialise datastore
        datastore = StorageFactory.getDatastore(this, "h2");

        // initialise redis
        if (getConfiguration().isRedisEnabled()) {
            getLog().info("Loading redis...");
            redisMessaging = new RedisMessaging(this);
            try {
                redisMessaging.init(getConfiguration().getRedisAddress(), getConfiguration().getRedisPassword());
                getLog().info("Loaded redis successfully...");
            } catch (Exception e) {
                getLog().info("Couldn't load redis...");
                e.printStackTrace();
            }
        }

        // setup the update task buffer
        updateTaskBuffer = new BufferedRequest<Void>(1000L, this::doAsync) {
            @Override
            protected Void perform() {
                doAsync(new UpdateTask(LPBukkitPlugin.this));
                return null;
            }
        };

        // load locale
        localeManager = new LocaleManager();
        File locale = new File(getDataFolder(), "lang.yml");
        if (locale.exists()) {
            getLog().info("Found locale file. Attempting to load from it.");
            try {
                localeManager.loadFromFile(locale);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // register commands
        getLog().info("Registering commands...");
        BukkitCommand commandManager = new BukkitCommand(this);
        PluginCommand main = getServer().getPluginCommand("luckperms");
        main.setExecutor(commandManager);
        main.setTabCompleter(commandManager);
        main.setAliases(Arrays.asList("perms", "lp", "permissions", "p", "perm"));

        // load internal managers
        getLog().info("Loading internal permission managers...");
        uuidCache = new UuidCache(getConfiguration().isOnlineMode());
        userManager = new UserManager(this);
        groupManager = new GroupManager(this);
        trackManager = new TrackManager();
        importer = new Importer(commandManager);
        consecutiveExecutor = new ConsecutiveExecutor(commandManager);
        calculatorFactory = new BukkitCalculatorFactory(this);

        contextManager = new ContextManager<>();
        worldCalculator = new WorldCalculator(this);
        pm.registerEvents(worldCalculator, this);
        contextManager.registerCalculator(worldCalculator);
        contextManager.registerCalculator(new ServerCalculator<>(getConfiguration().getServer()));

        // handle server operators
        if (getConfiguration().isAutoOp()) {
            contextManager.registerListener(new AutoOPListener());
        }

        // Provide vault support
        getLog().info("Attempting to hook into Vault...");
        try {
            if (getServer().getPluginManager().isPluginEnabled("Vault")) {
                vaultHook = new VaultHook();
                vaultHook.hook(this);
                getLog().info("Registered Vault permission & chat hook.");
            } else {
                getLog().info("Vault not found.");
            }
        } catch (Exception e) {
            getLog().severe("Error occurred whilst hooking into Vault.");
            e.printStackTrace();
        }

        // register with the LP API
        getLog().info("Registering API...");
        apiProvider = new ApiProvider(this);
        ApiHandler.registerProvider(apiProvider);
        getServer().getServicesManager().register(LuckPermsApi.class, apiProvider, this, ServicePriority.Normal);


        // schedule update tasks
        int mins = getConfiguration().getSyncTime();
        if (mins > 0) {
            long ticks = mins * 60 * 20;
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> updateTaskBuffer.request(), 20L, ticks);
        } else {
            // Update online users
            updateTaskBuffer.request();
        }

        // register tasks
        getServer().getScheduler().runTaskTimer(this, BukkitSenderFactory.get(this), 1L, 1L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, new ExpireTemporaryTask(this), 60L, 60L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, consecutiveExecutor, 20L, 20L);

        // register permissions
        registerPermissions(getConfiguration().isCommandsAllowOp() ? PermissionDefault.OP : PermissionDefault.FALSE);
        if (!getConfiguration().isOpsEnabled()) {
            getServer().getOperators().forEach(o -> o.setOp(false));
        }

        started = true;
        getLog().info("Successfully loaded.");
    }

    @Override
    public void onDisable() {
        started = false;
        getLog().info("Closing datastore...");
        datastore.shutdown();

        if (redisMessaging != null) {
            getLog().info("Closing redis...");
            redisMessaging.shutdown();
        }

        getLog().info("Unregistering API...");
        ApiHandler.unregisterProvider();
        getServer().getServicesManager().unregisterAll(this);

        if (vaultHook != null) {
            vaultHook.unhook(this);
        }
    }

    @Override
    public void doAsync(Runnable r) {
        getServer().getScheduler().runTaskAsynchronously(this, r);
    }

    @Override
    public void doSync(Runnable r) {
        getServer().getScheduler().runTask(this, r);
    }

    @Override
    public void doAsyncRepeating(Runnable r, long interval) {
        getServer().getScheduler().runTaskTimerAsynchronously(this, r, interval, interval);
    }

    @Override
    public String getVersion() {
        return getDescription().getVersion();
    }

    @Override
    public PlatformType getType() {
        return PlatformType.BUKKIT;
    }

    @Override
    public File getMainDir() {
        return getDataFolder();
    }

    @Override
    public int getPlayerCount() {
        return getServer().getOnlinePlayers().size();
    }

    @Override
    public List<String> getPlayerList() {
        return getServer().getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    @Override
    public Set<UUID> getOnlinePlayers() {
        return getServer().getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet());
    }

    @Override
    public boolean isOnline(UUID external) {
        return getServer().getPlayer(external) != null;
    }

    @Override
    public List<Sender> getNotifyListeners() {
        return getServer().getOnlinePlayers().stream()
                .map(p -> BukkitSenderFactory.get(this).wrap(p, Collections.singleton(Permission.LOG_NOTIFY)))
                .filter(Permission.LOG_NOTIFY::isAuthorized)
                .collect(Collectors.toList());
    }

    @Override
    public Sender getConsoleSender() {
        return BukkitSenderFactory.get(this).wrap(getServer().getConsoleSender());
    }

    @Override
    public Set<Contexts> getPreProcessContexts(boolean op) {
        Set<ContextSet> c = new HashSet<>();
        c.add(ContextSet.empty());
        c.add(ContextSet.singleton("server", getConfiguration().getServer()));

        // Pre process all worlds
        c.addAll(getServer().getWorlds().stream()
                .map(World::getName)
                .map(s -> {
                    MutableContextSet set = new MutableContextSet();
                    set.add("server", getConfiguration().getServer());
                    set.add("world", s);
                    return set.makeImmutable();
                })
                .collect(Collectors.toList())
        );

        // Pre process the separate Vault server, if any
        if (!getConfiguration().getServer().equals(getConfiguration().getVaultServer())) {
            c.add(ContextSet.singleton("server", getConfiguration().getVaultServer()));
            c.addAll(getServer().getWorlds().stream()
                    .map(World::getName)
                    .map(s -> {
                        MutableContextSet set = new MutableContextSet();
                        set.add("server", getConfiguration().getVaultServer());
                        set.add("world", s);
                        return set.makeImmutable();
                    })
                    .collect(Collectors.toList())
            );
        }

        Set<Contexts> contexts = new HashSet<>();

        // Convert to full Contexts
        contexts.addAll(c.stream()
                .map(set -> new Contexts(
                        set,
                        getConfiguration().isIncludingGlobalPerms(),
                        getConfiguration().isIncludingGlobalWorldPerms(),
                        true,
                        getConfiguration().isApplyingGlobalGroups(),
                        getConfiguration().isApplyingGlobalWorldGroups(),
                        op
                ))
                .collect(Collectors.toSet())
        );

        // Check for and include varying Vault config options
        try {
            assert getConfiguration().isVaultIncludingGlobal() == getConfiguration().isIncludingGlobalPerms();
            assert getConfiguration().isIncludingGlobalWorldPerms();
            assert getConfiguration().isApplyingGlobalGroups();
            assert getConfiguration().isApplyingGlobalWorldGroups();
        } catch (AssertionError e) {
            contexts.addAll(c.stream()
                    .map(map -> new Contexts(map, getConfiguration().isVaultIncludingGlobal(), true, true, true, true, op))
                    .collect(Collectors.toSet())
            );
        }

        return contexts;
    }

    @Override
    public Object getPlugin(String name) {
        return getServer().getPluginManager().getPlugin(name);
    }

    @Override
    public Object getService(Class clazz) {
        return getServer().getServicesManager().load(clazz);
    }

    @SuppressWarnings("deprecation")
    @Override
    public UUID getUUID(String playerName) {
        try {
            return getServer().getOfflinePlayer(playerName).getUniqueId();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean isPluginLoaded(String name) {
        return getServer().getPluginManager().isPluginEnabled(name);
    }

    private void registerPermissions(PermissionDefault def) {
        PluginManager pm = getServer().getPluginManager();

        for (Permission p : Permission.values()) {
            for (String node : p.getNodes()) {
                pm.addPermission(new org.bukkit.permissions.Permission(node, def));
            }
        }
    }
}