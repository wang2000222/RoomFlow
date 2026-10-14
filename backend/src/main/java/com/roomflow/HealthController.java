package com.roomflow;

import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  final JdbcTemplate db;
  final DemoAuth auth;

  public HealthController(JdbcTemplate db, DemoAuth auth) {
    this.db = db;
    this.auth = auth;
  }

  @GetMapping("/health")
  public Object health() {
    db.queryForObject("select 1", Integer.class);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("status", "UP");
    r.put("mode", auth.isDemo() ? "DEMO" : "INTEGRATION");
    return r;
  }

  @PostMapping("/api/ops/backup")
  public Object backup(@RequestHeader(value = "Authorization", required = false) String t)
      throws Exception {
    if (!"OWNER".equals(auth.require(t).role)) throw new ApiProblem(403, "FORBIDDEN", "仅负责人可创建备份");
    String database =
        db.execute(
            (org.springframework.jdbc.core.ConnectionCallback<String>)
                c -> c.getMetaData().getDatabaseProductName());
    if (!"H2".equals(database))
      throw new ApiProblem(409, "MYSQL_BACKUP_REQUIRED", "MySQL 请使用提供的备份脚本");
    Path root = Paths.get(".local", "backups").toAbsolutePath().normalize();
    Files.createDirectories(root);
    String filename =
        "roomflow-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString() + ".sql";
    Path path = root.resolve(filename).normalize();
    if (!path.startsWith(root)) throw new IllegalStateException("备份路径异常");
    db.execute("SCRIPT TO '" + path.toString().replace("'", "''") + "'");
    return Collections.singletonMap("filename", filename);
  }
}
