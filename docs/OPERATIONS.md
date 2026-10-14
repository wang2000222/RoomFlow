# 配置、部署与验收

## 模式与边界

默认 DEMO 模式仅监听本机。演示登录、收款、退款及设备确认不产生真实交易。将 ROOMFLOW_DEMO 设为 false 会禁用所有模拟资金与设备操作；工作人员可使用密码登录。正式微信登录代码通过服务端 code2Session 接口交换身份，需本地配置 WECHAT_APPID / WECHAT_SECRET；未配置时拒绝登录。

微信支付回调验签、真实退款对账与具体门锁供应商协议仍待接入；目前不能以生产完整商用版交付。接口 readiness 不会将这些功能标为真实已接入。

## 配置

.env.example 为变量清单，不会自动读取。使用系统环境变量或部署工具注入，禁止提交 .env 和私钥。OWNER_USERNAME / OWNER_PASSWORD 用于首次初始化负责人，密码至少12位，之后不会覆盖已有账号。负责人可在 PC 员工页面创建、停用、改店或重置管理员密码；这些操作撤销当前服务实例中的该账号会话。当前会话存放于内存，服务重启要求重新登录，横向多实例部署尚需共享会话实现。

微信用户默认仅有 USER 权限；管理员必须由负责人绑定已登录用户的 customerId，不能由小程序自行传入角色升级。联系手机号由用户自行填写，尚无微信手机号授权或短信验证，不应作为已验证身份使用。

## 数据库

Flyway V1—V4 负责数据库演进，保留所有版本。原 0.1 H2 演示库自动 baseline 到 V1 后执行后续迁移；不要将应用连接到无关已有数据库。MySQL 使用独立空库，并配置 DB_URL / DB_USERNAME / DB_PASSWORD。已提供 MySQL 驱动；当前实际验收使用 H2，MySQL 验收未完成。

## 备份与恢复

H2 可由负责人调用 POST /api/ops/backup 创建 .local/backups 下的 SQL 快照；含业务和账号数据，不公开下载。将备份另存到有访问控制的存储。MySQL 使用 ops/backup-mysql.ps1，密码在终端提示中输入，不放命令行。

恢复时先停止写入，使用独立空数据库导入备份，再运行权限、订单、流水和设备状态检查。不要直接覆盖现有数据库。本次测试将H2备份恢复到独立内存库并核对数据；MySQL和生产环境恢复演练尚未执行。

## 运行与监控

GET /health 检查服务与数据库连通性；ops/check-health.ps1 可用于外部监控调用。PC 账务设备页面列出设备 FAILED 与退款 FAILED 状态。30秒周期任务处理待付款过期、到时结束、未到店与站内提醒，自动关设备最多尝试3次，之后保留失败记录供人工处理。

提醒目前为站内记录，未发送短信或微信订阅消息。自动任务仅适用于单实例；多实例需分布式调度或任务抢占。设备和退款模拟对账需人工触发或重试。不开启演示模式时定时任务不会调用真实设备。

## 部署资源

Dockerfile、compose.yml 和 Nginx HTTPS 模板已提供；compose 只绑定本机端口。Docker 构建、HTTPS 证书、公网域名和微信审核尚未验收。不要将默认演示管理员入口公开部署。

## 外部服务接入依据

- [微信支付官方 Java SDK](https://github.com/wechatpay-apiv3/wechatpay-java)：未来真实支付应使用官方 SDK 处理请求签名、回调验签与解密，不能相信前端成功回调。
- [微信小程序登录官方接口](https://developers.weixin.qq.com/miniprogram/dev/api-backend/open-api/login/auth.code2Session.html)：AppSecret 与 session_key 留在服务端。

未持有 AppID、商户配置或设备资料时，只验收模拟业务流程，不宣称已真实支付、退款或开门。
