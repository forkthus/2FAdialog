package com.forkthus.twofadialog;

import com.forkthus.twofadialog.config.ConfigManager;
import com.forkthus.twofadialog.qr.QrMap;
import com.forkthus.twofadialog.security.Totp;
import com.forkthus.twofadialog.storage.UserStore;
import com.forkthus.twofadialog.storage.YamlUserStore;
import com.forkthus.twofadialog.ui.Dialogs;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.connection.PlayerGameConnection;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.Location;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TwoFactorPlugin extends JavaPlugin implements Listener {
    private UserStore store;
    private ConfigManager config;
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> askTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> authStartTimes = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> backupInventories = new ConcurrentHashMap<>();
    private final Map<UUID, Float[]> backupLookAngles = new ConcurrentHashMap<>(); // [yaw, pitch]
    private BukkitTask viewLockTask;
    private final NamespacedKey QR_TAG = QrMap.QR_TAG;

    @Override public void onEnable() {
        saveDefaultConfig();
        config = new ConfigManager(this);
        store = new YamlUserStore(getDataFolder());
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // Start view lock task that runs every tick to force frozen players to look down
        viewLockTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID frozenId : frozen) {
                Player p = Bukkit.getPlayer(frozenId);
                if (p != null && p.isOnline()) {
                    Location loc = p.getLocation();
                    // Only lock view if they have a QR map (to avoid locking during initial dialog)
                    ItemStack item = p.getInventory().getItem(0);
                    boolean hasQrMap = item != null && item.getItemMeta() != null && 
                        item.getItemMeta().getPersistentDataContainer().get(QR_TAG, PersistentDataType.BYTE) != null;
                    
                    if (hasQrMap && Math.abs(loc.getPitch() - 90f) > 0.1f) {
                        loc.setPitch(90f); // Force looking straight down
                        p.teleport(loc);
                        // getLogger().info("Forcing " + p.getName() + " to look down - was at pitch " + loc.getPitch());
                    }
                }
            }
        }, 1L, 2L); // Run every 2 ticks for better performance
        
        getLogger().info("TwoFactorDialogs enabled");
    }

    @Override public void onDisable() {
        askTasks.values().forEach(BukkitTask::cancel);
        timeoutTasks.values().forEach(BukkitTask::cancel);
        if (viewLockTask != null) {
            viewLockTask.cancel();
        }
        if (store != null) {
            store.close();
        }
    }

    /* ------------ Entry points ------------ */

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        String currentIP = p.getAddress().getAddress().getHostAddress();
        
        // Check if player is currently banned for failed attempts
        if (isPlayerBanned(id)) {
            long banExpiry = store.getBanExpiry(id);
            long remainingBanTime = (banExpiry - System.currentTimeMillis()) / (60 * 1000); // minutes
            getLogger().warning("Player " + p.getName() + " (" + currentIP + ") tried to join while banned for failed attempts. Ban expires in " + remainingBanTime + " minutes.");
            p.kick(Component.text(config.getWrongCodeBannedError((int)remainingBanTime)));
            return;
        }
        
        // Check if user is enrolled and using the same IP as last successful login
        if (store.hasSecret(id) && store.isEnrolled(id)) {
            String lastIP = store.getLastIP(id);
            long lastLoginTime = store.getLastLoginTime(id);
            long currentTime = System.currentTimeMillis();
            long bypassDurationMs = config.getIpBypassDays() * 24 * 60 * 60 * 1000L;
            
            if (currentIP.equals(lastIP) && (currentTime - lastLoginTime) <= bypassDurationMs) {
                // Same IP as last successful login AND within one week - allow immediate access
                getLogger().info("Player " + p.getName() + " (" + currentIP + ") bypassed 2FA using IP bypass");
                if (!isVanished(p)) {
                    Component joinMsg = Component.text(config.getJoinMessage(p.getName())).color(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
                    Bukkit.getServer().broadcast(joinMsg);
                }
                return; // Skip authentication entirely
            }
        }
        
        // Hide join message until authentication is complete
        e.setJoinMessage(null);
        
        // Teleport to spawn location first, then freeze and authenticate
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            
            // Teleport to server's main spawn location (like /spawn command)
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            
            if (!store.hasSecret(id)) {
                // First-time: create secret now, freeze and show scan prompt
                getLogger().info("New player " + p.getName() + " (" + currentIP + ") starting 2FA registration");
                freeze(p);
                String secret = Totp.newBase32Secret();
                store.setSecret(id, secret);
                startRegistrationTimeout(p);
                showScanPrompt(p);
            } else if (!store.isEnrolled(id)) {
                // Not enrolled yet (never completed first OTP), freeze and show scan prompt
                getLogger().info("Player " + p.getName() + " (" + currentIP + ") resuming 2FA registration");
                freeze(p);
                startRegistrationTimeout(p);
                showScanPrompt(p);
            } else {
                // Returning user: freeze + show login
                getLogger().info("Player " + p.getName() + " (" + currentIP + ") starting 2FA login");
                freeze(p);
                startLoginTimeout(p);
                showLogin(p, null);
            }
        }, 1L); // Delay by 1 tick to ensure player is fully loaded
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        unfreeze(p); // This will restore the inventory automatically
        cancelAsk(p.getUniqueId());
        cancelTimeout(p.getUniqueId());
        // No need to clean QR maps since unfreeze restores original inventory
    }

    /* ------------ UI helpers ------------ */

    private void showScanPrompt(Player p) { 
        int timeLeft = getRemainingTime(p.getUniqueId());
        p.showDialog(Dialogs.scanPrompt(config, timeLeft)); 
    }
    private void scheduleAsk(Player p) {
        cancelAsk(p.getUniqueId());
        askTasks.put(p.getUniqueId(), Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                int timeLeft = getRemainingTime(p.getUniqueId());
                p.showDialog(Dialogs.askFinished(config, timeLeft));
            }
        }, config.getScanAskDelay() * 20L)); // configurable delay in seconds
    }
    private void cancelAsk(UUID id) {
        var t = askTasks.remove(id);
        if (t != null) t.cancel();
    }
    private void showLogin(Player p, String error) { 
        int timeLeft = getRemainingTime(p.getUniqueId());
        p.showDialog(Dialogs.login(config, timeLeft, error)); 
    }
    
    /* ------------ Timeout management ------------ */
    
    private void startRegistrationTimeout(Player p) {
        UUID id = p.getUniqueId();
        authStartTimes.put(id, System.currentTimeMillis());
        int timeoutSeconds = config.getRegistrationTimeout();
        
        timeoutTasks.put(id, Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && frozen.contains(id)) {
                unfreeze(p);
                cancelAsk(id);
                p.kick(Component.text(config.getTimeoutExpiredError()));
            }
        }, timeoutSeconds * 20L));
    }
    
    private void startLoginTimeout(Player p) {
        UUID id = p.getUniqueId();
        authStartTimes.put(id, System.currentTimeMillis());
        int timeoutSeconds = config.getLoginTimeout();
        
        timeoutTasks.put(id, Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && frozen.contains(id)) {
                unfreeze(p);
                p.kick(Component.text(config.getTimeoutExpiredError()));
            }
        }, timeoutSeconds * 20L));
    }
    
    private void cancelTimeout(UUID id) {
        var t = timeoutTasks.remove(id);
        if (t != null) t.cancel();
        authStartTimes.remove(id);
    }
    
    private int getRemainingTime(UUID id) {
        Long startTime = authStartTimes.get(id);
        if (startTime == null) return 0;
        
        boolean isRegistration = !store.hasSecret(id) || !store.isEnrolled(id);
        int totalTimeout = isRegistration ? config.getRegistrationTimeout() : config.getLoginTimeout();
        
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        return Math.max(0, totalTimeout - (int)elapsed);
    }
    
    private boolean isPlayerBanned(UUID id) {
        if (config.getMaxFailedAttempts() <= 0) return false; // Feature disabled
        long banExpiry = store.getBanExpiry(id);
        return banExpiry > System.currentTimeMillis();
    }
    
    private void handleFailedAttempt(Player player) {
        UUID id = player.getUniqueId();
        String currentIP = player.getAddress().getAddress().getHostAddress();
        
        if (config.getMaxFailedAttempts() <= 0) {
            // Failed attempts tracking disabled, just show simple error
            getLogger().warning("Player " + player.getName() + " (" + currentIP + ") failed 2FA attempt");
            showLogin(player, "Wrong code. Please try again.");
            return;
        }
        
        int currentAttempts = store.getFailedAttempts(id) + 1;
        store.setFailedAttempts(id, currentAttempts);
        
        getLogger().warning("Player " + player.getName() + " (" + currentIP + ") failed 2FA attempt (" + currentAttempts + "/" + config.getMaxFailedAttempts() + ")");
        
        if (currentAttempts >= config.getMaxFailedAttempts()) {
            // Ban the player
            long banDuration = config.getFailedAttemptBanMinutes() * 60 * 1000L; // convert to milliseconds
            long banExpiry = System.currentTimeMillis() + banDuration;
            store.setBanExpiry(id, banExpiry);
            
            getLogger().warning("Player " + player.getName() + " (" + currentIP + ") banned for " + config.getFailedAttemptBanMinutes() + " minutes due to too many failed attempts");
            
            unfreeze(player);
            cancelTimeout(id);
            player.kick(Component.text(config.getWrongCodeBannedError(config.getFailedAttemptBanMinutes())));
        } else {
            // Show error with remaining attempts
            int attemptsLeft = config.getMaxFailedAttempts() - currentAttempts;
            showLogin(player, config.getWrongCodeError(attemptsLeft));
        }
    }
    
    private void handleSuccessfulLogin(Player player) {
        UUID id = player.getUniqueId();
        String currentIP = player.getAddress().getAddress().getHostAddress();
        
        // Reset failed attempts on successful login
        store.setFailedAttempts(id, 0);
        store.setBanExpiry(id, 0);
        
        // Log successful authentication
        boolean isRegistration = !store.isEnrolled(id);
        if (isRegistration) {
            getLogger().info("Player " + player.getName() + " (" + currentIP + ") completed 2FA registration successfully");
        } else {
            getLogger().info("Player " + player.getName() + " (" + currentIP + ") completed 2FA login successfully");
        }
    }

    /* ------------ Actions from dialogs ------------ */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDialogClick(PlayerCustomClickEvent event) {
        var key = event.getIdentifier();
        var view = event.getDialogResponseView(); // may be null
        if (key == null) return;

        // Need the Player object; resolve from connection when in-game.
        Player player = null;
        if (event.getCommonConnection() instanceof PlayerGameConnection game) {
            player = game.getPlayer();
        }
        if (player == null) return;

        UUID id = player.getUniqueId();

        if (key.equals(Dialogs.ACTION_QR_GIVE)) {
            // Give QR map (generated from provisioning URI)
            String secret = store.getSecret(id);
            String uri = Totp.provisioningUri(config.getServerName(), player.getName(), secret);
            ItemStack map = QrMap.make(player, uri);
            // Put into first inventory slot (slot 0) - inventory should be empty due to freeze
            player.getInventory().setItem(0, map);
            
            player.sendMessage(Component.text(config.getQrReceivedMessage()));
            
            // Use a delayed task to set the view angle after everything is processed
            final UUID playerId = id;
            final Player finalPlayer = player;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (finalPlayer.isOnline() && frozen.contains(playerId)) {
                    Location loc = finalPlayer.getLocation();
                    loc.setPitch(90f); // Look straight down
                    finalPlayer.teleport(loc);
                    getLogger().info("Set " + finalPlayer.getName() + " to look down at pitch " + loc.getPitch());
                }
            }, 3L); // 3 tick delay
            
            scheduleAsk(player);
            return;
        }

        if (key.equals(Dialogs.ACTION_QR_EXIT)) {
            // Player chose to leave during QR setup
            unfreeze(player); // Restore inventory before kicking
            cancelAsk(id);
            cancelTimeout(id);
            player.kick(Component.text(config.getLeaveKickMessage()));
            return;
        }

        if (key.equals(Dialogs.ACTION_ASK_DONE)) {
            cancelAsk(id);
            // Next: OTP + rules
            freeze(player); // start freezing now so they can't move while logging in
            showLogin(player, null);
            return;
        }

        if (key.equals(Dialogs.ACTION_ASK_NOTY)) {
            // Just ask again in 10s
            scheduleAsk(player);
            return;
        }

        if (key.equals(Dialogs.ACTION_LOGIN_SUB)) {
            DialogResponseView rv = view;
            if (rv == null) { showLogin(player, config.getNoInputError()); return; }

            String otp = rv.getText("otp");
            Boolean rules = rv.getBoolean("rules");
            String secret = store.getSecret(id);

            if (rules == null || !rules) {
                showLogin(player, config.getMustAgreeRulesError());
                return;
            }
            if (!Totp.verify(secret, otp, config.getTimeWindow())) {
                handleFailedAttempt(player);
                return;
            }

            // Success - first time or returning user
            handleSuccessfulLogin(player);
            store.setEnrolled(id, true);
            // Save current IP and login time for future logins
            String currentIP = player.getAddress().getAddress().getHostAddress();
            long currentTime = System.currentTimeMillis();
            store.setLastIP(id, currentIP);
            store.setLastLoginTime(id, currentTime);
            
            // Cancel timeout and cleanup
            cancelTimeout(id);
            unfreeze(player); // This restores original inventory, removing QR map
            player.sendMessage(Component.text(config.getAuthSuccessMessage()));
            
            // Show join message now that authentication is complete using server's default format
            if (!isVanished(player)) {
                Component joinMsg = Component.text(config.getJoinMessage(player.getName())).color(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
                Bukkit.getServer().broadcast(joinMsg);
            }
            return;
        }

        if (key.equals(Dialogs.ACTION_LOGIN_LEAVE)) {
            // Player chose to leave the server
            unfreeze(player); // Restore inventory before kicking
            cancelAsk(id);
            cancelTimeout(id);
            player.kick(Component.text(config.getLeaveKickMessage()));
        }
    }

    /* ------------ Freeze mechanics ------------ */

    private void freeze(Player p) {
        if (frozen.add(p.getUniqueId())) {
            // Backup and clear inventory for temporary clean state
            ItemStack[] contents = p.getInventory().getContents();
            backupInventories.put(p.getUniqueId(), contents);
            p.getInventory().clear();
            
            // Lock held item slot to slot 0
            p.getInventory().setHeldItemSlot(0);
            
            // Backup current look angles (but don't change view yet)
            Location loc = p.getLocation();
            Float[] originalAngles = {loc.getYaw(), loc.getPitch()};
            backupLookAngles.put(p.getUniqueId(), originalAngles);
            
            p.setWalkSpeed(0f);
            p.setFlySpeed(0f);
            p.setInvulnerable(true);
            p.setCollidable(false);
            p.setFoodLevel(20);
            // Make player invisible to others
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
            // Hide from other players
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(p)) {
                    other.hidePlayer(this, p);
                }
            }
        }
    }
    private void unfreeze(Player p) {
        if (frozen.remove(p.getUniqueId())) {
            // Restore original inventory
            ItemStack[] backup = backupInventories.remove(p.getUniqueId());
            if (backup != null) {
                p.getInventory().setContents(backup);
            }
            
            // Restore original look angles
            Float[] originalAngles = backupLookAngles.remove(p.getUniqueId());
            if (originalAngles != null) {
                Location loc = p.getLocation();
                loc.setYaw(originalAngles[0]);
                loc.setPitch(originalAngles[1]);
                p.teleport(loc);
            }
            
            p.setWalkSpeed(0.2f);
            p.setFlySpeed(0.1f);
            p.setInvulnerable(false);
            p.setCollidable(true);
            // Remove invisibility
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            // Show to other players again
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(p)) {
                    other.showPlayer(this, p);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        
        // Prevent position movement
        if (e.getFrom().distanceSquared(e.getTo()) > 0) {
            e.setTo(e.getFrom()); // "freeze" by snapping back
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text(config.getFinishLoginError()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        // Prevent frozen players from being damaged
        if (e.getEntity() instanceof Player victim && frozen.contains(victim.getUniqueId())) {
            e.setCancelled(true);
        }
        // Prevent frozen players from damaging others
        if (e.getDamager() instanceof Player attacker && frozen.contains(attacker.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent e) {
        // Prevent any damage to frozen players
        if (e.getEntity() instanceof Player player && frozen.contains(player.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (frozen.contains(p.getUniqueId())) {
            ItemStack item = e.getItemDrop().getItemStack();
            if (item.getItemMeta() != null) {
                var pdc = item.getItemMeta().getPersistentDataContainer();
                Byte tag = pdc.get(QR_TAG, PersistentDataType.BYTE);
                if (tag != null && tag == (byte)1) {
                    e.setCancelled(true);
                    p.sendMessage(Component.text(config.getNoDropMapError()));
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && frozen.contains(p.getUniqueId())) {
            // Allow viewing the QR map but prevent moving it
            ItemStack item = e.getCurrentItem();
            if (item != null && item.getItemMeta() != null) {
                var pdc = item.getItemMeta().getPersistentDataContainer();
                Byte tag = pdc.get(QR_TAG, PersistentDataType.BYTE);
                if (tag != null && tag == (byte)1) {
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerChat(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text(config.getNoChatError()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            // Force back to slot 0 if they somehow change
            if (e.getPlayer().getInventory().getHeldItemSlot() != 0) {
                e.getPlayer().getInventory().setHeldItemSlot(0);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        if (frozen.contains(p.getUniqueId())) {
            // Check if they have a QR map in slot 0
            ItemStack item = p.getInventory().getItem(0);
            if (item != null && item.getItemMeta() != null) {
                var pdc = item.getItemMeta().getPersistentDataContainer();
                Byte tag = pdc.get(QR_TAG, PersistentDataType.BYTE);
                if (tag != null && tag == (byte)1) {
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJump(PlayerJumpEvent e) {
        Player p = e.getPlayer();
        if (frozen.contains(p.getUniqueId())) {
            // Check if they have a QR map in slot 0
            ItemStack item = p.getInventory().getItem(0);
            if (item != null && item.getItemMeta() != null) {
                var pdc = item.getItemMeta().getPersistentDataContainer();
                Byte tag = pdc.get(QR_TAG, PersistentDataType.BYTE);
                if (tag != null && tag == (byte)1) {
                    e.setCancelled(true);
                }
            }
        }
    }

    /* ------------ Commands ------------ */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("2fa")) return false;
        
        // Check permission
        if (!sender.hasPermission("twofadialog.admin")) {
            sender.sendMessage(Component.text(config.getNoPermissionMessage()));
            return true;
        }
        
        if (args.length < 1) {
            sender.sendMessage(Component.text(config.getUsageMessage()));
            return true;
        }
        
        String subcommand = args[0].toLowerCase();
        
        if (subcommand.equals("reload")) {
            // Reload config command
            try {
                config.loadConfig();
                sender.sendMessage(Component.text(config.getConfigReloadedMessage()));
            } catch (Exception e) {
                sender.sendMessage(Component.text(config.getConfigReloadErrorMessage(e.getMessage())));
            }
            return true;
        }
        
        if (subcommand.equals("remove")) {
            if (args.length < 2) {
                sender.sendMessage(Component.text(config.getUsageMessage()));
                return true;
            }
            
            String playerName = args[1];
            // Find player by name (online or offline)
            OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(playerName);
            
            if (targetPlayer == null || !targetPlayer.hasPlayedBefore()) {
                sender.sendMessage(Component.text(config.getPlayerNotFoundMessage(playerName)));
                return true;
            }
            
            // Remove all 2FA data for the player
            store.removeUser(targetPlayer.getUniqueId());
            sender.sendMessage(Component.text(config.getPlayerResetMessage(targetPlayer.getName())));
            
            // If player is online, kick them to apply changes
            if (targetPlayer.isOnline()) {
                Player onlinePlayer = (Player) targetPlayer;
                onlinePlayer.kick(Component.text(config.getAdminResetKickMessage()));
            }
            
            return true;
        } else {
            sender.sendMessage(Component.text(config.getUsageMessage()));
            return true;
        }
    }

    /* ------------ Utilities ------------ */

    private boolean isVanished(Player player) {
        for (MetadataValue meta : player.getMetadata("vanished")) {
            if (meta.asBoolean()) return true;
        }
        return false;
    }

    private void cleanupQrMaps(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getItemMeta() == null) continue;
            var pdc = it.getItemMeta().getPersistentDataContainer();
            Byte tag = pdc.get(QR_TAG, PersistentDataType.BYTE);
            if (tag != null && tag == (byte)1) {
                inv.clear(i);
            }
        }
    }
}
