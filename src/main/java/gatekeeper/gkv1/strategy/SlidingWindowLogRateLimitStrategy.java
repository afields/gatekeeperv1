package gatekeeper.gkv1.strategy;

import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.Assert;

/**
 * Sliding Window Log Rate Limiting Strategy Implementation.
 * <p>
 * The sliding window log algorithm records the timestamps of each request in a sorted set.
 * When a new request arrives, it removes timestamps that are outside the current time window,
 * counts the remaining timestamps, and decides whether to allow or deny the request based on the count.
 * <p>
 * Algorithm steps:<br>
 * 1. For each incoming request, get the current timestamp.<br>
 * 2. Remove timestamps from the sorted set that are older than the defined time window.<br>
 * 3. Count the number of timestamps remaining in the sorted set.<br>
 * 4. If the count is below the predefined limit, add the current timestamp to the sorted set and allow the request; otherwise, deny it.
 * <p>
 * @see <a href="https://redis.io/learn/develop/dotnet/aspnetcore/rate-limiting/sliding-window">Sliding Window Log Rate Limiting with Redis</a>
 * @see <a href="https://redis.io/docs/latest/develop/programmability/lua/">Redis Lua Scripting</a>
 */
public class SlidingWindowLogRateLimitStrategy implements RateLimitStrategy {
	
	private static final String LUA_SCRIPT = """
			local window_request_key = KEYS[1]
			local capacity = tonumber(ARGV[1])
			local window_in_seconds = tonumber(ARGV[2])
			
			local current_time = redis.call("TIME")
			local window_start = tonumber(current_time[1]) - window_in_seconds
			
			-- Remove outdated requests
			redis.call('ZREMRANGEBYSCORE', window_request_key, 0, window_start)
			
			local request_count = redis.call('ZCARD', window_request_key)
			
			if request_count < capacity then
				redis.call('ZADD', window_request_key, tonumber(current_time[1]), tostring(current_time[1]) .. tostring(current_time[2]))
				-- Set expiration for the sorted set to avoid indefinite growth
				redis.call('EXPIRE', window_request_key, window_in_seconds)
				return true
			else
				return false
			end
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
	 * Redis key prefix for sliding window log storage.
	 */
	private final String slidingWindowKeyPrefix;
	
	/**
	 * Redis key suffix for sliding window log storage.
	 */
	private final String slidingWindowKeySuffix;
	
	/**
	 * Redis template for executing Redis commands.
	 */
	private final RedisTemplate<String, String> redisTemplate;
	
	/**
	 * Constructor to initialize the sliding window rate limit strategy.
	 * 
	 * @param capacity Maximum number of requests allowed in the time window.
	 * @param timeWindowInSeconds Time window duration in seconds.
	 * @param slidingWindowKeyPrefix Redis key prefix for sliding window log storage.
	 * @param redisTemplate Redis template for executing Redis commands.
	 */
	public SlidingWindowLogRateLimitStrategy(
			int capacity,
			int timeWindowInSeconds,
			String slidingWindowKeyPrefix,
			String slidingWindowKeySuffix,
			RedisTemplate<String, String> redisTemplate) {
		Assert.isTrue(capacity > 0, "Capacity must be greater than zero");
		Assert.isTrue(timeWindowInSeconds > 0, "Time window must be greater than zero");
		Assert.hasText(slidingWindowKeyPrefix, "Sliding window key prefix must not be empty");
		Assert.hasText(slidingWindowKeySuffix, "Sliding window key suffix must not be empty");
		Assert.notNull(redisTemplate, "RedisTemplate must not be null");
		this.capacity = capacity;
		this.timeWindowInSeconds = timeWindowInSeconds;
		this.slidingWindowKeyPrefix = slidingWindowKeyPrefix;
		this.slidingWindowKeySuffix = slidingWindowKeySuffix;
		this.redisTemplate = redisTemplate;
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean allowRequest(String key) {
		String redisKey = slidingWindowKeyPrefix + key; // Ensures uniqueness per key/client
		DefaultRedisScript<Long> redisScript = 
				new DefaultRedisScript<>(LUA_SCRIPT, Long.class); // Execute as lua script to ensure atomicity
		Long result = redisTemplate.execute( // TODO: Why Long result type?
				redisScript,
				List.of(
					redisKey + slidingWindowKeySuffix),
				String.valueOf(capacity),
				String.valueOf(timeWindowInSeconds)
			);
		return result != null && result == 1;
	}

}
