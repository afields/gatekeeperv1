package gatekeeper.gkv1.service;

import java.util.Map;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import gatekeeper.gkv1.strategy.AlwaysAllowRateLimitStrategy;
import gatekeeper.gkv1.strategy.AlwaysDenyRateLimitStrategy;
import gatekeeper.gkv1.strategy.FixedWindowCounterRateLimitStrategy;
import gatekeeper.gkv1.strategy.LeakyBucketRateLimitStrategy;
import gatekeeper.gkv1.strategy.RateLimitStrategy;
import gatekeeper.gkv1.strategy.SlidingWindowCounterRateLimitStrategy;
import gatekeeper.gkv1.strategy.SlidingWindowLogRateLimitStrategy;
import gatekeeper.gkv1.strategy.TokenBucketRateLimitStrategy;

/**
 * Service to handle rate limiting using various strategies.
 */
@Service
public class RateLimitService implements RateLimitServiceable {
	
	/**
	 * Map of strategy names to their corresponding RateLimitStrategy implementations.
	 */
	private final Map<String, RateLimitStrategy> strategies;
	
	/**
	 * Constructs a RateLimitService with predefined rate limiting strategies.
	 * 
	 * @param redisTemplate The Redis template for storing rate limit data.
	 */
	public RateLimitService(RedisTemplate<String, String> redisTemplate) {
		Assert.notNull(redisTemplate, "RedisTemplate must not be null");
		this.strategies = Map.ofEntries(
				Map.entry("alwaysallow", new AlwaysAllowRateLimitStrategy()),
				Map.entry("denyall", new AlwaysDenyRateLimitStrategy()),
				Map.entry("leakybucket", new LeakyBucketRateLimitStrategy(
						20,                                 // Maximum capacity of the bucket.
						2,                                  // Leak rate of the bucket (units per second).
						1,                                  // Number of tokens to request per incoming request.
						"leaky_bucket_strategy:",           // Leaky Token Bucket Strategy Redis Key Prefix
						":tokens",                          // Tokens Redis Key Suffix
						":timestamp",                       // Last Timestamp Redis Key Suffix
						redisTemplate)),
				Map.entry("tokenbucket", new TokenBucketRateLimitStrategy(
						20,                                 // Maximum capacity of the bucket.
						4,                                  // Number of tokens to add to the bucket per second.
						1,                                  // Number of tokens to request per incoming request.
						"token_bucket_strategy:",           // Token Bucket Strategy Redis Key Prefix
						":tokens",                          // Tokens Redis Key Suffix
						":timestamp",                       // Last Timestamp Redis Key Suffix
						redisTemplate)),
				Map.entry("fixedwindowcounter", new FixedWindowCounterRateLimitStrategy(
						20,                                 // Maximum number of requests allowed in the time window.
						60,                                 // Time window duration in seconds.
						"fixed_window_counter_strategy:",   // Fixed Window Counter Strategy Redis Key Prefix
						":requests",                        // Requests Redis Key Suffix
						":timestamp",                       // Last Timestamp Redis Key Suffix
						redisTemplate)),
				Map.entry("slidingwindowlog", new SlidingWindowLogRateLimitStrategy(
						20,                                 // Maximum number of requests allowed in the time window.
						60,                                 // Time window duration in seconds.
						"sliding_window_log_strategy:",     // Sliding Window Log Strategy Redis Key Prefix
						":log",                             // Sliding Window Log Redis Key Suffix
						redisTemplate)),
				Map.entry("slidingwindowcounter", new SlidingWindowCounterRateLimitStrategy(
						20,                                 // Maximum number of requests allowed in the time window.
						60,                                 // Time window duration in seconds.
						"sliding_window_counter_strategy:", // Sliding Window Counter Strategy Key Prefix
						":current_window_start",            // Current Window Start Redis Key Suffix
						":current_window_count",            // Current Window Count Redis Key Suffix
						":previous_window_count",           // Previous Window Count Redis Key Suffix
						redisTemplate))
		);
		
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isAllowed(String clientId, String strategyName) {
		RateLimitStrategy rateLimitStrategy = strategies.get(strategyName);
		if (rateLimitStrategy == null) {
			return false;
		}
		return rateLimitStrategy.allowRequest(clientId);
	}

}
