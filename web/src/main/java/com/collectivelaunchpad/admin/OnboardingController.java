package com.collectivelaunchpad.admin;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.collectivelaunchpad.domain.Group;
import com.collectivelaunchpad.domain.OnboardingSession;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.onboarding.OnboardingService;
import com.collectivelaunchpad.onboarding.OnboardingService.OnboardingException;
import com.collectivelaunchpad.uploads.UploadService;
import com.fasterxml.jackson.databind.JsonNode;

/** "Add a seller" and the conversation with the onboarding agent. */
@Controller
public class OnboardingController {

    private final OnboardingService onboarding;
    private final SellerRepository sellers;
    private final ProductRepository products;
    private final GroupRepository groups;

    public OnboardingController(OnboardingService onboarding, SellerRepository sellers, ProductRepository products,
                                GroupRepository groups) {
        this.onboarding = onboarding;
        this.sellers = sellers;
        this.products = products;
        this.groups = groups;
    }

    @GetMapping("/admin/sellers/new")
    public String form(Model model) {
        model.addAttribute("languages", OnboardingService.LANGUAGES);
        model.addAttribute("maxPhotos", UploadService.MAX_FILES_PER_REQUEST);
        return "admin/seller-new";
    }

    @PostMapping("/admin/sellers/new")
    public String create(@RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "hi") String language,
                         @RequestParam(defaultValue = "") String whatsapp,
                         @RequestParam(defaultValue = "") String notes,
                         @RequestParam(name = "photos", required = false) List<MultipartFile> photos,
                         Model model) {
        try {
            // Load the group here rather than as a @ModelAttribute parameter: that would bind the
            // form's "name" field onto the group.
            Group group = groups.findFirstByOrderByIdAsc().orElseThrow();
            OnboardingSession s = onboarding.start(group, new OnboardingService.NewSeller(name, language, whatsapp, notes, photos));
            return "redirect:/admin/onboarding/" + s.id;
        } catch (OnboardingException | UploadService.UploadException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("form", new String[] {name, language, whatsapp, notes});
            return form(model);
        }
    }

    @GetMapping("/admin/onboarding/{id}")
    public String session(@PathVariable long id, Model model) {
        OnboardingSession s = onboarding.find(id);
        var seller = sellers.findById(s.sellerId).orElseThrow();
        model.addAttribute("onb", s);
        model.addAttribute("seller", seller);
        model.addAttribute("products", products.findBySellerIdOrderBySortOrderAscIdAsc(seller.id));
        model.addAttribute("exchanges", exchanges(s.messages));
        model.addAttribute("maxPhotos", UploadService.MAX_FILES_PER_REQUEST);
        return "admin/onboarding";
    }

    @PostMapping("/admin/onboarding/{id}/answer")
    public String answer(@PathVariable long id, @RequestParam(defaultValue = "") String answer,
                         @RequestParam(name = "photos", required = false) List<MultipartFile> photos,
                         RedirectAttributes flash) {
        try {
            onboarding.answer(id, answer, photos);
        } catch (OnboardingException | UploadService.UploadException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/onboarding/" + id;
    }

    @PostMapping("/admin/onboarding/{id}/retry")
    public String retry(@PathVariable long id, RedirectAttributes flash) {
        try {
            onboarding.retry(id);
        } catch (OnboardingException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/onboarding/" + id;
    }

    /** A readable transcript: admin messages and the questions the agent asked. */
    public record Exchange(String who, String text) {}

    static List<Exchange> exchanges(JsonNode messages) {
        List<Exchange> out = new java.util.ArrayList<>();
        if (messages == null || !messages.isArray()) {
            return out;
        }
        for (JsonNode m : messages) {
            boolean user = "user".equals(m.path("role").asText());
            for (JsonNode b : m.path("content")) {
                String type = b.path("type").asText();
                if (user && "text".equals(type)) {
                    String t = b.path("text").asText();
                    if (t.startsWith("Photo id:")) {
                        continue;
                    }
                    int notes = t.indexOf("<notes>");
                    int end = t.indexOf("</notes>");
                    if (notes >= 0 && end > notes) {
                        t = t.substring(notes + "<notes>".length(), end).strip(); // first message: just the notes
                    }
                    out.add(new Exchange("You", t.replaceFirst("^Message from the group admin:\\n", "")));
                } else if (!user && "tool_use".equals(type) && "ask_seller".equals(b.path("name").asText())) {
                    out.add(new Exchange("Assistant", b.path("input").path("question").asText()));
                } else if (!user && "text".equals(type) && !b.path("text").asText().isBlank()) {
                    out.add(new Exchange("Assistant", b.path("text").asText()));
                }
            }
        }
        return out;
    }
}
