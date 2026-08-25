package com.tradinglabs.vidingest.commons;

import com.tradinglabs.vidingest.pipeline.exceptions.RunNotFoundException;
import com.tradinglabs.vidingest.pipeline.exceptions.RunItemNotFoundException;
import com.tradinglabs.vidingest.pipeline.exceptions.RunRetryNotAllowedException;
import com.tradinglabs.vidingest.search.exceptions.SemanticSearchUnavailableException;
import com.tradinglabs.vidingest.videos.exceptions.DuplicateVideoException;
import com.tradinglabs.vidingest.videos.exceptions.LocalStorageException;
import com.tradinglabs.vidingest.videos.exceptions.VideoArtifactNotFoundException;
import com.tradinglabs.vidingest.videos.exceptions.VideoNotFoundException;
import com.tradinglabs.vidingest.youtube.exceptions.YoutubeChannelNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class VidingestApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        pd.setDetail("Request validation failed.");
        pd.setInstance(instanceUri(request));

        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        pd.setProperty("fields", fields);
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), request, e);
    }

    @ExceptionHandler(VideoNotFoundException.class)
    ProblemDetail handleVideoNotFound(VideoNotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), request, e);
    }

    @ExceptionHandler(VideoArtifactNotFoundException.class)
    ProblemDetail handleVideoArtifactNotFound(VideoArtifactNotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), request, e);
    }

    @ExceptionHandler(RunNotFoundException.class)
    ProblemDetail handlePipelineRunNotFound(RunNotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), request, e);
    }

    @ExceptionHandler(RunItemNotFoundException.class)
    ProblemDetail handlePipelineRunItemNotFound(RunItemNotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), request, e);
    }

    @ExceptionHandler(YoutubeChannelNotFoundException.class)
    ProblemDetail handleYoutubeChannelNotFound(YoutubeChannelNotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), request, e);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleConflict(IllegalStateException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), request, e);
    }

    @ExceptionHandler(RunRetryNotAllowedException.class)
    ProblemDetail handlePipelineRetryNotAllowed(RunRetryNotAllowedException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), request, e);
    }

    @ExceptionHandler(DuplicateVideoException.class)
    ProblemDetail handleDuplicateVideo(DuplicateVideoException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), request, e);
    }

    @ExceptionHandler(SemanticSearchUnavailableException.class)
    ProblemDetail handleSearchUnavailable(SemanticSearchUnavailableException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), request, e);
    }

    @ExceptionHandler(LocalStorageException.class)
    ProblemDetail handleLocalStorage(LocalStorageException e, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", e.getMessage(), request, e);
    }

    @ExceptionHandler(IOException.class)
    ProblemDetail handleIo(IOException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_GATEWAY, "Upstream failure", e.getMessage(), request, e);
    }

    @ExceptionHandler(ErrorResponseException.class)
    ProblemDetail handleErrorResponse(ErrorResponseException e, HttpServletRequest request) {
        ProblemDetail pd = e.getBody();
        if (pd.getInstance() == null) {
            pd.setInstance(instanceUri(request));
        }
        return pd;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), request, e);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", e.getMessage(), request, e);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request, Exception e) {
        if (status.is5xxServerError()) {
            log.error("REST error {} {}: {}", status.value(), title, detail, e);
        } else if (status.is4xxClientError()) {
            // 4xx is part of the normal contract; don't pollute logs with stack traces.
            log.warn("REST {} {}: {}", status.value(), title, detail);
        } else {
            log.info("REST {} {}: {}", status.value(), title, detail);
        }
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setTitle(title);
        pd.setDetail(detail != null ? detail : status.getReasonPhrase());
        pd.setInstance(instanceUri(request));
        return pd;
    }

    private URI instanceUri(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null ? URI.create(uri) : null;
    }
}

