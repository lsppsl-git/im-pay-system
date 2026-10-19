package org.example.service;

import java.math.BigDecimal;
import java.util.Map;

public interface WalletService {

    void transfer(Long fromUserId, Long toUserId, BigDecimal amount);

    /**
     * 安全转账：按账户ID升序加锁（全局一致的加锁顺序），避免 A→B 与 B→A 交叉持锁导致死锁
     */
    void transferSafe(Long fromUserId, Long toUserId, BigDecimal amount);

    BigDecimal getBalance(Long userId);

    void recharge(Long userId, BigDecimal amount);

    Map<String, Object> getWalletInfo(Long userId);
}