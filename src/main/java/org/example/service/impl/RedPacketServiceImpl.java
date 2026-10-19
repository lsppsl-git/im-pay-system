package org.example.service.impl;

import org.example.common.BusinessException;
import org.example.entity.RedPacket;
import org.example.entity.RedPacketRecord;
import org.example.entity.TransactionLog;
import org.example.entity.Wallet;
import org.example.mapper.RedPacketMapper;
import org.example.mapper.RedPacketRecordMapper;
import org.example.mapper.TransactionLogMapper;
import org.example.mapper.WalletMapper;
import org.example.service.RedPacketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@Service
public class RedPacketServiceImpl implements RedPacketService {

    @Autowired
    private RedPacketMapper redPacketMapper;

    @Autowired
    private RedPacketRecordMapper redPacketRecordMapper;

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private TransactionLogMapper transactionLogMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 每次"读→判→带version更新"必须跑在独立新事务里，否则 RR 隔离级别下重读到的还是旧快照，重试永远失败 */
    private TransactionTemplate requiresNewTx;

    /** 乐观锁 version 冲突时的最大重试次数 */
    private static final int MAX_RETRY = 3;

    private final Random random = new Random();

    @PostConstruct
    private void initTx() {
        requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sendRedPacket(Long senderId, BigDecimal totalAmount, Integer totalCount, String message, String type) {
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.of(400, "红包金额必须大于0");
        }

        if (totalCount <= 0) {
            throw BusinessException.of(400, "红包个数必须大于0");
        }

        BigDecimal minAmount = new BigDecimal("0.01");
        BigDecimal maxAmount = totalAmount.divide(new BigDecimal(totalCount), 2, RoundingMode.DOWN);
        if (maxAmount.compareTo(minAmount) < 0) {
            throw BusinessException.of(400, "平均每人金额不能小于0.01");
        }

        Wallet senderWallet = walletMapper.findByUserIdForUpdate(senderId);
        if (senderWallet == null) {
            throw BusinessException.of(404, "发送者钱包不存在");
        }

        if (senderWallet.getBalance().compareTo(totalAmount) < 0) {
            throw BusinessException.of(400, "余额不足");
        }

        int rows = walletMapper.deduct(senderId, totalAmount, senderWallet.getVersion());
        if (rows == 0) {
            throw BusinessException.of(500, "扣款失败，请重试");
        }

        String packetNo = UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        RedPacket redPacket = RedPacket.builder()
                .packetNo(packetNo)
                .senderId(senderId)
                .totalAmount(totalAmount)
                .totalCount(totalCount)
                .remainAmount(totalAmount)
                .remainCount(totalCount)
                .message(message)
                .type(type)
                .status("ACTIVE")
                .expiredAt(LocalDateTime.now().plusHours(24))
                .createdAt(LocalDateTime.now())
                .build();

        redPacketMapper.insert(redPacket);

        TransactionLog log = TransactionLog.builder()
                .transactionNo(generateTransactionNo())
                .fromUserId(senderId)
                .toUserId(null)
                .amount(totalAmount)
                .type("RED_PACKET")
                .status("SUCCESS")
                .remark("发红包")
                .createdAt(LocalDateTime.now())
                .build();

        transactionLogMapper.insert(log);

        Map<String, Object> result = new HashMap<>();
        result.put("packetId", redPacket.getId());
        result.put("packetNo", packetNo);
        result.put("message", "红包发送成功");

        return result;
    }

    /**
     * 抢红包入口，按 mode 走三种实现：
     * version（默认）= 乐观锁 version + 冲突重试；
     * lock = 悲观锁 SELECT ... FOR UPDATE（本系统原实现，保留作对比）；
     * unsafe = 【仅教学演示】无锁错误写法，用于复现并发超抢
     */
    @Override
    public Map<String, Object> grabRedPacket(Long packetId, Long userId, String mode) {
        if ("unsafe".equals(mode)) {
            return requiresNewTx.execute(status -> doGrabUnsafe(packetId, userId));
        }
        if ("lock".equals(mode)) {
            return requiresNewTx.execute(status -> doGrabPessimistic(packetId, userId));
        }
        // 乐观锁：读出版本 → 带 version 条件更新 → 更新 0 行说明被别人改过，重读重试
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            Map<String, Object> result = requiresNewTx.execute(status -> doGrabWithVersion(packetId, userId));
            if (result != null) {
                return result;
            }
        }
        throw BusinessException.of(400, "手慢了，红包已被抢完");
    }

    /** 乐观锁实现：返回 null 表示 version 冲突，交给外层重试 */
    private Map<String, Object> doGrabWithVersion(Long packetId, Long userId) {
        // ① 普通读，不加锁；读到的 version 就是本次更新的"凭据"
        RedPacket redPacket = redPacketMapper.selectById(packetId);
        BigDecimal grabAmount = validateAndCalcAmount(redPacket, userId);

        // ② 带 version 条件更新：若这行被别人改过，version 已对不上 → 更新 0 行
        int rows = redPacketMapper.grabWithVersion(packetId, grabAmount, redPacket.getVersion());
        if (rows == 0) {
            return null;
        }

        afterGrabSuccess(packetId, userId, grabAmount);
        return buildGrabResult(grabAmount);
    }

    /** 悲观锁实现：SELECT ... FOR UPDATE 持有行锁直到事务结束，并发的抢包请求在读取处排队 */
    private Map<String, Object> doGrabPessimistic(Long packetId, Long userId) {
        RedPacket redPacket = redPacketMapper.findByIdForUpdate(packetId);
        BigDecimal grabAmount = validateAndCalcAmount(redPacket, userId);

        int rows = redPacketMapper.grab(packetId, grabAmount);
        if (rows == 0) {
            throw BusinessException.of(400, "手慢了，红包已被抢完");
        }

        afterGrabSuccess(packetId, userId, grabAmount);
        return buildGrabResult(grabAmount);
    }

    /** 【仅教学演示】无锁错误写法：先查再改，中间无任何保护，并发下必然超抢 */
    private Map<String, Object> doGrabUnsafe(Long packetId, Long userId) {
        RedPacket redPacket = redPacketMapper.selectById(packetId);
        BigDecimal grabAmount = validateAndCalcAmount(redPacket, userId);

        // 故意放大"判断"与"更新"之间的时间窗口，让并发竞争必然发生
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 无条件 UPDATE：不带 version、不判断剩余，多个请求同时通过上面的 if 就会全部扣减成功
        redPacketMapper.grabUnsafe(packetId, grabAmount);

        afterGrabSuccess(packetId, userId, grabAmount);
        return buildGrabResult(grabAmount);
    }

    /** 公共校验 + 计算本次抢到的金额 */
    private BigDecimal validateAndCalcAmount(RedPacket redPacket, Long userId) {
        if (redPacket == null) {
            throw BusinessException.of(404, "红包不存在");
        }
        if ("FINISHED".equals(redPacket.getStatus())) {
            throw BusinessException.of(400, "红包已被抢完");
        }
        if ("EXPIRED".equals(redPacket.getStatus())) {
            throw BusinessException.of(400, "红包已过期");
        }
        if (redPacket.getSenderId().equals(userId)) {
            throw BusinessException.of(400, "不能抢自己发的红包");
        }
        RedPacketRecord existingRecord = redPacketRecordMapper.findByPacketAndUser(redPacket.getId(), userId);
        if (existingRecord != null) {
            throw BusinessException.of(400, "你已经抢过这个红包了");
        }
        if (redPacket.getRemainCount() <= 0) {
            throw BusinessException.of(400, "红包已被抢完");
        }
        return calcGrabAmount(redPacket);
    }

    private BigDecimal calcGrabAmount(RedPacket redPacket) {
        if (redPacket.getRemainCount() == 1) {
            return redPacket.getRemainAmount();
        }
        if ("FIXED".equals(redPacket.getType())) {
            return redPacket.getTotalAmount()
                    .divide(new BigDecimal(redPacket.getTotalCount()), 2, RoundingMode.DOWN);
        }
        BigDecimal minAmount = new BigDecimal("0.01");
        BigDecimal maxAmount = redPacket.getRemainAmount()
                .subtract(minAmount.multiply(new BigDecimal(redPacket.getRemainCount() - 1)))
                .multiply(new BigDecimal("2"))
                .divide(new BigDecimal(redPacket.getRemainCount()), 2, RoundingMode.DOWN);

        if (maxAmount.compareTo(minAmount) <= 0) {
            return minAmount;
        }
        double randomValue = random.nextDouble();
        return minAmount.add(maxAmount.subtract(minAmount).multiply(new BigDecimal(randomValue)))
                .setScale(2, RoundingMode.DOWN);
    }

    /** 扣减成功后的收尾：钱包入账 + 写抢包记录 + 剩余为 0 时置 FINISHED */
    private void afterGrabSuccess(Long packetId, Long userId, BigDecimal grabAmount) {
        Wallet wallet = walletMapper.findByUserIdForUpdate(userId);
        if (wallet == null) {
            wallet = Wallet.builder()
                    .userId(userId)
                    .balance(BigDecimal.ZERO)
                    .version(0)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            walletMapper.insert(wallet);
        }

        walletMapper.add(userId, grabAmount, wallet.getVersion());

        RedPacketRecord record = RedPacketRecord.builder()
                .redPacketId(packetId)
                .userId(userId)
                .amount(grabAmount)
                .createdAt(LocalDateTime.now())
                .build();
        redPacketRecordMapper.insert(record);

        RedPacket latest = redPacketMapper.selectById(packetId);
        if (latest.getRemainCount() == 0) {
            latest.setStatus("FINISHED");
            redPacketMapper.updateById(latest);
        }
    }

    private Map<String, Object> buildGrabResult(BigDecimal grabAmount) {
        Map<String, Object> result = new HashMap<>();
        result.put("amount", grabAmount);
        result.put("message", "抢红包成功");
        return result;
    }

    @Override
    public Map<String, Object> getRedPacketInfo(Long packetId) {
        RedPacket redPacket = redPacketMapper.selectById(packetId);
        if (redPacket == null) {
            throw BusinessException.of(404, "红包不存在");
        }

        List<RedPacketRecord> records = redPacketRecordMapper.findByPacketId(packetId);

        Map<String, Object> result = new HashMap<>();
        result.put("packetId", redPacket.getId());
        result.put("totalAmount", redPacket.getTotalAmount());
        result.put("totalCount", redPacket.getTotalCount());
        result.put("remainAmount", redPacket.getRemainAmount());
        result.put("remainCount", redPacket.getRemainCount());
        result.put("message", redPacket.getMessage());
        result.put("status", redPacket.getStatus());
        result.put("version", redPacket.getVersion());
        result.put("records", records);

        return result;
    }

    private String generateTransactionNo() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    @Override
    public List<Map<String, Object>> getActiveRedPackets(Integer limit) {
        List<RedPacket> redPackets = redPacketMapper.findActiveRedPackets(limit != null ? limit : 20);
        return redPackets.stream().map(rp -> {
            Map<String, Object> map = new HashMap<>();
            map.put("packetId", rp.getId());
            map.put("totalAmount", rp.getTotalAmount());
            map.put("totalCount", rp.getTotalCount());
            map.put("remainAmount", rp.getRemainAmount());
            map.put("remainCount", rp.getRemainCount());
            map.put("message", rp.getMessage());
            map.put("status", rp.getStatus());
            return map;
        }).collect(java.util.stream.Collectors.toList());
    }
}