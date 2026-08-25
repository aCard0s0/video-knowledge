package com.tradinglabs.vidingest.search.service.embedding;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

public class EmbeddingsBaseUrlPresentCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String baseUrl = env != null ? env.getProperty("vidingest.search.embeddings.base-url") : null;
        return StringUtils.hasText(baseUrl);
    }
}

