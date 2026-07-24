package org.example.controller;

import org.example.common.Result;
import org.example.dto.message.MessageResponseDTO;
import org.example.dto.message.MessageSendDTO;
import org.example.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    @Autowired
    private MessageService messageService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @GetMapping("/user/{userId}")
    public Result<List<MessageResponseDTO>> getMessagesByUserId(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "50") Integer limit) {
        List<MessageResponseDTO> messages = messageService.getMessagesByUserId(userId, limit);
        return Result.success(messages);
    }

    @GetMapping("/private/{user1}/{user2}")
    public Result<List<MessageResponseDTO>> getPrivateMessages(
            @PathVariable Long user1,
            @PathVariable Long user2,
            @RequestParam(defaultValue = "50") Integer limit) {
        List<MessageResponseDTO> messages = messageService.getPrivateMessages(user1, user2, limit);
        return Result.success(messages);
    }

    @PostMapping("/private/{fromUserId}/{toUserId}")
    public Result<MessageResponseDTO> sendPrivateMessage(
            @PathVariable Long fromUserId,
            @PathVariable Long toUserId,
            @RequestBody MessageSendDTO dto) {
        dto.setToUserId(toUserId);
        MessageResponseDTO response = messageService.sendMessage(fromUserId, dto);
        
        messagingTemplate.convertAndSend("/topic/user/" + toUserId, response);
        
        return Result.success("消息发送成功", response);
    }
}