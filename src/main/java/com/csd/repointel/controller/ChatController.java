package com.csd.repointel.controller;

import com.csd.repointel.model.ChatResponse;
import com.csd.repointel.service.ChatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) { this.chatService = chatService; }

    @GetMapping("/chat")
    public ChatResponse chat(@RequestParam(name = "query") String query) {
        return chatService.interpret(query);
    }
}
