package gatekeeper.gkv1.strategy;

import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.Assert;

/**
 * Sliding Window Counter Rate Limiting Strategy Implementation.
 * <p>
 * The sliding window counter algorithm divides time into smaller intervals (sub-windows)
 * and maintains a count of requests in each sub-window. When a new request arrives,
 * it calculates the total number of requests in the current sliding window by summing
 * the counts from the relevant sub-windows. If the total count is below the predefined limit,
 * the request is allowed; otherwise, it is denied.
 * <p>
 * Algorithm steps:<br>
 * 1. Define the size of the sliding window and the number of sub-windows.<br>
 * 2. For each incoming request, determine the current sub-window based on the current time.<br>
 * 3. Increment the count for the current sub-window.<br>
 * 4. Calculate the total count of requests in the sliding window by summing the counts from all relevant sub-windows.<br>
 * 5. If the total count exceeds the predefined limit, deny the request; otherwise, allow it.
 * <p>
 * @see <a href="https://en.wikipedia.org/wiki/Rate_limiting#Sliding_window_counter">Sliding Window Counter</a>
 * @see <a href="https://bytebytego.com/courses/system-design-interview/design-a-rate-limiter">Design a Rate Limiter - ByteByteGo</a>
 * @see <a href="https://redis.io/docs/latest/develop/programmability/lua/">Redis Lua Scripting</a>
 */
public class SlidingWindowCounterRateLimitStrategy implements RateLimitStrategy {
	
	private static final String LUA_SCRIPT = """
			local current_window_start_key = KEYS[1]
			local current_window_count_key = KEYS[2]
			local last_window_count_key = KEYS[3]
			
			local capacity = tonumber(ARGV[1])
			local window_in_seconds = tonumber(ARGV[2])
			
			local current_window_start = tonumber(redis.call("get", current_window_start_key) or "0")
			local current_window_count = tonumber(redis.call("get", current_window_count_key) or "0")
			local last_window_count = tonumber(redis.call("get", last_window_count_key) or "0")
			
			local current_time = redis.call("TIME")
			local window = current_time[1] - current_window_start
			if window >= window_in_seconds then
				-- Shift windows
				last_window_count = current_window_count
				current_window_count = 0
				current_window_start = current_time[1]
				
				redis.call("set", last_window_count_key, last_window_count)
				redis.call("set", current_window_start_key, current_window_start)
			end
			
			local weighted_count = math.floor((last_window_count * (window_in_seconds - window) / window_in_seconds)) + current_window_count
			
			if weighted_count <= capacity then
				current_window_count = current_window_count + 1
				redis.call("set", current_window_count_key, current_window_count)
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
	 * Redis key prefix for fixed window counter storage.
	 */
	private final String slidingWindowKeyPrefix;
	
	/**
	 * Redis key suffix for current window start storage.
	 */
	private final String currentWindowStartKeySuffix;
	
	/**
	 * Redis key suffix for current window count storage.
	 */
	private final String currentWindowCountKeySuffix;
	
	/**
	 * Redis key suffix for previous window count storage.
	 */
	private final String previousWindowCountKeySuffix;
	
	/**
	 * Redis template for executing Redis commands.
	 */
	private final RedisTemplate<String, String> redisTemplate;
	
	
	public SlidingWindowCounterRateLimitStrategy(
			int capacity,
			int timeWindowInSeconds,
			String slidingWindowKeyPrefix,
			String currentWindowStartKeySuffix,
			String currentWindowCountKeySuffix,
			String previousWindowCountKeySuffix,
			RedisTemplate<String, String> redisTemplate) {
		Assert.isTrue(capacity > 0, "Capacity must be greater than zero");
		Assert.isTrue(timeWindowInSeconds > 0, "Time window must be greater than zero");
		Assert.hasText(slidingWindowKeyPrefix, "Fixed window key prefix must not be empty");
		Assert.hasText(currentWindowStartKeySuffix, "Current window start key suffix must not be empty");
		Assert.hasText(currentWindowCountKeySuffix, "Current window count key suffix must not be empty");
		Assert.hasText(previousWindowCountKeySuffix, "Previous window count key suffix must not be empty");
		Assert.notNull(redisTemplate, "RedisTemplate must not be null");
		this.capacity = capacity;
		this.timeWindowInSeconds = timeWindowInSeconds;
		this.slidingWindowKeyPrefix = slidingWindowKeyPrefix;
		this.currentWindowStartKeySuffix = currentWindowStartKeySuffix;
		this.currentWindowCountKeySuffix = currentWindowCountKeySuffix;
		this.previousWindowCountKeySuffix = previousWindowCountKeySuffix;
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
					redisKey + currentWindowStartKeySuffix, 
					redisKey + currentWindowCountKeySuffix,
					redisKey + previousWindowCountKeySuffix),
				String.valueOf(capacity),
				String.valueOf(timeWindowInSeconds)
			);
		return result != null && result == 1;
	}

}
