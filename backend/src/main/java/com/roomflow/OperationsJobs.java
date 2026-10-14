package com.roomflow;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OperationsJobs {
  final RoomService service;
  final WorkflowService workflow;
  final JdbcTemplate db;
  final boolean enabled;

  public OperationsJobs(
      RoomService service,
      WorkflowService workflow,
      JdbcTemplate db,
      @Value("${roomflow.jobs-enabled}") boolean enabled) {
    this.service = service;
    this.workflow = workflow;
    this.db = db;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void scheduled() {
    if (enabled) run();
  }

  public Map<String, Object> run() {
    long now = System.currentTimeMillis();
    service.bookings(new DemoAuth.Actor("system", "OWNER", null));
    int failures = 0;
    for (Map<String, Object> b :
        db.queryForList("select * from booking where state in ('PAID','IN_USE')")) {
      String id = (String) b.get("id");
      long end = ((Number) b.get("ends_at")).longValue();
      if (end <= now) {
        try {
          if ("IN_USE".equals(b.get("state"))) {
            List<Map<String, Object>> commands =
                db.queryForList(
                    "select attempts from device_command where booking_id=? and action='CLOSE'",
                    id);
            if (!commands.isEmpty() && ((Number) commands.get(0).get("attempts")).intValue() >= 3)
              continue;
            service.action(new DemoAuth.Actor("system", "OWNER", null), id, "finish");
          } else service.action(new DemoAuth.Actor("system", "OWNER", null), id, "no-show");
        } catch (Exception e) {
          failures++;
        }
      } else if (end - now <= 10 * 60000) {
        workflow.notify(id, "ENDING_SOON", "预约将在10分钟内结束，请及时续时或结束使用");
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ranAt", now);
    result.put("failures", failures);
    return result;
  }
}
