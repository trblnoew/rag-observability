package com.portfolio.ragobservability.rag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class CitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[S([^\\]]+)]");

    public String validate(String answer, int sourceCount) {
        if (answer == null || answer.isBlank()) {
            throw new GenerationValidationException("Generation response must not be blank");
        }
        String normalized = answer.strip();
        Matcher matcher = CITATION.matcher(normalized);
        boolean foundCitation = false;
        while (matcher.find()) {
            foundCitation = true;
            int sourceNumber;
            try {
                sourceNumber = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new GenerationValidationException("Generation response contains an invalid source ID");
            }
            if (sourceNumber < 1 || sourceNumber > sourceCount) {
                throw new GenerationValidationException("Generation response contains an unknown source ID");
            }
        }
        if (!foundCitation) {
            throw new GenerationValidationException("Generation response must cite at least one source");
        }
        return normalized;
    }
}
