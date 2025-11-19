package gatekeeper.gkv1.strategy;

import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.Assert;

/**
 * Fixed Window Counter Rate Limiting Strategy Implementation.
 * <p>
 * Fixed Window Counter algorithm counts the number of requests in a fixed time window
 * and allows or denies requests based on a predefined limit. (e.g., 100 requests per minute).
 * The counter resets at the start of each new time window.
 * <p>
 * Algorithm steps:<br>
 * 1. Define a fixed time window (e.g., 1 minute).<br>
 * 2. For each incoming request, check the current time window.<br>
 * 3. If the current time window has expired, reset the request counter to zero.<br>
 * 4. Increment the request counter.<br>
 * 5. If the request counter exceeds the predefined limit, deny the request; otherwise, allow it.
 * <p>
 * @see <a href="https://en.wikipedia.org/wiki/Rate_limiting#Fixed_window_counter">Fixed Window Counter</a>
 * @see <a href="https://redis.io/docs/latest/develop/programmability/lua/">Redis Lua Scripting</a>
 */
public class FixedWindowCounterRateLimitStrategy implements RateLimitStrategy {
	
	private static final String LUA_SCRIPT = """
			local request_count_key = KEYS[1]
			local last_timestamp_key = KEYS[2]
			local capacity = tonumber(ARGV[1])
			local window_in_seconds = tonumber(ARGV[2])
			local now = tonumber(ARGV[3])
			
			local last_timestamp = tonumber(redis.call("get", last_timestamp_key) or "0")
			local request_count = tonumber(redis.call("get", request_count_key) or "0")
			
			local delta = math.max(0, now - last_timestamp)
			local reset_window = math.floor(delta / 1000) >= window_in_seconds
			if reset_window then
				request_count = 0
				last_timestamp = now
				redis.call("set", last_timestamp_key, last_timestamp)
			end
			
			request_count = request_count + 1
			redis.call("set", request_count_key, request_count)
			
			return capacity > request_count
		""";
	
	/**
	 * Maximum number of requests allowed in the time window.
	 */
	private final int capacity;
	
	/**
	 * Time window duration in seconds.
	 */
	private final int timeWindowInSeconds;
	
	/**
	 * Redis key prefix for fixed window counter storage.
	 */
	private final String fixedWindowKeyPrefix;
	
	/**
	 * Redis key suffix for requests storage.
	 */
	private final String requestsKeySuffix;
	
	/**
	 * Redis key suffix for timestamp storage.
	 */
	private final String timestampKeySuffix;
	
	/**
	 * Redis template for executing Redis commands.
	 */
	private final RedisTemplate<String, String> redisTemplate;
	
	/**
	 * Constructor to initialize the fixed window rate limit strategy.
	 * 
	 * @param capacity Maximum number of requests allowed in the time window.
	 * @param timeWindowInSeconds Time window duration in seconds.
	 * @param fixedWindowKeyPrefix Redis key prefix for fixed window counter storage.
	 * @param requestsKeySuffix Redis key suffix for requests storage.
	 * @param timestampKeySuffix Redis key suffix for timestamp storage.
	 * @param redisTemplate Redis template for executing Redis commands.
	 */
	public FixedWindowCounterRateLimitStrategy(
			int capacity,
			int timeWindowInSeconds,
			String fixedWindowKeyPrefix,
			String requestsKeySuffix,
			String timestampKeySuffix,
			RedisTemplate<String, String> redisTemplate) {
		Assert.isTrue(capacity > 0, "capacity must be positive");
		Assert.isTrue(timeWindowInSeconds > 0, "timeWindowInSeconds must be positive");
		Assert.hasText(fixedWindowKeyPrefix, "fixedwindowKeyPrefix must not be empty");
		Assert.hasText(requestsKeySuffix, "requestsKeySuffix must not be empty");
		Assert.hasText(timestampKeySuffix, "timestampKeySuffix must not be empty");
		Assert.notNull(redisTemplate, "redisTemplate must not be null");
		this.capacity = capacity;
		this.timeWindowInSeconds = timeWindowInSeconds;
		this.fixedWindowKeyPrefix = fixedWindowKeyPrefix;
		this.requestsKeySuffix = requestsKeySuffix;
		this.timestampKeySuffix = timestampKeySuffix;
		this.redisTemplate = redisTemplate;
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	// TODO: Handle overflow and underflow cases properly
	public boolean allowRequest(String key) {
		String redisKey = fixedWindowKeyPrefix + key; // Ensures uniqueness per key/client
		DefaultRedisScript<Long> redisScript = 
				new DefaultRedisScript<>(LUA_SCRIPT, Long.class); // Execute as lua script to ensure atomicity
		Long result = redisTemplate.execute( // TODO: Why Long result type?
				redisScript,
				List.of(
					redisKey + requestsKeySuffix, 
					redisKey + timestampKeySuffix),
				String.valueOf(capacity),
				String.valueOf(timeWindowInSeconds),
				String.valueOf(System.currentTimeMillis())
			);
		return result != null && result == 1;
	}

}
