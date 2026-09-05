package com.portfolio.ragobservability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagObservabilityApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(RagObservabilityApplication.class, args);
        if (context.getEnvironment().matchesProfiles("evaluation")) {
            int code;
            try {
                code = com.portfolio.ragobservability.evaluation.EvaluationCli.execute(context);
            } finally {
                context.close();
            }
            System.exit(code);
        }
    }
}
