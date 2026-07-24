package org.example.controller;

import org.example.common.Result;
import org.example.service.RedPacketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/redpacket")
public class RedPacketController {

    @Autowired
    private RedPacketService redPacketService;

    @PostMapping("/send")
    public Result<Map<String, Object>> sendRedPacket(
            @RequestParam Long senderId,
            @RequestParam BigDecimal totalAmount,
            @RequestParam Integer totalCount,
            @RequestParam(required = false) String message,
            @RequestParam(defaultValue = "RANDOM") String type) {
        Map<String, Object> result = redPacketService.sendRedPacket(senderId, totalAmount, totalCount, message, type);
        return Result.success("红包发送成功", result);
    }

    @PostMapping("/grab")
    public Result<Map<String, Object>> grabRedPacket(
            @RequestParam Long packetId,
            @RequestParam Long userId) {
        Map<String, Object> result = redPacketService.grabRedPacket(packetId, userId);
        return Result.success(result);
    }

    @GetMapping("/info/{packetId}")
    public Result<Map<String, Object>> getRedPacketInfo(@PathVariable Long packetId) {
        Map<String, Object> info = redPacketService.getRedPacketInfo(packetId);
        return Result.success(info);
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> getActiveRedPackets(
            @RequestParam(defaultValue = "20") Integer limit) {
        List<Map<String, Object>> list = redPacketService.getActiveRedPackets(limit);
        return Result.success(list);
    }
}