package gatekeeper.gkv1.strategy;

import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.Assert;

/**
 * Leaky Bucket Rate Limiting Strategy Implementation.
 * <p>
 * The bucket is a counter or variable separate from the flow of traffic or schedule of events.
 * This counter is used only to check that the traffic or events conform to the limits: 
 * The counter is incremented as each packet arrives at the point where the check is being made or 
 * an event occurs, which is equivalent to the way water is added intermittently to the bucket. 
 * The counter is also decremented at a fixed rate, equivalent to the way the water leaks out of 
 * the bucket. As a result, the value in the counter represents the level of the water in the bucket. 
 * If the counter remains below a specified limit value when a packet arrives or an event occurs, 
 * i.e. the bucket does not overflow, that indicates its conformance to the bandwidth and burstiness 
 * limits or the average and peak rate event limits.
 * <p>
 * Algorithm steps:<br>
 * 0. Initialize an empty bucket with a fixed capacity and leak rate.<br>
 * 1. For each incoming request, calculate the time elapsed since the last leak operation.<br>
 * 2. Determine the number of units to leak based on the elapsed time and leak rate.<br>
 * 3. Decrease the current level of the bucket by the leaked units, ensuring it does not go below zero.<br>
 * 4. If the current level of the bucket is at or above capacity, reject the request.<br>
 * 5. If there is capacity in the bucket, increment the current level by one unit and allow the request.
 * 
 * @see <a href="https://en.wikipedia.org/wiki/Leaky_bucket">Leaky Bucket Algorithm</a>
 * @see <a href="https://redis.io/docs/latest/develop/programmability/lua/">Redis Lua Scripting</a>
 */
public class LeakyBucketRateLimitStrategy implements RateLimitStrategy {
	
	private static final String LUA_SCRIPT = """
			local tokens_key = KEYS[1]
			local timestamp_key = KEYS[2]
			local capacity = tonumber(ARGV[1])
			local leak_rate = tonumber(ARGV[2])
			local now = tonumber(ARGV[3])
			local tokens_requested = tonumber(ARGV[4])
			
			local last_refilled = tonumber(redis.call("get", timestamp_key) or "0")
			local current_tokens = tonumber(redis.call("get", tokens_key) or "0")
			
			local delta = math.max(0, now - last_refilled)
			local tokens_to_leak = math.floor(delta / 1000) * leak_rate
			current_tokens = math.max(0, current_tokens - tokens_to_leak)
			
			local allowed = capacity >= current_tokens
			if allowed then
				current_tokens = current_tokens + tokens_requested
				redis.call("set", tokens_key, current_tokens)
				redis.call("set", timestamp_key, now)
			end
			
			return allowed
		""";
	
	/**
	 * Maximum capacity of the bucket.
	 */
	private final int capacity;
	
	/**
	 * Leak rate of the bucket (units per second).
	 */
	private final int leakRate;
	
	/**
	 * Number of tokens to request from the bucket per incoming request.<br>
	 * Could be adjusted based on request size or type.
	 */
	private final int tokensToRequest;
	
	/**
	 * Redis key prefix for token bucket storage.
	 */
	private final String tokenBucketKeyPrefix;
	
	/**
	 * Redis key suffix for tokens storage.
	 */
	private final String tokensKeySuffix;
	
	/**
	 * Redis key suffix for timestamp storage.
	 */
	private final String timestampKeySuffix;
	
	/**
	 * Redis template for executing Redis commands.
	 */
	private final RedisTemplate<String, String> redisTemplate;
	
	/**
	 * Constructor to initialize the leaky bucket rate limit strategy.
	 * 
	 * @param capacity Maximum capacity of the bucket.
	 * @param leakRate Leak rate of the bucket (units per second).
	 * @param tokensToRequest Number of tokens to request per incoming request.
	 * @param tokenBucketKeyPrefix Redis key prefix for token bucket storage.
	 * @param tokensKeySuffix Redis key suffix for tokens storage.
	 * @param timestampKeySuffix Redis key suffix for timestamp storage.
	 * @param redisTemplate Redis template for executing Redis commands.
	 */
	public LeakyBucketRateLimitStrategy(
			int capacity, 
			int leakRate,
			int tokensToRequest,
			String tokenBucketKeyPrefix,
			String tokensKeySuffix,
			String timestampKeySuffix,
			RedisTemplate<String, String> redisTemplate) {
		Assert.isTrue(capacity > 0, "Capacity must be greater than zero.");
		Assert.isTrue(leakRate > 0, "Leak rate must be greater than zero.");
		Assert.isTrue(tokensToRequest > 0, "Tokens to request must be greater than zero.");
		Assert.hasText(tokenBucketKeyPrefix, "Token bucket key prefix must not be empty.");
		Assert.hasText(tokensKeySuffix, "Tokens key suffix must not be empty.");
		Assert.hasText(timestampKeySuffix, "Timestamp key suffix must not be empty.");
		Assert.notNull(redisTemplate, "RedisTemplate must not be null.");
		this.capacity = capacity;
		this.leakRate = leakRate;
		this.tokensToRequest = tokensToRequest;
		this.tokenBucketKeyPrefix = tokenBucketKeyPrefix;
		this.tokensKeySuffix = tokensKeySuffix;
		this.timestampKeySuffix = timestampKeySuffix;
		this.redisTemplate = redisTemplate;
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	// TODO: Handle overflow and underflow cases properly
	public boolean allowRequest(String key) {
		String redisKey = tokenBucketKeyPrefix + key; // Ensures uniqueness per key/client
		DefaultRedisScript<Long> redisScript = 
				new DefaultRedisScript<>(LUA_SCRIPT, Long.class); // Execute as lua script to ensure atomicity
		Long result = redisTemplate.execute( // TODO: Why Long result type?
				redisScript,
				List.of(
					redisKey + tokensKeySuffix, 
					redisKey + timestampKeySuffix),
				String.valueOf(capacity),
				String.valueOf(leakRate),
				String.valueOf(System.currentTimeMillis()),
				Integer.toString(tokensToRequest)
			);
		return result != null && result == 1;
	}
	
}
