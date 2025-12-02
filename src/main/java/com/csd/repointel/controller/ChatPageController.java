package com.csd.repointel.controller;

import com.csd.repointel.model.ChatResponse;
import com.csd.repointel.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ChatPageController {
    private final ChatService chatService;
    private final ObjectMapper mapper = new ObjectMapper();
    public ChatPageController(ChatService chatService) { this.chatService = chatService; }

    @GetMapping("/chat")
    public String chatForm() { return "chat"; }

    @PostMapping("/chat")
    public String submit(@RequestParam("query") String query, Model model) {
        if (query == null || query.isBlank()) {
            model.addAttribute("error", "Query required");
            return "chat";
        }
        ChatResponse resp = chatService.interpret(query);
        model.addAttribute("query", query);
        model.addAttribute("response", resp);
        try { model.addAttribute("dataJson", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resp.getData())); }
        catch (Exception e) { model.addAttribute("dataJson", "{\"error\":\"Unable to serialize\"}"); }
        return "chat";
    }
}

