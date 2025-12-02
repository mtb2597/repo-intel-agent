package com.csd.repointel.controller;

import com.csd.repointel.model.RepoScanResult;
import com.csd.repointel.service.RepoScanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Controller
public class ScanUiController {
    private final RepoScanService repoScanService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScanUiController(RepoScanService repoScanService) {
        this.repoScanService = repoScanService;
    }

    @GetMapping("/scan")
    public String scanForm(Model model) {
        addStateToModel(model);
        return "scan";
    }

    @PostMapping("/scan")
    public String scanRepos(@RequestParam("repos") String repos, Model model) {
        if (repos == null || repos.isBlank()) {
            model.addAttribute("error", "Please enter at least one repository URL");
            addStateToModel(model);
            return "scan";
        }

        List<String> repoList = Arrays.stream(repos.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        if (repoList.isEmpty()) {
            model.addAttribute("error", "No valid repository URLs provided");
            addStateToModel(model);
            return "scan";
        }

        try {
            List<CompletableFuture<RepoScanResult>> futures = repoList.stream()
                    .map(repoScanService::scanRepo)
                    .collect(Collectors.toList());

            List<RepoScanResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            long successCount = results.stream().filter(RepoScanResult::isSuccess).count();
            model.addAttribute("message", String.format("Scanned %d repositories. %d successful, %d failed.",
                    results.size(), successCount, results.size() - successCount));

            model.addAttribute("results", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        } catch (Exception e) {
            model.addAttribute("error", "Error scanning repositories: " + e.getMessage());
        }

        addStateToModel(model);
        return "scan";
    }

    @GetMapping("/state")
    public String viewState(Model model) {
        addStateToModel(model);
        return "state";
    }

    @GetMapping("/help/private-repos")
    public String helpPrivateRepos() {
        return "help-private-repos";
    }

    private void addStateToModel(Model model) {
        var repoDeps = repoScanService.getRepoDependencies();
        model.addAttribute("repoCount", repoDeps.size());
        model.addAttribute("repoNames", repoDeps.keySet());
    }
}

