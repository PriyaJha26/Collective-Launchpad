package com.collectivelaunchpad.onboarding;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.collectivelaunchpad.agentclient.AgentClient;
import com.collectivelaunchpad.config.SiteUrls;
import com.collectivelaunchpad.domain.Entities.SessionStatus;
import com.collectivelaunchpad.domain.Group;
import com.collectivelaunchpad.domain.OnboardingSession;
import com.collectivelaunchpad.domain.Repositories.OnboardingSessionRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.uploads.UploadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Runs the onboarding agent one admin turn at a time.
 *
 * The conversation is stored here, append-only. Photos are stored in history as references
 * ({"type":"image","source":{"type":"upload","path":...}}) and expanded to base64 only when a
 * turn is sent, so the database stays small and every replay sends identical bytes.
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    public static final Map<String, String> LANGUAGES = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>() {{
        put("hi", "Hindi");
        put("mr", "Marathi");
        put("ta", "Tamil");
        put("te", "Telugu");
        put("kn", "Kannada");
        put("ml", "Malayalam");
        put("bn", "Bengali");
        put("gu", "Gujarati");
        put("pa", "Punjabi");
        put("or", "Odia");
        put("ur", "Urdu");
        put("en", "English");
    }});

    /** The admin did something that is not possible right now; message is shown in the form. */
    public static class OnboardingException extends RuntimeException {
        public OnboardingException(String message) {
            super(message);
        }
    }

    public record NewSeller(String name, String language, String whatsapp, String notes, List<MultipartFile> photos) {}

    private final SellerRepository sellers;
    private final OnboardingSessionRepository sessions;
    private final UploadService uploads;
    private final AgentClient agent;
    private final ObjectMapper mapper;
    private final ExecutorService background;
    private final TransactionTemplate tx;
    private final SiteUrls urls;

    public OnboardingService(SellerRepository sellers, OnboardingSessionRepository sessions, UploadService uploads,
                             AgentClient agent, ObjectMapper mapper, ExecutorService backgroundExecutor,
                             TransactionTemplate tx, SiteUrls urls) {
        this.sellers = sellers;
        this.sessions = sessions;
        this.uploads = uploads;
        this.agent = agent;
        this.mapper = mapper;
        this.background = backgroundExecutor;
        this.tx = tx;
        this.urls = urls;
    }

    // ------------------------------------------------------------------ admin actions

    /** Creates a draft seller and a session, stores the photos and starts the first agent turn. */
    public OnboardingSession start(Group group, NewSeller in) {
        String name = in.name() == null ? "" : in.name().strip();
        if (name.isEmpty() || name.length() > 80) {
            throw new OnboardingException("Please enter the seller's name (up to 80 characters).");
        }
        String notes = in.notes() == null ? "" : in.notes().strip();
        if (notes.length() > 8000) {
            throw new OnboardingException("The notes are too long. Please keep them under 8,000 characters.");
        }
        boolean hasPhotos = in.photos() != null && in.photos().stream().anyMatch(f -> f != null && !f.isEmpty());
        if (notes.isEmpty() && !hasPhotos) {
            throw new OnboardingException("Add at least one photo, or type or dictate some notes about the seller.");
        }
        String language = LANGUAGES.containsKey(in.language()) ? in.language() : "hi";

        Seller seller = new Seller();
        seller.groupId = group.id;
        seller.name = name;
        seller.language = language;
        seller.whatsapp = clip(in.whatsapp(), 20);
        sellers.save(seller);

        List<UploadService.Photo> photos;
        try {
            photos = uploads.storeAll(seller.id, in.photos());
        } catch (RuntimeException e) {
            sellers.delete(seller);
            throw e;
        }

        ArrayNode input = mapper.createArrayNode();
        input.add(text("""
                Seller's name: %s
                Seller's language: %s
                WhatsApp number: %s
                Group: %s%s
                Shop addresses look like <address>.%s

                Notes from the group admin (typed or dictated, may be in any language):
                <notes>
                %s
                </notes>
                %s""".formatted(
                name, LANGUAGES.get(language), seller.whatsapp == null || seller.whatsapp.isBlank() ? "not given" : "given",
                group.name, group.location == null || group.location.isBlank() ? "" : " (" + group.location + ")",
                urls.domain(), notes.isEmpty() ? "(none)" : notes,
                photos.isEmpty() ? "No photos." : photos.size() + " photo(s) follow, each labelled with its id.")));
        addPhotos(input, photos);

        OnboardingSession session = new OnboardingSession();
        session.sellerId = seller.id;
        session.status = SessionStatus.WORKING;
        session.inflightInput = input;
        sessions.save(session);
        runInBackground(session.id);
        return session;
    }

    /** The admin answers the agent's question (or says "continue"), optionally with more photos. */
    public void answer(long sessionId, String answer, List<MultipartFile> photos) {
        OnboardingSession session = find(sessionId);
        if (session.isWorking()) {
            throw new OnboardingException("The assistant is still working on the last message. Please wait a moment.");
        }
        if (SessionStatus.FAILED.equals(session.status) && session.inflightInput != null) {
            throw new OnboardingException("The last message did not go through. Use \"Try again\" first.");
        }
        Seller seller = sellers.findById(session.sellerId).orElseThrow();
        if (!seller.isEditableByAgent()) {
            throw new OnboardingException("This shop is already published. Edit it directly instead.");
        }
        String text = answer == null ? "" : answer.strip();
        boolean hasPhotos = photos != null && photos.stream().anyMatch(f -> f != null && !f.isEmpty());
        if (text.isEmpty() && !hasPhotos) {
            throw new OnboardingException("Type an answer, or add photos.");
        }
        if (text.length() > 4000) {
            throw new OnboardingException("The answer is too long. Please keep it under 4,000 characters.");
        }
        List<UploadService.Photo> stored = uploads.storeAll(seller.id, photos);

        // Pending tool results first, then the admin's words as a separate text block.
        ArrayNode input = mapper.createArrayNode();
        session.pendingToolResults.forEach(input::add);
        input.add(text("Message from the group admin:\n" + (text.isEmpty() ? "(see the new photos)" : text)
                + (stored.isEmpty() ? "" : "\n\n" + stored.size() + " new photo(s) follow, each labelled with its id.")));
        addPhotos(input, stored);

        session.inflightInput = input;
        session.pendingToolResults = mapper.createArrayNode();
        session.pendingQuestion = null;
        session.lastError = null;
        session.status = SessionStatus.WORKING;
        sessions.save(session);
        runInBackground(session.id);
    }

    /** Resends the input of a turn that failed before anything was recorded. */
    public void retry(long sessionId) {
        OnboardingSession session = find(sessionId);
        if (!SessionStatus.FAILED.equals(session.status) || session.inflightInput == null) {
            throw new OnboardingException("There is nothing to retry.");
        }
        session.status = SessionStatus.WORKING;
        session.lastError = null;
        sessions.save(session);
        runInBackground(session.id);
    }

    public OnboardingSession find(long sessionId) {
        return sessions.findById(sessionId).orElseThrow(() -> new OnboardingException("Onboarding session not found."));
    }

    // ------------------------------------------------------------------ the turn

    private void runInBackground(long sessionId) {
        background.submit(() -> {
            try {
                runTurn(sessionId);
            } catch (Exception e) {
                log.error("Onboarding turn crashed for session {}", sessionId, e);
                markFailed(sessionId, "Unexpected error: " + e.getMessage());
            }
        });
    }

    void runTurn(long sessionId) {
        OnboardingSession session = find(sessionId);
        Seller seller = sellers.findById(session.sellerId).orElseThrow();
        ObjectNode request = mapper.createObjectNode();
        request.put("session_id", session.id);
        request.put("seller_id", seller.id);
        request.put("language", seller.language);
        try {
            request.set("history", expand(session.messages));
            request.set("input", expand(session.inflightInput));
        } catch (IOException e) {
            markFailed(sessionId, "A stored photo could not be read: " + e.getMessage());
            return;
        }

        JsonNode response;
        try {
            response = agent.onboardingTurn(request);
        } catch (AgentClient.AgentException e) {
            markFailed(sessionId, e.getMessage());
            return;
        }
        String status = response.path("status").asText();
        if (SessionStatus.FAILED.equals(status)) {
            markFailed(sessionId, response.path("error").asText("The assistant could not answer."));
            return;
        }

        tx.executeWithoutResult(t -> {
            OnboardingSession s = find(sessionId);
            ArrayNode messages = s.messages.isArray() ? (ArrayNode) s.messages.deepCopy() : mapper.createArrayNode();
            ObjectNode userMessage = mapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.set("content", s.inflightInput);
            messages.add(userMessage);
            response.path("appended").forEach(messages::add);
            s.messages = messages;
            s.pendingToolResults = response.path("pending_tool_results").isArray()
                    ? response.get("pending_tool_results") : mapper.createArrayNode();
            s.pendingQuestion = nullIfEmpty(response.path("question").asText(null));
            s.lastError = nullIfEmpty(response.path("error").asText(null));
            String note = nullIfEmpty(response.path("note_for_admin").asText(null));
            if (note != null) {
                s.noteForAdmin = note;
            }
            s.inflightInput = null;
            s.status = SessionStatus.SUBMITTED.equals(status) ? SessionStatus.SUBMITTED : SessionStatus.NEEDS_INPUT;
            sessions.save(s);
        });
        log.info("Onboarding session {} -> {} after {} round(s)", sessionId, status, response.path("rounds").asInt());
    }

    private void markFailed(long sessionId, String error) {
        tx.executeWithoutResult(t -> sessions.findById(sessionId).ifPresent(s -> {
            s.status = SessionStatus.FAILED;
            s.lastError = error;
            sessions.save(s);
        }));
    }

    // ------------------------------------------------------------------ message building

    private ObjectNode text(String value) {
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "text");
        block.put("text", value);
        return block;
    }

    private void addPhotos(ArrayNode input, List<UploadService.Photo> photos) {
        for (UploadService.Photo p : photos) {
            input.add(text("Photo id: " + p.id()));
            ObjectNode image = mapper.createObjectNode();
            image.put("type", "image");
            ObjectNode source = image.putObject("source");
            source.put("type", "upload");
            source.put("path", p.path());
            input.add(image);
        }
    }

    /** Copies messages/blocks, replacing upload references with base64 image data. */
    JsonNode expand(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return mapper.createArrayNode();
        }
        if (node.isObject()) {
            JsonNode source = node.get("source");
            if ("image".equals(node.path("type").asText()) && source != null && "upload".equals(source.path("type").asText())) {
                String path = source.path("path").asText();
                ObjectNode image = mapper.createObjectNode();
                image.put("type", "image");
                ObjectNode src = image.putObject("source");
                src.put("type", "base64");
                src.put("media_type", UploadService.mediaType(path));
                src.put("data", Base64.getEncoder().encodeToString(uploads.read(path)));
                return image;
            }
            ObjectNode copy = mapper.createObjectNode();
            var fields = node.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                copy.set(e.getKey(), expand(e.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            for (JsonNode child : node) {
                copy.add(expand(child));
            }
            return copy;
        }
        return node;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t.length() > max ? t.substring(0, max) : t;
    }

    private static String nullIfEmpty(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
