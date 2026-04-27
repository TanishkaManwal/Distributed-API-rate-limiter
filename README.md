# Distributed-API-rate-limiter
Distributed API rate limiter built with Java 17, Spring Boot, and Redis, implementing Token Bucket, Fixed Window, Sliding Window Log, and Leaky Bucket algorithms. Uses atomic Redis Lua scripts to ensure consistency across distributed nodes, with in-memory fallback and Prometheus-based observability and monitoring.
