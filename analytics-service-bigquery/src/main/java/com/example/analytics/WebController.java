package com.example.analytics;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class WebController {

    private final AnalyticsSubscriber subscriber;
    private final BigQueryService bigQueryService;

    public WebController(AnalyticsSubscriber subscriber, BigQueryService bigQueryService) {
        this.subscriber = subscriber;
        this.bigQueryService = bigQueryService;
    }

    @GetMapping("/")
    public String home(Model model) {
        long count = subscriber.getMessageCount();
        long uptimeSeconds = Duration.between(subscriber.getStartTime(), Instant.now()).getSeconds();
        double throughput = uptimeSeconds > 0 ? (double) count / uptimeSeconds : 0;

        model.addAttribute("messagesProcessed", count);
        model.addAttribute("avgLatencyMs", count > 0 ? subscriber.getTotalLatencyMs() / count : 0);
        model.addAttribute("uptimeSeconds", uptimeSeconds);
        model.addAttribute("throughput", Math.round(throughput * 100.0) / 100.0);
        model.addAttribute("totalVolume", subscriber.getTotalAmount());
        model.addAttribute("maxTransaction", subscriber.getMaxTransaction());
        model.addAttribute("minTransaction", subscriber.getMinTransaction());
        model.addAttribute("amountDistribution", subscriber.getAmountBuckets().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
        model.addAttribute("topSenders", subscriber.getTopSenders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
        model.addAttribute("topReceivers", subscriber.getTopReceivers().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
        model.addAttribute("bqRowsInserted", bigQueryService.getRowsInserted());
        model.addAttribute("bqInsertErrors", bigQueryService.getInsertErrors());
        model.addAttribute("bqDataset", bigQueryService.getDatasetId());
        model.addAttribute("bqTable", bigQueryService.getTableId());
        return "home";
    }
}
