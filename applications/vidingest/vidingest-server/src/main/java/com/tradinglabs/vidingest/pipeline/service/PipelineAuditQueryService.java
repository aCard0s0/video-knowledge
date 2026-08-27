package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.pipeline.RunItemAuditEvent;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEvent;
import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEventType;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.exceptions.RunItemNotFoundException;
import com.tradinglabs.vidingest.pipeline.exceptions.RunNotFoundException;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemEventRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PipelineAuditQueryService {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;

    private final PipelineRunItemEventRepository eventRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineRunItemRepository pipelineRunItemRepository;
    private final PipelineAuditMapper mapper;

    public record AuditFilters(
            UUID runId,
            String eventType,
            String status,
            String phase,
            String errorCode,
            OffsetDateTime fromDate,
            OffsetDateTime toDate
    ) {}

    @Transactional(readOnly = true)
    public PageResponse<RunItemAuditEvent> listItemEvents(UUID runId, UUID itemId, Integer pageParam, Integer sizeParam) {
        if (!pipelineRunRepository.existsById(runId)) {
            throw new RunNotFoundException(runId);
        }
        PipelineRunItem item = pipelineRunItemRepository.findByIdAndPipelineRun_Id(itemId, runId)
                .orElseThrow(() -> new RunItemNotFoundException(runId, itemId));

        Pageable pageable = buildPageable(pageParam, sizeParam);
        Page<PipelineRunItemEvent> page = eventRepository.findByRunItemIdOrderByOccurredAtAscIdAsc(item.getId(), pageable);
        return toPageResponse(page);
    }

    @Transactional(readOnly = true)
    public PageResponse<RunItemAuditEvent> listRunEvents(UUID runId, Integer pageParam, Integer sizeParam) {
        if (!pipelineRunRepository.existsById(runId)) {
            throw new RunNotFoundException(runId);
        }
        Pageable pageable = buildPageable(pageParam, sizeParam);
        Page<PipelineRunItemEvent> page = eventRepository.findByPipelineRunIdOrderByOccurredAtAscIdAsc(runId, pageable);
        return toPageResponse(page);
    }

    @Transactional(readOnly = true)
    public PageResponse<RunItemAuditEvent> listEvents(AuditFilters filters, Integer pageParam, Integer sizeParam) {
        Specification<PipelineRunItemEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filters.runId() != null) {
                predicates.add(cb.equal(root.get("pipelineRunId"), filters.runId()));
            }
            if (filters.eventType() != null && !filters.eventType().isBlank()) {
                try {
                    PipelineRunItemEventType typed = PipelineRunItemEventType.valueOf(filters.eventType().trim());
                    predicates.add(cb.equal(root.get("eventType"), typed));
                } catch (IllegalArgumentException ex) {
                    predicates.add(cb.disjunction());
                }
            }
            if (filters.status() != null && !filters.status().isBlank()) {
                try {
                    RunStatus typed = RunStatus.valueOf(filters.status().trim().toUpperCase());
                    predicates.add(cb.equal(root.get("status"), typed));
                } catch (IllegalArgumentException ex) {
                    predicates.add(cb.disjunction());
                }
            }
            if (filters.phase() != null && !filters.phase().isBlank()) {
                try {
                    PipelineRunPhase typed = PipelineRunPhase.valueOf(filters.phase().trim().toUpperCase());
                    predicates.add(cb.equal(root.get("phase"), typed));
                } catch (IllegalArgumentException ex) {
                    predicates.add(cb.disjunction());
                }
            }
            if (filters.errorCode() != null && !filters.errorCode().isBlank()) {
                try {
                    PipelineErrorCode typed = PipelineErrorCode.valueOf(filters.errorCode().trim().toUpperCase());
                    predicates.add(cb.equal(root.get("errorCode"), typed));
                } catch (IllegalArgumentException ex) {
                    predicates.add(cb.disjunction());
                }
            }
            if (filters.fromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filters.fromDate()));
            }
            if (filters.toDate() != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), filters.toDate()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = buildPageable(
                pageParam,
                sizeParam,
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))
        );
        Page<PipelineRunItemEvent> page = eventRepository.findAll(spec, pageable);
        return toPageResponse(page);
    }

    private Pageable buildPageable(Integer pageParam, Integer sizeParam) {
        return buildPageable(pageParam, sizeParam, Sort.unsorted());
    }

    private Pageable buildPageable(Integer pageParam, Integer sizeParam, Sort sort) {
        int page = pageParam != null && pageParam >= 0 ? pageParam : 0;
        int size = sizeParam != null && sizeParam > 0 ? Math.min(sizeParam, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        return PageRequest.of(page, size, sort);
    }

    private PageResponse<RunItemAuditEvent> toPageResponse(Page<PipelineRunItemEvent> page) {
        List<RunItemAuditEvent> items = page.getContent().stream().map(mapper::toDto).toList();
        return new PageResponse<>(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
