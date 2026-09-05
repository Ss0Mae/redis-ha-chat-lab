package com.seongmin.redislab.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiErrors {
	@ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.MethodArgumentNotValidException.class})
	public ResponseEntity<Map<String, String>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage())); }
	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", String.valueOf(e.getMessage()))); }
	@ExceptionHandler(NoSuchElementException.class)
	public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) { return ResponseEntity.status(404).body(Map.of("error", String.valueOf(e.getMessage()))); }
}
