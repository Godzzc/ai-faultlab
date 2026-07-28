# Local Development

本文档只记录本地开发环境的常用启动和排查命令。完整演示流程见 [demo-guide.md](./demo-guide.md)。

## Docker Compose

首次准备环境变量：

```bash
cp deploy/.env.example deploy/.env
```

启动基础组件：

```bash
cd deploy
docker compose up -d
docker compose ps
```

正常应启动：

- `faultlab-mysql`
- `faultlab-redis`
- `faultlab-rabbitmq`

查看日志：

```bash
docker compose logs -f mysql
docker compose logs -f rabbitmq
```

停止容器但保留数据卷：

```bash
docker compose down
```

停止容器并清理数据卷：

```bash
docker compose down -v
```

区别：

- `docker compose down`：删除容器和网络，保留 MySQL / Redis / RabbitMQ 数据卷。
- `docker compose down -v`：同时删除数据卷，MySQL 会在下次启动时重新执行 `schema.sql` 初始化表结构。

## Backend

建议使用 IDEA 启动：

```text
FaultLabBackendApplication
```

IDEA Environment variables 示例：

```text
MYSQL_HOST=localhost;MYSQL_PORT=3306;MYSQL_DATABASE=faultlab;MYSQL_USERNAME=faultlab;MYSQL_PASSWORD=faultlab123456;RABBITMQ_HOST=localhost;RABBITMQ_PORT=5672;RABBITMQ_USERNAME=faultlab;RABBITMQ_PASSWORD=faultlab123456;REDIS_HOST=localhost;REDIS_PORT=6379
```

健康检查：

```text
GET http://localhost:8080/api/health
```

也可以命令行启动：

```bash
cd faultlab-backend
mvn spring-boot:run
```

## RabbitMQ Management

管理台地址：

```text
http://localhost:15672
```

默认本地账号密码来自 `deploy/.env.example`：

```text
faultlab / faultlab123456
```

如果复制后的 `deploy/.env` 修改过账号密码，后端 IDEA 环境变量也需要同步修改。

## Frontend

启动前端：

```bash
cd faultlab-frontend
npm install
npm run dev
```

访问：

```text
http://localhost:5173
```

前端 Vite 已配置代理：

```text
/api -> http://localhost:8080
```

如果 PowerShell 执行策略拦截 `npm`，可以使用：

```bash
npm.cmd run build
```

## Notes

- `deploy/.env` 不应提交到 Git。
- MySQL 初始化脚本挂载自 `faultlab-backend/src/main/resources/db/schema.sql`，不要维护第二份 schema。
- 修改 `schema.sql` 后，如果已有 MySQL volume，需要执行 `docker compose down -v` 后重新启动才会重新初始化。
