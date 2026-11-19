package com.collectivelaunchpad.i18n;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.collectivelaunchpad.agentclient.AgentClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Translates texts into the readers' languages through the agent (Claude Haiku). */
@Service
public class TranslationService {

    private final AgentClient agent;
    private final ObjectMapper mapper;

    public TranslationService(AgentClient agent, ObjectMapper mapper) {
        this.agent = agent;
        this.mapper = mapper;
    }

    /**
     * Returns {lang: {field: text}} for every target language. Throws AgentClient.AgentException
     * when the agent or Claude is not available.
     */
    public ObjectNode translate(Map<String, String> fields, Collection<String> targets) {
        Map<String, String> clean = new LinkedHashMap<>();
        fields.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                clean.put(k, v);
            }
        });
        ObjectNode out = mapper.createObjectNode();
        var wanted = targets.stream().filter(Languages::isSupported).distinct().toList();
        if (clean.isEmpty() || wanted.isEmpty()) {
            return out;
        }
        ObjectNode req = mapper.createObjectNode();
        ObjectNode f = req.putObject("fields");
        clean.forEach(f::put);
        var t = req.putArray("targets");
        wanted.forEach(t::add);
        JsonNode res = agent.translateTo(req);
        JsonNode results = res == null ? mapper.createObjectNode() : res.path("results");
        for (String lang : wanted) {
            JsonNode r = results.path(lang);
            if (r.isObject()) {
                out.set(lang, r);
            }
        }
        return out;
    }

    /** One text into one language. */
    public String translate(String text, String target) {
        ObjectNode r = translate(Map.of("text", text), java.util.List.of(target));
        return r.path(target).path("text").asText(text);
    }

    /** {lang: {"text": t}} -> {lang: t}, merged into existing translations. */
    public ObjectNode flatten(ObjectNode results, JsonNode existing) {
        ObjectNode out = existing != null && existing.isObject() ? ((ObjectNode) existing).deepCopy() : mapper.createObjectNode();
        results.fields().forEachRemaining(e -> {
            JsonNode t = e.getValue().path("text");
            if (t.isTextual()) {
                out.put(e.getKey(), t.asText());
            }
        });
        return out;
    }
}
