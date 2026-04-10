package com.example.fraud;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;
import java.time.LocalDateTime;

@Controller
public class WebController {

    private final FraudDetectionSubscriber subscriber;

    public WebController(FraudDetectionSubscriber subscriber) {
        this.subscriber = subscriber;
    }

    @GetMapping("/")
    public String home(Model model) {
        long count = subscriber.getMessageCount();
        long uptime = Duration.between(subscriber.getStartTime(), LocalDateTime.now()).getSeconds();
        double throughput = uptime > 0 ? (double) count / uptime : 0;

        model.addAttribute("messagesProcessed", count);
        model.addAttribute("alertCount", subscriber.getAlertCount());
        model.addAttribute("avgLatencyMs", subscriber.getAvgLatencyMs());
        model.addAttribute("uptimeSeconds", uptime);
        model.addAttribute("throughput", Math.round(throughput * 100.0) / 100.0);
        model.addAttribute("checks", subscriber.getRecentChecks());
        return "home";
    }
}
