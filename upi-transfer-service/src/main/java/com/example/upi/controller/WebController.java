package com.example.upi.controller;

import com.example.upi.dto.TransferRequest;
import com.example.upi.service.TransferService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
public class WebController {

    private final TransferService transferService;

    public WebController(TransferService transferService) {
        this.transferService = transferService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("accounts", transferService.getAllAccounts());
        model.addAttribute("transactions", transferService.getAllTransactions());
        model.addAttribute("pubsubEnabled", true);
        return "home";
    }

    @PostMapping("/web/transfer")
    public String transfer(@RequestParam String senderUpiId,
                           @RequestParam String receiverUpiId,
                           @RequestParam BigDecimal amount,
                           @RequestParam(required = false) String remark,
                           RedirectAttributes redirectAttributes) {
        try {
            TransferRequest request = new TransferRequest();
            request.setSenderUpiId(senderUpiId);
            request.setReceiverUpiId(receiverUpiId);
            request.setAmount(amount);
            request.setRemark(remark);

            var response = transferService.transfer(request);
            if (response.getStatus().name().equals("SUCCESS")) {
                redirectAttributes.addFlashAttribute("success",
                        String.format("Transferred ₹%s from %s to %s (published to Pub/Sub)", amount, senderUpiId, receiverUpiId));
            } else {
                redirectAttributes.addFlashAttribute("error", "Transfer failed: Insufficient balance");
            }
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }
}
