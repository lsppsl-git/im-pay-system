# IM-Pay-System 即时通讯与支付系统

基于 Spring Boot 3 的即时通讯与在线支付后端，实现用户聊天、红包收发、钱包转账、文件传输等功能，重点实践高并发场景下的并发控制与事务一致性。

## 技术栈

| 分类 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2.5 |
| 持久层 | MyBatis-Plus 3.5.6 |
| 数据库 | MySQL 8.x |
| 实时通信 | WebSocket |
| 构建 | Maven |
| JDK | 17 |

## 项目结构

```
im-pay-system/
├── src/main/java/org/example/
│   ├── controller/     # REST 接口
│   ├── service/        # 业务逻辑（含并发控制实现）
│   ├── mapper/         # 数据访问层
│   ├── entity/         # 实体类
│   ├── common/         # 公共组件（全局异常处理等）
│   └── Application.java
├── src/main/resources/
│   └── schema.sql      # 数据库建表脚本
└── pom.xml
```

## 核心功能

- **即时通讯**：基于 WebSocket 的点对点实时消息
- **红包系统**：支持固定金额与拼手气红包，一人一包，24 小时未领自动退回
- **钱包系统**：余额查询、扣款、加款，支持转账
- **文件传输**：文件上传与下载

## 并发控制实践

### 抢红包：三种方案对比

红包表设置 `version` 字段，代码中保留三种实现用于对比验证：

1. **乐观锁（正式实现）**：读取 `version` → 带 `version` 条件更新 → 更新 0 行说明冲突，重读重试
2. **悲观锁 FOR UPDATE**：`SELECT ... FOR UPDATE` 持有行锁直到事务结束，并发请求排队
3. **无锁（错误示范）**：不带条件的 UPDATE，并发下会出现超抢

### 钱包：乐观锁 + 版本号

钱包扣款/加款均使用 `UPDATE wallet SET balance = balance ± ? WHERE user_id = ? AND version = ?`，更新失败即版本冲突，由外层重试。

### 防超抢

红包扣减 SQL 同时校验 `remain_count > 0` 与 `remain_amount >= amount`，双重条件确保不会出现负数。

## 本地启动

1. 创建 MySQL 数据库并执行 `src/main/resources/schema.sql`
2. 配置 `application.yml` 中的数据库连接
3. `mvn spring-boot:run`（默认端口 8080）

## License

MIT
