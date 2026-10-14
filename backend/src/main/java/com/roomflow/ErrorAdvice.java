package com.roomflow;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorAdvice {
  @ExceptionHandler(ApiProblem.class)
  public ResponseEntity<?> problem(ApiProblem e) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("code", e.code);
    b.put("message", e.getMessage());
    return ResponseEntity.status(e.status).body(b);
  }

  @ExceptionHandler({org.springframework.dao.EmptyResultDataAccessException.class})
  public ResponseEntity<?> missing(Exception e) {
    return ResponseEntity.status(404).body(Collections.singletonMap("message", "记录不存在"));
  }

  @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class})
  public ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409).body(Collections.singletonMap("message", "信息已存在或关联数据不允许此操作"));
  }
}
