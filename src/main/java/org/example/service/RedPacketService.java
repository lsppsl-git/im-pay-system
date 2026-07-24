package org.example.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface RedPacketService {

    Map<String, Object> sendRedPacket(Long senderId, BigDecimal totalAmount, Integer totalCount, String message, String type);

    Map<String, Object> grabRedPacket(Long packetId, Long userId);

    Map<String, Object> getRedPacketInfo(Long packetId);

    List<Map<String, Object>> getActiveRedPackets(Integer limit);
}