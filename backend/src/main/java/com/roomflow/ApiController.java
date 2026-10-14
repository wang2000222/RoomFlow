package com.roomflow;

import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  final DemoAuth auth;
  final RoomService service;

  public ApiController(DemoAuth auth, RoomService service) {
    this.auth = auth;
    this.service = service;
  }

  @PostMapping("/demo/session")
  public Object login(@RequestBody Map<String, String> body) {
    return auth.login(body.getOrDefault("account", ""));
  }

  @GetMapping("/stores")
  public Object stores() {
    return service.stores();
  }

  @GetMapping("/rooms")
  public Object rooms() {
    return service.rooms();
  }

  @GetMapping("/bookings")
  public Object bookings(@RequestHeader(value = "Authorization", required = false) String token) {
    return service.bookings(auth.require(token));
  }

  @PostMapping("/bookings")
  public Object reserve(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestBody Map<String, Object> body) {
    DemoAuth.Actor actor = auth.require(token);
    try {
      return service.reservePackage(
          actor,
          Long.parseLong(String.valueOf(body.get("roomId"))),
          Instant.parse(String.valueOf(body.get("start"))).toEpochMilli(),
          Instant.parse(String.valueOf(body.get("end"))).toEpochMilli(),
          (String) body.get("requestKey"),
          (String) body.get("packageId"));
    } catch (ApiProblem e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ApiProblem(400, "INPUT_INVALID", "请检查房间、时间和预约标识");
    }
  }

  @PostMapping("/bookings/{id}/{action}")
  public Object action(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @PathVariable String action) {
    return service.action(auth.require(token), id, action);
  }

  @PatchMapping("/rooms/{id}")
  public Object room(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable long id,
      @RequestBody Map<String, String> body) {
    service.roomState(auth.require(token), id, body.get("state"));
    return Collections.singletonMap("ok", true);
  }

  @GetMapping("/audit")
  public Object audit(@RequestHeader(value = "Authorization", required = false) String token) {
    return service.audits(auth.require(token));
  }

  @PostMapping("/bookings/{id}/extensions")
  public Object extend(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @RequestBody Map<String, Object> b) {
    return service.extend(
        auth.require(token),
        id,
        OperationsService.integer(b, "minutes", 30, 720),
        String.valueOf(b.get("requestKey")));
  }

  @GetMapping("/bookings/{id}/extensions")
  public Object extensions(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return service.extensions(auth.require(token), id);
  }

  @PostMapping("/extensions/{id}/pay")
  public Object payExtension(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return service.payExtension(auth.require(token), id);
  }

  @PatchMapping("/bookings/{id}/remark")
  public Object remark(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @RequestBody Map<String, String> b) {
    service.remark(auth.require(token), id, b.get("remark"));
    return Collections.singletonMap("ok", true);
  }

  @ExceptionHandler(ApiProblem.class)
  public ResponseEntity<?> problem(ApiProblem e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", e.code);
    body.put("message", e.getMessage());
    return ResponseEntity.status(e.status).body(body);
  }
}
