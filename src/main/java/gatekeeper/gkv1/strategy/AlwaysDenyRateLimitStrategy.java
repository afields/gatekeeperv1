package gatekeeper.gkv1.strategy;

/**
 * A rate limiting strategy that always denies requests.
 */
public class AlwaysDenyRateLimitStrategy implements RateLimitStrategy {
	/**
	 * Always deny requests.
	 * @return false
	 */
	@Override
	public boolean allowRequest(String key) {
		return false;
	}

}
