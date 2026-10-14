package com.roomflow;

import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class WeChatAuthService {
  final JdbcTemplate db;
  final DemoAuth auth;
  final String appid, secret;
  final RestTemplate http;

  public WeChatAuthService(
      JdbcTemplate db,
      DemoAuth auth,
      @Value("${roomflow.wechat-appid}") String appid,
      @Value("${roomflow.wechat-secret}") String secret) {
    this.db = db;
    this.auth = auth;
    this.appid = appid;
    this.secret = secret;
    this.http =
        new RestTemplateBuilder()
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(5))
            .build();
  }

  boolean ready() {
    return !appid.isEmpty() && !secret.isEmpty();
  }

  public Object login(String code) {
    if (!ready()) throw new ApiProblem(503, "WECHAT_NOT_CONFIGURED", "尚未配置微信小程序登录");
    if (code == null || code.length() < 5 || code.length() > 200)
      throw new ApiProblem(400, "INPUT_INVALID", "微信登录凭证无效");
    Map<String, Object> response;
    try {
      String uri =
          UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/sns/jscode2session")
              .queryParam("appid", appid)
              .queryParam("secret", secret)
              .queryParam("js_code", code)
              .queryParam("grant_type", "authorization_code")
              .build()
              .encode()
              .toUriString();
      response =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readValue(http.getForObject(uri, String.class), Map.class);
    } catch (Exception e) {
      throw new ApiProblem(502, "WECHAT_UNAVAILABLE", "微信登录服务暂不可用");
    }
    if (response == null
        || !(response.get("openid") instanceof String)
        || ((String) response.get("openid")).isEmpty())
      throw new ApiProblem(401, "WECHAT_LOGIN_FAILED", "微信登录凭证已失效，请重新登录");
    return identity((String) response.get("openid"));
  }

  Object identity(String openid) {
    List<Map<String, Object>> employees =
        db.queryForList("select * from staff_account where wx_openid=? and enabled=true", openid);
    if (!employees.isEmpty()) {
      Map<String, Object> e = employees.get(0);
      return auth.issue(
          new DemoAuth.Actor(
              (String) e.get("id"),
              (String) e.get("role"),
              e.get("store_id") == null ? null : ((Number) e.get("store_id")).longValue()));
    }
    List<Map<String, Object>> customers =
        db.queryForList("select id from customer where openid=?", openid);
    String id;
    if (customers.isEmpty()) {
      id = UUID.randomUUID().toString();
      try {
        db.update("insert into customer(id,openid) values(?,?)", id, openid);
      } catch (org.springframework.dao.DuplicateKeyException e) {
        id = db.queryForObject("select id from customer where openid=?", String.class, openid);
      }
    } else id = (String) customers.get(0).get("id");
    return auth.issue(new DemoAuth.Actor(id, "USER", null));
  }

  public Object profile(DemoAuth.Actor a) {
    List<Map<String, Object>> rows =
        db.queryForList("select id,contact_name,contact_phone from customer where id=?", a.id);
    return rows.isEmpty() ? Collections.singletonMap("id", a.id) : rows.get(0);
  }

  public void contact(DemoAuth.Actor a, Map<String, String> b) {
    String name = b.getOrDefault("name", ""), phone = b.getOrDefault("phone", "");
    if (!"USER".equals(a.role)
        || name.length() > 80
        || (!phone.isEmpty() && !phone.matches("[+0-9 -]{6,30}")))
      throw new ApiProblem(400, "INPUT_INVALID", "联系人或电话号码无效");
    if (db.update(
            "update customer set contact_name=?,contact_phone=? where id=?", name, phone, a.id)
        == 0) throw new ApiProblem(409, "WECHAT_REQUIRED", "请使用正式微信登录后保存联系信息");
  }

  public void bind(DemoAuth.Actor a, String staffId, String customerId) {
    if (!"OWNER".equals(a.role)) throw new ApiProblem(403, "FORBIDDEN", "仅负责人可绑定员工微信");
    String openid =
        db.queryForObject("select openid from customer where id=?", String.class, customerId);
    if (db.update(
            "update staff_account set wx_openid=? where id=? and role='MANAGER'", openid, staffId)
        == 0) throw new ApiProblem(404, "NOT_FOUND", "员工不存在");
    auth.revoke(staffId);
    auth.revoke(customerId);
  }
}
