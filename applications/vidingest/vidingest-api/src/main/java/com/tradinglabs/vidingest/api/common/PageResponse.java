package com.tradinglabs.vidingest.api.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Paging contract for VidIngest REST APIs.
 *
 * <p>Note: VidIngest currently uses a simple custom paging shape ({@code items/page/size/total}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total
) {
    public PageResponse {
        if (items == null) {
            items = List.of();
        }
    }
}

