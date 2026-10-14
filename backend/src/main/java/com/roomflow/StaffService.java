package com.roomflow;

import static com.roomflow.DemoAuth.Actor;

import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PostConstruct;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffService {
  final JdbcTemplate db;
  final DemoAuth auth;
  final String ownerName, ownerPassword;
  final Map<String, long[]> limits = new ConcurrentHashMap<>();

  public StaffService(
      JdbcTemplate db,
      DemoAuth auth,
      @Value("${roomflow.owner-username}") String name,
      @Value("${roomflow.owner-password}") String password) {
    this.db = db;
    this.auth = auth;
    ownerName = name;
    ownerPassword = password;
  }

  @PostConstruct
  void bootstrap() {
    if (!ownerName.isEmpty()
        && !ownerPassword.isEmpty()
        && db.queryForObject("select count(*) from staff_account where role='OWNER'", Integer.class)
            == 0) {
      if (ownerPassword.length() < 12) throw new IllegalStateException("OWNER_PASSWORD 至少12位");
      db.update(
          "insert into staff_account(id,username,password_hash,role,store_id,enabled) values(?,?,?,'OWNER',null,true)",
          UUID.randomUUID().toString(),
          ownerName,
          hash(ownerPassword));
    }
  }

  public Object login(String username, String password) {
    if (username == null || password == null || username.length() > 80 || password.length() > 200)
      throw new ApiProblem(401, "LOGIN_FAILED", "账号或密码错误");
    long[] attempts =
        limits.computeIfAbsent(username, k -> new long[] {System.currentTimeMillis(), 0});
    synchronized (attempts) {
      if (System.currentTimeMillis() - attempts[0] > 60000) {
        attempts[0] = System.currentTimeMillis();
        attempts[1] = 0;
      }
      if (++attempts[1] > 5) throw new ApiProblem(429, "RATE_LIMIT", "请稍后再试");
    }
    List<Map<String, Object>> rows =
        db.queryForList("select * from staff_account where username=? and enabled=true", username);
    if (rows.isEmpty() || !check(password, (String) rows.get(0).get("password_hash")))
      throw new ApiProblem(401, "LOGIN_FAILED", "账号或密码错误");
    Map<String, Object> r = rows.get(0);
    return auth.issue(
        new Actor(
            (String) r.get("id"),
            (String) r.get("role"),
            r.get("store_id") == null ? null : ((Number) r.get("store_id")).longValue()));
  }

  void owner(Actor a) {
    if (!"OWNER".equals(a.role)) throw new ApiProblem(403, "FORBIDDEN", "仅负责人可管理员工");
  }

  public Object list(Actor a) {
    owner(a);
    return db.queryForList(
        "select id,username,role,store_id,enabled from staff_account order by username");
  }

  @Transactional
  public Object save(Actor a, Map<String, Object> b) {
    owner(a);
    String name = OperationsService.text(b, "username", 80),
        role = OperationsService.text(b, "role", 20);
    if (!"MANAGER".equals(role)) throw new ApiProblem(400, "ROLE_INVALID", "员工角色仅允许门店管理员");
    long store = OperationsService.integer(b, "storeId", 1, Integer.MAX_VALUE);
    if (db.queryForObject("select count(*) from store where id=?", Integer.class, store) == 0)
      throw new ApiProblem(404, "NOT_FOUND", "门店不存在");
    boolean enabled = !Boolean.FALSE.equals(b.get("enabled"));
    String id =
        b.get("id") == null ? UUID.randomUUID().toString() : OperationsService.text(b, "id", 80);
    String password = String.valueOf(b.getOrDefault("password", ""));
    List<Map<String, Object>> old =
        db.queryForList("select * from staff_account where id=? for update", id);
    if (!old.isEmpty() && "OWNER".equals(old.get(0).get("role")))
      throw new ApiProblem(403, "FORBIDDEN", "不能通过员工接口修改负责人");
    if ((old.isEmpty() || !password.isEmpty())
        && (password.length() < 12 || password.length() > 200))
      throw new ApiProblem(400, "PASSWORD_INVALID", "密码需要12至200位");
    String encoded = password.isEmpty() ? (String) old.get(0).get("password_hash") : hash(password);
    if (old.isEmpty())
      db.update(
          "insert into staff_account(id,username,password_hash,role,store_id,enabled) values(?,?,?,?,?,?)",
          id,
          name,
          encoded,
          role,
          store,
          enabled);
    else
      db.update(
          "update staff_account set username=?,password_hash=?,role=?,store_id=?,enabled=? where id=?",
          name,
          encoded,
          role,
          store,
          enabled,
          id);
    auth.revoke(id);
    db.update(
        "insert into audit values(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        a.id,
        "STAFF_CONFIG",
        id,
        System.currentTimeMillis());
    return Collections.singletonMap("id", id);
  }

  static String derive(String password, byte[] salt) {
    try {
      return Base64.getEncoder()
          .encodeToString(
              SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                  .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 210000, 256))
                  .getEncoded());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String hash(String password) {
    byte[] salt = new byte[24];
    new SecureRandom().nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt) + ":" + derive(password, salt);
  }

  static boolean check(String password, String hash) {
    String[] parts = hash.split(":");
    return parts.length == 2
        && MessageDigest.isEqual(
            derive(password, Base64.getDecoder().decode(parts[0]))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            parts[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
