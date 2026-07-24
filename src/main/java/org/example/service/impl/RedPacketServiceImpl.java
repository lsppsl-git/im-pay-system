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
import org.springframework.transaction.annotation.Transactional;

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

    private final Random random = new Random();

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> grabRedPacket(Long packetId, Long userId) {
        RedPacket redPacket = redPacketMapper.findByIdForUpdate(packetId);
        if (redPacket == null) {
            throw BusinessException.of(404, "红包不存在");
        }

        if (redPacket.getStatus().equals("FINISHED")) {
            throw BusinessException.of(400, "红包已被抢完");
        }

        if (redPacket.getStatus().equals("EXPIRED")) {
            throw BusinessException.of(400, "红包已过期");
        }

        if (redPacket.getSenderId().equals(userId)) {
            throw BusinessException.of(400, "不能抢自己发的红包");
        }

        RedPacketRecord existingRecord = redPacketRecordMapper.findByPacketAndUser(packetId, userId);
        if (existingRecord != null) {
            throw BusinessException.of(400, "你已经抢过这个红包了");
        }

        if (redPacket.getRemainCount() <= 0) {
            throw BusinessException.of(400, "红包已被抢完");
        }

        BigDecimal grabAmount;
        if (redPacket.getRemainCount() == 1) {
            grabAmount = redPacket.getRemainAmount();
        } else if ("FIXED".equals(redPacket.getType())) {
            grabAmount = redPacket.getTotalAmount()
                    .divide(new BigDecimal(redPacket.getTotalCount()), 2, RoundingMode.DOWN);
        } else {
            BigDecimal minAmount = new BigDecimal("0.01");
            BigDecimal maxAmount = redPacket.getRemainAmount()
                    .subtract(minAmount.multiply(new BigDecimal(redPacket.getRemainCount() - 1)))
                    .multiply(new BigDecimal("2"))
                    .divide(new BigDecimal(redPacket.getRemainCount()), 2, RoundingMode.DOWN);

            if (maxAmount.compareTo(minAmount) <= 0) {
                grabAmount = minAmount;
            } else {
                double randomValue = random.nextDouble();
                grabAmount = minAmount.add(maxAmount.subtract(minAmount).multiply(new BigDecimal(randomValue)))
                        .setScale(2, RoundingMode.DOWN);
            }
        }

        int grabRows = redPacketMapper.grab(packetId, grabAmount);
        if (grabRows == 0) {
            throw BusinessException.of(400, "手慢了，红包已被抢完");
        }

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

        redPacket = redPacketMapper.selectById(packetId);
        if (redPacket.getRemainCount() == 0) {
            redPacket.setStatus("FINISHED");
            redPacketMapper.updateById(redPacket);
        }

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