package com.tradinglabs.vidingest.core.ocr.mapper;

import com.tradinglabs.vidingest.api.ocr.OcrFrameGroup;
import com.tradinglabs.vidingest.api.ocr.OcrResultDto;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.ocr.domain.OcrResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entity → DTO mapper for {@link OcrResult}. Provides both a single-row mapping and the
 * grouped-by-frame variant returned by {@code GET /api/v1/videos/{id}/ocr}.
 */
@Component
public class OcrResultMapper {

    public OcrResultDto toDto(OcrResult row) {
        if (row == null) return null;
        VideoFrame frame = row.getFrame();
        return new OcrResultDto(
                row.getId() != null ? row.getId().toString() : null,
                frame != null && frame.getId() != null ? frame.getId().toString() : null,
                row.getText(),
                row.getConfidence(),
                row.getBbox(),
                row.getLanguage(),
                row.getCreatedAt() != null ? row.getCreatedAt().toString() : null
        );
    }

    /**
     * Group OCR results by their parent frame, preserving frame order by timestamp. The
     * grouping happens client-side of the JPA load — fine for the row counts we deal with
     * (capped at {@code vidingest.ocr.max-results-per-video}, default 10k).
     */
    public List<OcrFrameGroup> toFrameGroups(List<OcrResult> rows) {
        if (rows == null || rows.isEmpty()) return List.of();

        Map<UUID, FrameAccumulator> byFrameId = new LinkedHashMap<>();
        for (OcrResult row : rows) {
            VideoFrame frame = row.getFrame();
            if (frame == null || frame.getId() == null) continue;
            byFrameId.computeIfAbsent(frame.getId(), k -> new FrameAccumulator(frame))
                    .lines.add(toDto(row));
        }

        List<FrameAccumulator> sorted = new ArrayList<>(byFrameId.values());
        sorted.sort(Comparator.comparing(
                a -> a.frame.getTimestampSeconds() != null ? a.frame.getTimestampSeconds() : 0.0));

        return sorted.stream()
                .map(a -> new OcrFrameGroup(
                        a.frame.getId().toString(),
                        a.frame.getTimestampSeconds(),
                        a.frame.getFilePath(),
                        a.lines
                ))
                .toList();
    }

    /** Mutable accumulator used during grouping. */
    private static final class FrameAccumulator {
        final VideoFrame frame;
        final List<OcrResultDto> lines = new ArrayList<>();

        FrameAccumulator(VideoFrame frame) {
            this.frame = frame;
        }
    }
}
