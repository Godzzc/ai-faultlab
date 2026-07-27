# Local Development Infrastructure

This setup starts the local infrastructure required by `faultlab-backend`: MySQL, Redis, and RabbitMQ.

## Prepare Environment

```bash
cp deploy/.env.example deploy/.env
```

`deploy/.env` is ignored by Git. Keep local passwords and machine-specific settings there.

## Start Components

```bash
cd deploy
docker compose up -d
```

## Check Status

```bash
docker compose ps
```

## View Logs

```bash
docker compose logs -f mysql
docker compose logs -f rabbitmq
```

## Stop Components

```bash
docker compose down
```

## Clean Data

```bash
docker compose down -v
```

MySQL runs `../faultlab-backend/src/main/resources/db/schema.sql` only when the MySQL data volume is first initialized. If `schema.sql` changes after MySQL has already started once, run `docker compose down -v` and start again to recreate the database and tables.

## RabbitMQ Management

Open:

```text
http://localhost:15672
```

Use the values from `deploy/.env` for the RabbitMQ username and password.

## Start Backend Locally

```bash
cd faultlab-backend
mvn spring-boot:run
```

The backend defaults already point to local MySQL and RabbitMQ. When using the example `.env`, set matching environment variables before starting the backend if your shell does not load `deploy/.env` automatically.
