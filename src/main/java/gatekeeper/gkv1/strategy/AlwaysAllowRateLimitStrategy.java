package gatekeeper.gkv1.strategy;

/**
 * A rate limit strategy that always allows requests.
 */
public class AlwaysAllowRateLimitStrategy implements RateLimitStrategy {
	/**
	 * Always allow requests.
	 * @return true
	 */
	@Override
	public boolean allowRequest(String key) {
		return true;
	}

}
