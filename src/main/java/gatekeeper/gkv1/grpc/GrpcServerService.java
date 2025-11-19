package gatekeeper.gkv1.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gatekeeper.gkv1.proto.CheckRateLimitRequest;
import gatekeeper.gkv1.proto.CheckRateLimitResponse;
import gatekeeper.gkv1.proto.Gkv1ServiceGrpc.Gkv1ServiceImplBase;
import gatekeeper.gkv1.service.RateLimitServiceable;
import io.grpc.stub.StreamObserver;

/**
 * gRPC service implementation for rate limiting.
 */
@Service
public class GrpcServerService extends Gkv1ServiceImplBase {
	
	private final static Logger logger = LoggerFactory.getLogger(GrpcServerService.class);
	
	private final RateLimitServiceable rateLimitService;
	
	/**
	 * Constructs a GrpcServerService with the provided RateLimitServiceable.
	 * 
	 * @param rateLimitService The rate limit service to use for checking rate limits.
	 */
	public GrpcServerService(RateLimitServiceable rateLimitService) {
		this.rateLimitService = rateLimitService;
	}

	/**
	 * Checks if a request is allowed based on the specified strategy and client ID.
	 * 
	 * @param request The CheckRateLimitRequest containing strategy and client ID.
	 * @param responseObserver The StreamObserver to send the response.
	 */
	@Override
	public void checkRateLimit(
			CheckRateLimitRequest request, 
			StreamObserver<CheckRateLimitResponse> responseObserver) {
		
		logger.info("Received rate limit check request: strategy={}, clientId={}", 
				request.getStrategy(), request.getClientId());
		
		// Build the response based on whether the request is allowed.
		CheckRateLimitResponse.Builder responseBuilder = CheckRateLimitResponse.newBuilder();
		
		// Check if the request is allowed using the rate limit service.
		responseBuilder.setAllowed(
				rateLimitService.isAllowed(
						request.getClientId(),
						request.getStrategy()));
		responseBuilder.setMessage(request.getStrategy() + ":" + request.getClientId());
		
		logger.info("Rate limit check result: allowed={}", responseBuilder.getAllowed());
		
		// Send the response back to the client.
		responseObserver.onNext(responseBuilder.build());
		
		// Complete the RPC call.
		responseObserver.onCompleted();
	}
	
}
