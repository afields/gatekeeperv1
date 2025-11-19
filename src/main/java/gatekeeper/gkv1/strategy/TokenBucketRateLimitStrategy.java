package gatekeeper.gkv1.strategy;

import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.Assert;

/**
 * Token Bucket Rate Limiting Strategy Implementation.
 * <p>
 * The token bucket algorithm is based on an analogy of a fixed capacity bucket into which tokens, 
 * normally representing a unit of bytes or a single packet of predetermined size, are added at a 
 * fixed rate. When a packet is to be checked for conformance to the defined limits, the bucket is 
 * inspected to see if it contains sufficient tokens at that time. If so, the appropriate number of 
 * tokens, e.g. equivalent to the length of the packet in bytes, are removed ("cashed in"), and the 
 * packet is passed, e.g., for transmission. The packet does not conform if there are insufficient 
 * tokens in the bucket, and the contents of the bucket are not changed.
 * <p>
 * Algorithm steps:<br>
 * 0. Initialize a full bucket.<br> 
 * 1. Get last refill time from Redis.<br>
 * 2. Get current token count from Redis.<br>
 * 3. Determine time elapsed since last refill.<br>
 * 4. Calculate number of tokens to add based on elapsed time and refill rate.<br>
 * 5. Update current token count, ensuring it does not exceed capacity.<br>
 * 6. Check if there are enough tokens for the request.<br>
 * 7. If enough tokens, deduct requested tokens and update Redis with new token count and timestamp.
 * 
 * @see <a href="https://www.wikipedia.org/wiki/Token_bucket">Token Bucket</a>
 * @see <a href="https://redis.io/docs/latest/develop/programmability/lua/">Redis Lua Scripting</a>
 */
public class TokenBucketRateLimitStrategy implements RateLimitStrategy {
	
	private static final String LUA_SCRIPT = """
			local tokens_key = KEYS[1]
			local timestamp_key = KEYS[2]
			local capacity = tonumber(ARGV[1])
			local refill_rate = tonumber(ARGV[2])
			local now = tonumber(ARGV[3])
			local tokens_requested = tonumber(ARGV[4])
			
			local last_refilled = tonumber(redis.call("get", timestamp_key) or "0")
			local current_tokens = tonumber(redis.call("get", tokens_key) or capacity)
			
			local delta = math.max(0, now - last_refilled)
			local tokens_to_refill = math.floor(delta / 1000) * refill_rate
			current_tokens = math.min(capacity, current_tokens + tokens_to_refill)
			
			local allowed = current_tokens >= tokens_requested
			if allowed then
				current_tokens = current_tokens - tokens_requested
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
	 * Number of tokens to add to the bucket (units per second).
	 */
	private final int refillTokenRate;
	
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
	 * Constructor to initialize the token bucket rate limit strategy.
	 * 
	 * @param capacity Maximum capacity of the bucket.
	 * @param refillTokenRate Number of tokens to add to the bucket per second.
	 * @param tokensToRequest Number of tokens to request per incoming request.
	 * @param tokenBucketKeyPrefix Redis key prefix for token bucket storage.
	 * @param tokensKeySuffix Redis key suffix for tokens storage.
	 * @param timestampKeySuffix Redis key suffix for timestamp storage.
	 * @param redisTemplate Redis template for executing Redis commands.
	 */
	public TokenBucketRateLimitStrategy(
			int capacity,
			int refillTokenRate,
			int tokensToRequest,
			String tokenBucketKeyPrefix,
			String tokensKeySuffix,
			String timestampKeySuffix,
			RedisTemplate<String, String> redisTemplate) {
		Assert.isTrue(capacity > 0, "Capacity must be greater than zero.");
		Assert.isTrue(refillTokenRate > 0, "Refill token rate must be greater than zero.");
		Assert.isTrue(tokensToRequest > 0, "Tokens to request must be greater than zero.");
		Assert.hasText(tokenBucketKeyPrefix, "Token bucket key prefix must not be empty.");
		Assert.hasText(tokensKeySuffix, "Tokens key suffix must not be empty.");
		Assert.hasText(timestampKeySuffix, "Timestamp key suffix must not be empty.");
		Assert.notNull(redisTemplate, "RedisTemplate must not be null");
		this.capacity = capacity;
		this.refillTokenRate = refillTokenRate;
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
				String.valueOf(refillTokenRate),
				String.valueOf(System.currentTimeMillis()),
				Integer.toString(tokensToRequest)
			);
		return result != null && result == 1;
	}

}
