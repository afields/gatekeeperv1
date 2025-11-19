# Gatekeeper v1

A distributed rate limiter service built with Spring Boot and gRPC, implementing multiple rate limiting strategies backed by Redis.

## Overview

Gatekeeper v1 is a gRPC-based rate limiting service that provides flexible rate limiting capabilities using various algorithms. It's designed to help protect your services from being overwhelmed by too many requests.

## Features

- **Multiple Rate Limiting Strategies**: Choose from 7 different rate limiting algorithms
- **gRPC API**: High-performance communication using Protocol Buffers
- **Redis-backed**: Distributed rate limiting with Redis for state management
- **Spring Boot**: Built on Spring Boot 3.5.7 with Spring gRPC
- **Actuator & Prometheus**: Built-in health checks and metrics

## Prerequisites

- Java 21 or higher
- Redis server (running on localhost:6379 by default)
- Gradle (wrapper included)

## Getting Started

### 1. Start Redis

Make sure you have a Redis server running on `localhost:6379`. You can start Redis using Docker:

```bash
docker run -d -p 6379:6379 redis:latest
```

Or install and run Redis locally:

```bash
# On Windows (using Chocolatey)
choco install redis-64

# On macOS
brew install redis
brew services start redis

# On Linux
sudo apt-get install redis-server
sudo systemctl start redis
```

### 2. Build the Application

```bash
./gradlew build
```

### 3. Run the Application

```bash
./gradlew bootRun
```

The application will start on port `9090` (default gRPC port for Spring gRPC).

## Rate Limiting Strategies

Gatekeeper v1 supports the following rate limiting strategies:

### 1. **AlwaysAllow** (`alwaysallow`)
- **Description**: Always allows all requests (useful for testing or bypass scenarios)
- **Use Case**: Testing, development, or when you want to disable rate limiting temporarily

### 2. **DenyAll** (`denyall`)
- **Description**: Always denies all requests
- **Use Case**: Emergency scenarios where you need to block all traffic, or for testing denial flows

### 3. **Token Bucket** (`tokenbucket`)
- **Description**: Tokens are added to a bucket at a fixed rate. Each request consumes tokens. If no tokens are available, the request is denied.
- **Configuration**: 
  - Capacity: 20 tokens
  - Refill rate: 4 tokens/second
  - Tokens per request: 1
- **Use Case**: Allows bursts of traffic while maintaining average rate

### 4. **Leaky Bucket** (`leakybucket`)
- **Description**: Similar to token bucket but tokens "leak" from the bucket at a constant rate. Requests add to the bucket; if full, they're denied.
- **Configuration**:
  - Capacity: 20 tokens
  - Leak rate: 2 tokens/second
  - Tokens per request: 1
- **Use Case**: Smooths out bursty traffic to a constant rate

### 5. **Fixed Window Counter** (`fixedwindowcounter`)
- **Description**: Counts requests in fixed time windows. Counter resets at the start of each window.
- **Configuration**:
  - Limit: 20 requests
  - Window: 60 seconds
- **Use Case**: Simple rate limiting with clear time boundaries
- **Note**: Can allow up to 2× limit at window boundaries

### 6. **Sliding Window Log** (`slidingwindowlog`)
- **Description**: Maintains a log of request timestamps. Counts requests within a sliding time window.
- **Configuration**:
  - Limit: 20 requests
  - Window: 60 seconds
- **Use Case**: More accurate than fixed window, prevents boundary issues
- **Note**: More memory-intensive

### 7. **Sliding Window Counter** (`slidingwindowcounter`)
- **Description**: Combines fixed window counters with weighted calculation for current window position.
- **Configuration**:
  - Limit: 20 requests
  - Window: 60 seconds
- **Use Case**: Balance between accuracy and efficiency

## gRPC API

### Service Definition

```protobuf
service Gkv1Service {
    rpc CheckRateLimit(CheckRateLimitRequest) returns (CheckRateLimitResponse);
}

message CheckRateLimitRequest {
    string client_id = 1;
    string strategy = 2;
}

message CheckRateLimitResponse {
    bool allowed = 1;
    string message = 2;
}
```

### Example gRPC Calls

You can use `grpcurl` to test the API. First, install grpcurl:

```bash
# On Windows (using Chocolatey)
choco install grpcurl

# On macOS
brew install grpcurl

# Or download from: https://github.com/fullstorydev/grpcurl/releases
```

#### 1. AlwaysAllow Strategy

```bash
grpcurl -plaintext -d '{
  "client_id": "user123",
  "strategy": "alwaysallow"
}' localhost:9090 gkv1.Gkv1Service/CheckRateLimit
```

**Expected Response:**
```json
{
  "allowed": true,
  "message": "alwaysallow:user123"
}
```

#### 2. DenyAll Strategy

```bash
grpcurl -plaintext -d '{
  "client_id": "user456",
  "strategy": "denyall"
}' localhost:9090 gkv1.Gkv1Service/CheckRateLimit
```

**Expected Response:**
```json
{
  "allowed": false,
  "message": "denyall:user456"
}
```

#### 3. Token Bucket Strategy

```bash
grpcurl -plaintext -d '{
  "client_id": "user789",
  "strategy": "tokenbucket"
}' localhost:9090 gkv1.Gkv1Service/CheckRateLimit
```

**Expected Response (first 20 requests within a short time):**
```json
{
  "allowed": true,
  "message": "tokenbucket:user789"
}
```

**Expected Response (after exceeding capacity):**
```json
{
  "allowed": false,
  "message": "tokenbucket:user789"
}
```

**Note**: Tokens refill at 4/second, so wait a few seconds and requests will be allowed again.

#### 4. Leaky Bucket Strategy

```bash
grpcurl -plaintext -d '{
  "client_id": "userABC",
  "strategy": "leakybucket"
}' localhost:9090 gkv1.Gkv1Service/CheckRateLimit
```

**Expected Response (within rate limit):**
```json
{
  "allowed": true,
  "message": "leakybucket:userABC"
}
```

**Note**: Bucket leaks at 2 tokens/second with capacity of 20.

#### 5. Fixed Window Counter Strategy

```bash
grpcurl -plaintext -d '{
  "client_id": "userDEF",
  "strategy": "fixedwindowcounter"
}' localhost:9090 gkv1.Gkv1Service/CheckRateLimit
```

**Expected Response (within 20 requests per 60-second window):**
```json
{
  "allowed": true,
  "message": "fixedwindowcounter:userDEF"
}
```

#### 6. Sliding Window Log Strategy

```bash
grpcurl -plaintext -d '{
  "client_id": "userGHI",
  "strategy": "slidingwindowlog"
}' localhost:9090 gkv1.Gkv1Service/CheckRateLimit
```

**Expected Response (within 20 requests per rolling 60-second window):**
```json
{
  "allowed": true,
  "message": "slidingwindowlog:userGHI"
}
```

#### 7. Sliding Window Counter Strategy

```bash
grpcurl -plaintext -d '{
  "client_id": "userJKL",
  "strategy": "slidingwindowcounter"
}' localhost:9090 gkv1.Gkv1Service/CheckRateLimit
```

**Expected Response (within 20 requests per rolling 60-second window):**
```json
{
  "allowed": true,
  "message": "slidingwindowcounter:userJKL"
}
```

### Testing Multiple Clients

Each `client_id` is tracked independently. Test with different clients:

```bash
# Client 1 - will have its own rate limit
grpcurl -plaintext -d '{
  "client_id": "client1",
  "strategy": "tokenbucket"
}' localhost:9090 gkv1.Gkv1Service/CheckRateLimit

# Client 2 - will have its own separate rate limit
grpcurl -plaintext -d '{
  "client_id": "client2",
  "strategy": "tokenbucket"
}' localhost:9090 gkv1.Gkv1Service/CheckRateLimit
```

## Monitoring

### Health Check

```bash
curl http://localhost:8080/actuator/health
```

### Prometheus Metrics

```bash
curl http://localhost:8080/actuator/prometheus
```

## Architecture

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ gRPC
       ▼
┌─────────────────────────┐
│  GrpcServerService      │
│  (Spring gRPC)          │
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  RateLimitService       │
│  (Strategy Router)      │
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  RateLimitStrategy      │
│  (Algorithm Impl)       │
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│       Redis             │
│  (State Storage)        │
└─────────────────────────┘
```

## Project Structure

```
gkv1/
├── src/main/
│   ├── java/gatekeeper/gkv1/
│   │   ├── GatekeeperV1Application.java    # Main application
│   │   ├── grpc/
│   │   │   └── GrpcServerService.java      # gRPC service implementation
│   │   ├── service/
│   │   │   ├── RateLimitServiceable.java   # Service interface
│   │   │   └── RateLimitService.java       # Service implementation
│   │   └── strategy/
│   │       ├── RateLimitStrategy.java      # Strategy interface
│   │       ├── AlwaysAllowRateLimitStrategy.java
│   │       ├── AlwaysDenyRateLimitStrategy.java
│   │       ├── TokenBucketRateLimitStrategy.java
│   │       ├── LeakyBucketRateLimitStrategy.java
│   │       ├── FixedWindowCounterRateLimitStrategy.java
│   │       ├── SlidingWindowLogRateLimitStrategy.java
│   │       └── SlidingWindowCounterRateLimitStrategy.java
│   ├── proto/
│   │   └── gkv1.proto                      # Protocol buffer definition
│   └── resources/
│       └── application.properties          # Application configuration
└── build.gradle                            # Build configuration
```

## Configuration

Edit `src/main/resources/application.yml` to customize:

```yaml
spring:
  application:
    name: "Gatekeeper v1"

  data:
    redis:
      # host: localhost
      # port: 6379

  grpc:
    server:
      # port: 9090

server:
  # port: 8080
```

To modify rate limiting parameters, edit `RateLimitService.java` constructor where strategies are initialized.

## Running Tests

```bash
./gradlew test
```

## Building for Production

```bash
# Build JAR file
./gradlew bootJar

# Run the JAR
java -jar build/libs/gkv1-0.0.1-SNAPSHOT.jar
```

## Troubleshooting

### Connection Refused
- Ensure Redis is running: `redis-cli ping` (should return `PONG`)
- Check if the gRPC server started on port 9090

### Requests Always Denied
- Check Redis connection
- Verify the strategy name is spelled correctly (all lowercase)
- Review application logs for errors

### Invalid Strategy
If you send a request with an unknown strategy, the service will return `allowed: false`.

## License

This is an example application for educational purposes.

## Contributing

This is a demonstration project. Feel free to use it as a reference for your own rate limiting implementation.
