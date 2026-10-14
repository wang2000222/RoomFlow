package com.roomflow;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WeChatController {
  final WeChatAuthService wechat;
  final DemoAuth auth;

  public WeChatController(WeChatAuthService wechat, DemoAuth auth) {
    this.wechat = wechat;
    this.auth = auth;
  }

  @PostMapping("/wechat/session")
  public Object login(@RequestBody Map<String, String> b) {
    return wechat.login(b.get("code"));
  }

  @GetMapping("/profile")
  public Object profile(@RequestHeader(value = "Authorization", required = false) String t) {
    return wechat.profile(auth.require(t));
  }

  @PutMapping("/profile")
  public Object contact(
      @RequestHeader(value = "Authorization", required = false) String t,
      @RequestBody Map<String, String> b) {
    wechat.contact(auth.require(t), b);
    return Collections.singletonMap("ok", true);
  }

  @PostMapping("/staff/{id}/wechat")
  public Object bind(
      @RequestHeader(value = "Authorization", required = false) String t,
      @PathVariable String id,
      @RequestBody Map<String, String> b) {
    wechat.bind(auth.require(t), id, b.get("customerId"));
    return Collections.singletonMap("ok", true);
  }
}
