package gatekeeper.gkv1.strategy;

/**
 * Strategy interface for Gatekeeper rate limiting.
 */
public interface RateLimitStrategy {
	/**
	 * Determines if a request is allowed under the rate limit strategy identified by the given key.
	 *
	 * @param key The identifier for the strategy (e.g., "alwaysallow", "denyall").
	 * @return true if the request is allowed, false if it exceeds the rate limit.
	 */
	boolean allowRequest(String key);
}
