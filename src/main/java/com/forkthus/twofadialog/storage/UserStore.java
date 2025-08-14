package com.forkthus.twofadialog.storage;

import java.util.UUID;

public interface UserStore {
    boolean hasSecret(UUID id);
    String  getSecret(UUID id);
    void    setSecret(UUID id, String base32);
    boolean isEnrolled(UUID id);          // first OTP completed
    void    setEnrolled(UUID id, boolean v);
    String  getLastIP(UUID id);           // get last successful login IP
    void    setLastIP(UUID id, String ip); // set last successful login IP
    long    getLastLoginTime(UUID id);    // get last successful login timestamp (milliseconds)
    void    setLastLoginTime(UUID id, long timestamp); // set last successful login timestamp
    int     getFailedAttempts(UUID id);   // get current failed login attempts
    void    setFailedAttempts(UUID id, int attempts); // set failed login attempts
    long    getBanExpiry(UUID id);        // get ban expiry timestamp (milliseconds)
    void    setBanExpiry(UUID id, long timestamp); // set ban expiry timestamp
    void    removeUser(UUID id);          // remove all data for a user (for reset)
    void    save();
    void    close();
}
