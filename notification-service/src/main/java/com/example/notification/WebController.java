package com.example.notification;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;
import java.time.LocalDateTime;

@Controller
public class WebController {

    private final NotificationSubscriber subscriber;

    public WebController(NotificationSubscriber subscriber) {
        this.subscriber = subscriber;
    }

    @GetMapping("/")
    public String home(Model model) {
        long count = subscriber.getMessageCount();
        long uptime = Duration.between(subscriber.getStartTime(), LocalDateTime.now()).getSeconds();
        double throughput = uptime > 0 ? (double) count / uptime : 0;

        model.addAttribute("messagesProcessed", count);
        model.addAttribute("avgLatencyMs", subscriber.getAvgLatencyMs());
        model.addAttribute("uptimeSeconds", uptime);
        model.addAttribute("throughput", Math.round(throughput * 100.0) / 100.0);
        model.addAttribute("notifications", subscriber.getRecentNotifications());
        return "home";
    }
}
