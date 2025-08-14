package com.forkthus.twofadialog.storage;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public final class YamlUserStore implements UserStore {
    private final File file;
    private final FileConfiguration cfg;

    public YamlUserStore(File dataFolder) {
        this.file = new File(dataFolder, "users.yml");
        this.cfg  = YamlConfiguration.loadConfiguration(file);
    }

    private String base(String id) { return "users." + id; }

    @Override public boolean hasSecret(UUID id) { return cfg.contains(base(id.toString()) + ".secret"); }
    @Override public String getSecret(UUID id) { return cfg.getString(base(id.toString()) + ".secret"); }

    @Override public void setSecret(UUID id, String base32) {
        String b = base(id.toString());
        cfg.set(b + ".secret", base32);
        if (!cfg.contains(b + ".enrolled")) cfg.set(b + ".enrolled", false);
        save();
    }

    @Override public boolean isEnrolled(UUID id) { return cfg.getBoolean(base(id.toString()) + ".enrolled", false); }
    @Override public void setEnrolled(UUID id, boolean v) { cfg.set(base(id.toString()) + ".enrolled", v); save(); }
    
    @Override public String getLastIP(UUID id) { return cfg.getString(base(id.toString()) + ".lastip"); }
    @Override public void setLastIP(UUID id, String ip) { cfg.set(base(id.toString()) + ".lastip", ip); save(); }
    
    @Override public long getLastLoginTime(UUID id) { return cfg.getLong(base(id.toString()) + ".lastlogin", 0); }
    @Override public void setLastLoginTime(UUID id, long timestamp) { cfg.set(base(id.toString()) + ".lastlogin", timestamp); save(); }
    
    @Override public int getFailedAttempts(UUID id) { return cfg.getInt(base(id.toString()) + ".failedattempts", 0); }
    @Override public void setFailedAttempts(UUID id, int attempts) { cfg.set(base(id.toString()) + ".failedattempts", attempts); save(); }
    
    @Override public long getBanExpiry(UUID id) { return cfg.getLong(base(id.toString()) + ".banexpiry", 0); }
    @Override public void setBanExpiry(UUID id, long timestamp) { cfg.set(base(id.toString()) + ".banexpiry", timestamp); save(); }
    
    @Override public void removeUser(UUID id) { cfg.set(base(id.toString()), null); save(); }

    @Override public void save() {
        try { cfg.save(file); } catch (IOException e) { e.printStackTrace(); }
    }
    @Override public void close() { save(); }
}
