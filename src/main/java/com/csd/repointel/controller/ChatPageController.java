package com.csd.repointel.controller;

import com.csd.repointel.model.*;
import com.csd.repointel.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@Slf4j
@Controller
public class ChatPageController {
    private final ChatService chatService;
    private final IntentParserService intentParserService;
    private final VersionComparisonService versionComparisonService;
    private final ChatResponseFormatter chatResponseFormatter;
    private final ExportService exportService;
    private final ObjectMapper mapper = new ObjectMapper();
    
    public ChatPageController(ChatService chatService,
                             IntentParserService intentParserService,
                             VersionComparisonService versionComparisonService,
                             ChatResponseFormatter chatResponseFormatter,
                             ExportService exportService) {
        this.chatService = chatService;
        this.intentParserService = intentParserService;
        this.versionComparisonService = versionComparisonService;
        this.chatResponseFormatter = chatResponseFormatter;
        this.exportService = exportService;
    }

    @GetMapping("/chat")
    public String chatForm() { return "chat"; }

    @PostMapping("/chat")
    public String submit(@RequestParam("query") String query, Model model) {
        if (query == null || query.isBlank()) {
            model.addAttribute("error", "Query required");
            return "chat";
        }

        log.info("Chat UI query: {}", query);
        model.addAttribute("query", query);

        try {
            // Parse intent
            QueryIntent intent = intentParserService.parseQuery(query);
            
            if (intent.getIntentType() == IntentType.VERSION_CHECK || 
                intent.getIntentType() == IntentType.VERSION_COMPARE ||
                intent.getIntentType() == IntentType.LIST_ALL) {
                
                // Use new enhanced version comparison
                VersionComparisonResult result = versionComparisonService.compareVersionsAcrossRepos(intent);
                String formattedResponse = chatResponseFormatter.formatVersionComparison(result);
                
                // Build enhanced response
                ChatResponse enhancedResponse = ChatResponse.builder()
                        .intent(ChatIntent.SINGLE)
                        .summary(result.getSummary().getNaturalLanguageSummary())
                        .link("/api/export/json?package=" + (intent.getPackageName() != null ? intent.getPackageName() : ""))
                        .data(Map.of(
                            "result", result,
                            "formatted", formattedResponse,
                            "exportLinks", exportService.getExportLinks(
                                intent.getPackageName() != null ? intent.getPackageName() : "results")
                        ))
                        .build();
                
                model.addAttribute("response", enhancedResponse);
                model.addAttribute("dataJson", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                
            } else {
                // Fallback to legacy chat service
                ChatResponse resp = chatService.interpret(query);
                model.addAttribute("response", resp);
                try { 
                    model.addAttribute("dataJson", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resp.getData())); 
                } catch (Exception e) { 
                    model.addAttribute("dataJson", "{\"error\":\"Unable to serialize\"}"); 
                }
            }
            
        } catch (Exception e) {
            log.error("Error processing chat query", e);
            model.addAttribute("error", "Error: " + e.getMessage());
            ChatResponse errorResponse = ChatResponse.builder()
                    .intent(ChatIntent.UNKNOWN)
                    .summary("Error processing query")
                    .data(Map.of("error", e.getMessage()))
                    .build();
            model.addAttribute("response", errorResponse);
        }

        return "chat";
    }
}

