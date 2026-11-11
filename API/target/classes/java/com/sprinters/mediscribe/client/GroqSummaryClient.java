package com.sprinters.mediscribe.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sprinters.mediscribe.config.AppProperties;
import com.sprinters.mediscribe.exception.AiServiceException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

@Component
@ConditionalOnProperty(name = "app.groq.mode", havingValue = "live", matchIfMissing = true)
public class GroqSummaryClient implements SummaryClient {

    private static final Logger log = LoggerFactory.getLogger(GroqSummaryClient.class);

    private final RestClient groqRestClient;
    private final AppProperties.Groq cfg;

    public GroqSummaryClient(RestClient groqRestClient, AppProperties props) {
        this.groqRestClient = groqRestClient;
        this.cfg = props.groq();
    }

    @Override
    public String modelName() {
        return cfg.model();
    }

    @Override
    public String summarize(String clinicalNote) {
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new AiServiceException("The AI service is not configured (GROQ_API_KEY is missing).");
        }
        String note = PromptFactory.clean(clinicalNote);
        if (note.isEmpty()) {
            throw new AiServiceException("The note is empty, so there is nothing to summarise.");
        }
        if (note.length() > PromptFactory.MAX_NOTE_CHARS) {
            throw new AiServiceException("The note is too long to summarise.");
        }

        Map<String, Object> body = Map.of(
                "model", cfg.model(),
                "temperature", cfg.temperature(),
                "max_tokens", cfg.maxTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", PromptFactory.SYSTEM_PROMPT),
                        Map.of("role", "user", "content", PromptFactory.userPrompt(note))));

        ChatResponse response;
        try {
            response = groqRestClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientResponseException e) {
            String reason = errorReason(e.getResponseBodyAsString());
            log.warn("Groq returned HTTP {}: {}", e.getStatusCode().value(), reason);
            throw new AiServiceException("The AI service answered with HTTP " + e.getStatusCode().value()
                    + (reason.isEmpty() ? "." : ": " + reason), e);
        } catch (ResourceAccessException e) {
            log.warn("Groq not reachable or timed out: {}", e.getMessage());
            throw new AiServiceException("The AI service timed out or could not be reached.", e);
        } catch (RuntimeException e) {
            log.warn("Unexpected error while calling Groq", e);
            throw new AiServiceException("The AI service returned an answer that could not be read.", e);
        }

        String text = extract(response);
        if (text == null || text.isBlank()) {
            throw new AiServiceException("The AI service returned an empty answer.");
        }
        return text.trim();
    }

    private static String extract(ChatResponse r) {
        if (r == null || r.choices() == null || r.choices().isEmpty()) {
            return null;
        }
        Choice first = r.choices().get(0);
        return first == null || first.message() == null ? null : first.message().content();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String content) {}

    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    static String errorReason(String body) {
        if (body == null) return "";
        Matcher m = MESSAGE.matcher(body);
        if (!m.find()) return "";
        String r = m.group(1).replace("\\\"", "\"");
        return r.length() > 200 ? r.substring(0, 200) : r;
    }
}
