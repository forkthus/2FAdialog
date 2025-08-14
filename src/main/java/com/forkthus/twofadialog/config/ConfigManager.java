package com.forkthus.twofadialog.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class ConfigManager {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    public void loadConfig() {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }
    
    // Auth settings
    public int getTimeWindow() {
        return config.getInt("auth.time-window", 1);
    }
    
    public int getIpBypassDays() {
        return config.getInt("auth.ip-bypass-days", 7);
    }
    
    public int getScanAskDelay() {
        return config.getInt("auth.scan-ask-delay", 10);
    }
    
    public String getServerName() {
        return config.getString("auth.server-name", "My Minecraft Server");
    }
    
    public int getRegistrationTimeout() {
        return config.getInt("auth.registration-timeout", 90);
    }
    
    public int getLoginTimeout() {
        return config.getInt("auth.login-timeout", 30);
    }
    
    public int getMaxFailedAttempts() {
        return config.getInt("auth.max-failed-attempts", 3);
    }
    
    public int getFailedAttemptBanMinutes() {
        return config.getInt("auth.failed-attempt-ban-minutes", 5);
    }
    
    // Message getters with placeholder support
    public String getMessage(String path) {
        return config.getString("messages." + path, "Missing message: " + path);
    }
    
    public String getMessage(String path, String placeholder, String value) {
        return getMessage(path).replace("%" + placeholder + "%", value);
    }
    
    // Specific message getters for commonly used ones
    public String getScanPromptTitle() { return getMessage("scan-prompt.title"); }
    public String getScanPromptBody(int timeLeft) { return getMessage("scan-prompt.body", "time-left", String.valueOf(timeLeft)); }
    public String getScanPromptButtonText() { return getMessage("scan-prompt.button-text"); }
    public String getScanPromptButtonDesc() { return getMessage("scan-prompt.button-description"); }
    public String getScanPromptExitButton() { return getMessage("scan-prompt.exit-button"); }
    public String getScanPromptExitDesc() { return getMessage("scan-prompt.exit-description"); }
    
    public String getAskFinishedTitle() { return getMessage("ask-finished.title"); }
    public String getAskFinishedBody(int timeLeft) { return getMessage("ask-finished.body", "time-left", String.valueOf(timeLeft)); }
    public String getAskFinishedYesButton() { return getMessage("ask-finished.yes-button"); }
    public String getAskFinishedYesDesc() { return getMessage("ask-finished.yes-description"); }
    public String getAskFinishedNoButton() { return getMessage("ask-finished.no-button"); }
    public String getAskFinishedNoDesc() { return getMessage("ask-finished.no-description"); }
    
    public String getLoginTitle() { return getMessage("login.title"); }
    public String getLoginBody(int timeLeft) { return getMessage("login.body", "time-left", String.valueOf(timeLeft)); }
    public String getLoginOtpLabel() { return getMessage("login.otp-field-label"); }
    public String getLoginRulesLabel() { return getMessage("login.rules-field-label"); }
    public String getLoginSubmitButton() { return getMessage("login.submit-button"); }
    public String getLoginSubmitDesc() { return getMessage("login.submit-description"); }
    public String getLoginLeaveButton() { return getMessage("login.leave-button"); }
    public String getLoginLeaveDesc() { return getMessage("login.leave-description"); }
    
    public String getQrReceivedMessage() { return getMessage("game.qr-received"); }
    public String getAuthSuccessMessage() { return getMessage("game.auth-success"); }
    public String getJoinMessage(String playerName) { return getMessage("game.join-message", "player", playerName); }
    public String getLeaveKickMessage() { return getMessage("game.leave-kick"); }
    public String getAdminResetKickMessage() { return getMessage("game.admin-reset-kick"); }
    
    public String getNoInputError() { return getMessage("errors.no-input"); }
    public String getMustAgreeRulesError() { return getMessage("errors.must-agree-rules"); }
    public String getWrongCodeError(int attemptsLeft) { return getMessage("errors.wrong-code", "attempts-left", String.valueOf(attemptsLeft)); }
    public String getWrongCodeBannedError(int banMinutes) { return getMessage("errors.wrong-code-banned", "ban-time", String.valueOf(banMinutes)); }
    public String getNoChatError() { return getMessage("errors.no-chat"); }
    public String getNoDropMapError() { return getMessage("errors.no-drop-map"); }
    public String getFinishLoginError() { return getMessage("errors.finish-login"); }
    public String getTimeoutExpiredError() { return getMessage("errors.timeout-expired"); }
    
    public String getNoPermissionMessage() { return getMessage("admin.no-permission"); }
    public String getUsageMessage() { return getMessage("admin.usage"); }
    public String getPlayerNotFoundMessage(String playerName) { return getMessage("admin.player-not-found", "player", playerName); }
    public String getPlayerResetMessage(String playerName) { return getMessage("admin.player-reset", "player", playerName); }
    public String getConfigReloadedMessage() { return getMessage("admin.config-reloaded"); }
    public String getConfigReloadErrorMessage(String error) { return getMessage("admin.config-reload-error", "error", error); }
}