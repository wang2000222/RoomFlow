package com.roomflow;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OperationsController {
  final DemoAuth auth;
  final OperationsService ops;
  final StaffService staff;
  final WorkflowService workflow;
  final OperationsJobs jobs;
  final org.springframework.jdbc.core.JdbcTemplate db;
  final WeChatAuthService wechat;

  public OperationsController(
      DemoAuth auth,
      OperationsService ops,
      StaffService staff,
      WorkflowService workflow,
      OperationsJobs jobs,
      org.springframework.jdbc.core.JdbcTemplate db,
      WeChatAuthService wechat) {
    this.auth = auth;
    this.ops = ops;
    this.staff = staff;
    this.workflow = workflow;
    this.jobs = jobs;
    this.db = db;
    this.wechat = wechat;
  }

  @PostMapping("/staff/session")
  public Object login(@RequestBody Map<String, String> b) {
    return staff.login(b.get("username"), b.get("password"));
  }

  @PostMapping("/session/revoke")
  public Object logout(@RequestHeader(value = "Authorization", required = false) String t) {
    auth.revoke(auth.require(t).id);
    return Collections.singletonMap("ok", true);
  }

  @GetMapping("/capabilities")
  public Object capabilities() {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("demo", auth.isDemo());
    r.put("realWechatLogin", wechat.ready());
    r.put("realPayment", false);
    r.put("realDevices", false);
    return r;
  }

  @GetMapping("/configuration")
  public Object config(@RequestHeader(value = "Authorization", required = false) String t) {
    ops.owner(auth.require(t));
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("stores", db.queryForList("select * from store order by id"));
    r.put("rooms", db.queryForList("select * from room order by id"));
    r.put("packages", db.queryForList("select * from price_package order by room_id,minutes"));
    return r;
  }

  @PostMapping("/stores")
  public Object newStore(
      @RequestHeader(value = "Authorization", required = false) String t,
      @RequestBody Map<String, Object> b) {
    return ops.saveStore(auth.require(t), null, b);
  }

  @PutMapping("/stores/{id}")
  public Object store(
      @RequestHeader(value = "Authorization", required = false) String t,
      @PathVariable long id,
      @RequestBody Map<String, Object> b) {
    return ops.saveStore(auth.require(t), id, b);
  }

  @PostMapping("/rooms")
  public Object newRoom(
      @RequestHeader(value = "Authorization", required = false) String t,
      @RequestBody Map<String, Object> b) {
    return ops.saveRoom(auth.require(t), null, b);
  }

  @PutMapping("/rooms/{id}")
  public Object room(
      @RequestHeader(value = "Authorization", required = false) String t,
      @PathVariable long id,
      @RequestBody Map<String, Object> b) {
    return ops.saveRoom(auth.require(t), id, b);
  }

  @GetMapping("/rooms/{id}/packages")
  public Object packs(@PathVariable long id) {
    return ops.packages(id);
  }

  @PostMapping("/packages")
  public Object pack(
      @RequestHeader(value = "Authorization", required = false) String t,
      @RequestBody Map<String, Object> b) {
    return ops.savePackage(auth.require(t), b);
  }

  @GetMapping("/staff")
  public Object staff(@RequestHeader(value = "Authorization", required = false) String t) {
    return staff.list(auth.require(t));
  }

  @PostMapping("/staff")
  public Object saveStaff(
      @RequestHeader(value = "Authorization", required = false) String t,
      @RequestBody Map<String, Object> b) {
    return staff.save(auth.require(t), b);
  }

  @GetMapping("/finance")
  public Object finance(@RequestHeader(value = "Authorization", required = false) String t) {
    return ops.overview(auth.require(t));
  }

  @GetMapping("/notifications")
  public Object notices(@RequestHeader(value = "Authorization", required = false) String t) {
    return ops.notifications(auth.require(t));
  }

  @PostMapping("/refunds/{id}/retry")
  public Object retry(
      @RequestHeader(value = "Authorization", required = false) String t, @PathVariable String id) {
    return workflow.retryRefund(auth.require(t), id);
  }

  @PostMapping("/reconcile")
  public Object reconcile(@RequestHeader(value = "Authorization", required = false) String t) {
    workflow.reconcile(auth.require(t));
    return Collections.singletonMap("ok", true);
  }

  @PostMapping("/jobs/run")
  public Object jobs(@RequestHeader(value = "Authorization", required = false) String t) {
    ops.owner(auth.require(t));
    return jobs.run();
  }

  @PostMapping("/simulation/fault")
  public Object fault(
      @RequestHeader(value = "Authorization", required = false) String t,
      @RequestBody Map<String, Object> b) {
    workflow.fault(
        auth.require(t), String.valueOf(b.get("key")), OperationsService.integer(b, "count", 0, 5));
    return Collections.singletonMap("ok", true);
  }
}
