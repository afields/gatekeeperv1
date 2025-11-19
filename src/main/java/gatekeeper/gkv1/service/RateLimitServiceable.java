package gatekeeper.gkv1.service;

/**
 * Serviceable interface for rate limiting functionality.
 */
public interface RateLimitServiceable {
	
	/**
	 * Checks if a request is allowed based on the specified strategy and client ID.
	 * 
	 * @param clientId The identifier for the client making the request.
	 * @param strategyName The name of the rate limiting strategy to use.
	 * @return true if the request is allowed, false otherwise.
	 */
	public boolean isAllowed(
			String clientId,
			String strategyName);
	
}
