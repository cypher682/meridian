package com.meridian.api;

import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.meridian.core.BadRequestException;
import com.meridian.core.ConflictException;
import com.meridian.core.NotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({ NotFoundException.class, EmptyResultDataAccessException.class })
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(Exception error) {
        return Map.of("error", error instanceof NotFoundException ? error.getMessage() : "not found");
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(ConflictException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> dataConflict(DataIntegrityViolationException error) {
        return Map.of("error", "request conflicts with existing data");
    }

    @ExceptionHandler({
            BadRequestException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(Exception error) {
        String message = error instanceof BadRequestException
                ? error.getMessage()
                : "invalid request";
        return Map.of("error", message);
    }
}
