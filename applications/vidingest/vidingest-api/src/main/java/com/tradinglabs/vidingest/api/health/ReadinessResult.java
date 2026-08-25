package com.tradinglabs.vidingest.api.health;

import java.util.Map;

public record ReadinessResult(boolean ready, Map<String, String> checks) {
}

