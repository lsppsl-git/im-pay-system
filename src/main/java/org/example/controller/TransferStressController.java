package org.example.controller;

import org.example.common.Result;
import org.example.service.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 转账死锁压测入口（仅本地测试用）
 * 用法：POST /api/wallet/transfer-stress?fromUserId=9001&toUserId=9002&rounds=25&mode=unsafe|safe
 * 两个线程同时发起方向相反的转账（A→B 与 B→A），观察加锁顺序不同导致的死锁差异
 */
@RestController
@RequestMapping("/api/wallet")
public class TransferStressController {

    @Autowired
    private WalletService walletService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostMapping("/transfer-stress")
    public Map<String, Object> transferStress(@RequestParam Long fromUserId,
                                              @RequestParam Long toUserId,
                                              @RequestParam(defaultValue = "25") int rounds,
                                              @RequestParam(defaultValue = "0.01") BigDecimal amount,
                                              @RequestParam(defaultValue = "unsafe") String mode) {
        Map<String, Object> report = new HashMap<>();
        report.put("mode", mode);
        report.put("fromUserId", fromUserId);
        report.put("toUserId", toUserId);
        report.put("roundsPerDirection", rounds);
        report.put("amount", amount);

        BigDecimal fromBefore = queryBalance(fromUserId);
        BigDecimal toBefore = queryBalance(toUserId);
        report.put("fromBalanceBefore", fromBefore);
        report.put("toBalanceBefore", toBefore);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger deadlock = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        long start = System.nanoTime();
        //线程1：A→B × rounds；线程2：B→A × rounds
        Long[][] jobs = {{fromUserId, toUserId}, {toUserId, fromUserId}};
        for (Long[] job : jobs) {
            Long from = job[0], to = job[1];
            pool.submit(() -> {
                try {
                    for (int i = 0; i < rounds; i++) {
                        try {
                            if ("safe".equalsIgnoreCase(mode)) {
                                walletService.transferSafe(from, to, amount);
                            } else {
                                walletService.transfer(from, to, amount);
                            }
                            success.incrementAndGet();
                        } catch (Throwable e) {
                            String sig = e.getClass().getSimpleName() + " " + String.valueOf(rootMessage(e));
                            if (sig.contains("eadlock")) {
                                deadlock.incrementAndGet();
                            } else {
                                other.incrementAndGet();
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();

        BigDecimal fromAfter = queryBalance(fromUserId);
        BigDecimal toAfter = queryBalance(toUserId);
        report.put("success", success.get());
        report.put("deadlockErrors", deadlock.get());
        report.put("otherErrors", other.get());
        report.put("elapsedMs", elapsedMs);
        report.put("fromBalanceAfter", fromAfter);
        report.put("toBalanceAfter", toAfter);
        report.put("totalBefore", fromBefore.add(toBefore));
        report.put("totalAfter", fromAfter.add(toAfter));
        report.put("totalConserved", fromBefore.add(toBefore).compareTo(fromAfter.add(toAfter)) == 0);
        return report;
    }

    private BigDecimal queryBalance(Long userId) {
        BigDecimal balance = jdbcTemplate.queryForObject(
                "select balance from wallet where user_id = ?", BigDecimal.class, userId);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? "" : cur.getMessage();
    }
}
