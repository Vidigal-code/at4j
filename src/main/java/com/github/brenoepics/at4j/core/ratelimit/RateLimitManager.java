package com.github.brenoepics.at4j.core.ratelimit;

import com.github.brenoepics.at4j.core.AzureApiImpl;
import com.github.brenoepics.at4j.core.exceptions.AzureException;
import com.github.brenoepics.at4j.util.logging.LoggerUtil;
import com.github.brenoepics.at4j.util.rest.RestRequest;
import com.github.brenoepics.at4j.util.rest.RestRequestResponseInformationImpl;
import com.github.brenoepics.at4j.util.rest.RestRequestResult;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import okhttp3.Response;
import org.apache.logging.log4j.Logger;

/** This class manages rate-limits and keeps track of them. */
public class RateLimitManager {

  /** The (logger) of this class. */
  private static final Logger logger = LoggerUtil.getLogger(RateLimitManager.class);

  /** The Azure API instance for this rate-limit manager. */
  private final AzureApiImpl api;

  /** All buckets. */
  private final Set<RatelimitBucket> buckets = new HashSet<>();

  /**
   * Creates a new rate-limit manager.
   *
   * @param api The azure api instance for this rate-limit manager.
   */
  public RateLimitManager(AzureApiImpl api) {
    this.api = api;
  }

  /**
   * Gets all rate-limit buckets.
   *
   * @return All rate-limit buckets.
   */
  public Set<RatelimitBucket> getBuckets() {
    return buckets;
  }

  /**
   * Queues the given request. This method is automatically called when using {@link
   * RestRequest#execute(Function)}!
   *
   * @param request The request to queue.
   */
  public void queueRequest(RestRequest<?> request) {
    final RatelimitBucket bucket;
    final boolean alreadyInQueue;
    synchronized (buckets) {
      // Search for a bucket that fits to this request
      bucket =
          buckets.stream()
              .filter(
                  b -> b.equals(request.getEndpoint(), request.getMajorUrlParameter().orElse(null)))
              .findAny()
              .orElseGet(
                  () ->
                      new RatelimitBucket(
                          api, request.getEndpoint(), request.getMajorUrlParameter().orElse(null)));

      // Must be executed BEFORE adding the request to the queue
      alreadyInQueue = bucket.peekRequestFromQueue() != null;

      // Add the bucket to the set of buckets (does nothing if it's already in the set)
      buckets.add(bucket);

      // Add the request to the bucket's queue
      bucket.addRequestToQueue(request);
    }

    // If the bucket is already in the queue, there's nothing more to do
    if (alreadyInQueue) {
      return;
    }

    // Start working of the queue
    api.getThreadPool()
        .getExecutorService()
        .submit(
            () -> {
              RestRequest<?> currentRequest = bucket.peekRequestFromQueue();
              RestRequestResult result = null;
              long responseTimestamp = System.currentTimeMillis();
              while (currentRequest != null) {
                try {
                  int sleepTime = bucket.getTimeTillSpaceGetsAvailable();
                  if (sleepTime > 0) {
                    logger.debug(
                        "Delaying requests to {} for {}ms to prevent hitting ratelimits",
                        bucket,
                        sleepTime);
                  }

                  // Sleep until space is available
                  while (sleepTime > 0) {
                    try {
                      Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                      logger.warn("We got interrupted while waiting for a rate limit!", e);
                    }
                    // Update in case something changed (e.g. because we hit a global rate-limit)
                    sleepTime = bucket.getTimeTillSpaceGetsAvailable();
                  }

                  // Execute the request
                  result = currentRequest.executeBlocking();

                  // Calculate the time offset if it wasn't done before
                  responseTimestamp = System.currentTimeMillis();
                } catch (Throwable t) {
                  responseTimestamp = System.currentTimeMillis();
                  if (currentRequest.getResult().isDone()) {
                    logger.warn(
                        "Received exception for a request that is already done. "
                            + "This should not be able to happen!",
                        t);
                  }
                  // Try to get the response from the exception if it exists
                  if (t instanceof AzureException) {
                    result =
                        ((AzureException) t)
                            .getResponse()
                            .map(RestRequestResponseInformationImpl.class::cast)
                            .map(RestRequestResponseInformationImpl::getRestRequestResult)
                            .orElse(null);
                  }
                  // Complete the request
                  currentRequest.getResult().completeExceptionally(t);
                } finally {
                  try {
                    // Handle the response
                    handleResponse(currentRequest, result, bucket, responseTimestamp);
                  } catch (Throwable t) {
                    logger.warn("Encountered unexpected exception.", t);
                  }

                  // The request didn't finish, so let's try again
                  if (!currentRequest.getResult().isDone()) {
                    continue;
                  }

                  // Poll a new quest
                  synchronized (buckets) {
                    bucket.pollRequestFromQueue();
                    currentRequest = bucket.peekRequestFromQueue();
                    if (currentRequest == null) {
                      buckets.remove(bucket);
                    }
                  }
                }
              }
            });
  }

  /**
   * Updates the rate-limit information and sets the result if the request was successful.
   *
   * @param request The request.
   * @param result The result of the request.
   * @param bucket The bucket the request belongs to.
   * @param responseTimestamp The timestamp directly after the response finished.
   */
  private void handleResponse(
      RestRequest<?> request,
      RestRequestResult result,
      RatelimitBucket bucket,
      long responseTimestamp) {
    if (result == null || result.getResponse() == null) {
      return;
    }
    Response response = result.getResponse();
    boolean global =
        Objects.requireNonNull(response.header("X-RateLimit-Global", "false"))
            .equalsIgnoreCase("true");
    int remaining =
        Integer.parseInt(Objects.requireNonNull(response.header("X-RateLimit-Remaining", "1")));
    long reset =
        (long)
            (Double.parseDouble(Objects.requireNonNull(response.header("X-RateLimit-Reset", "0")))
                * 1000);

    // Check if we received a 429 response
    if (result.getResponse().code() == 429) {
      if (response.header("Via") == null) {
        logger.warn(
            "Hit a CloudFlare API ban! This means you were sending a very large "
                + "amount of invalid requests.");
        long retryAfter =
            Long.parseLong(Objects.requireNonNull(response.header("Retry-after"))) * 1000;
        RatelimitBucket.setGlobalRatelimitResetTimestamp(api, responseTimestamp + retryAfter);
        return;
      }
      long retryAfter =
          result.getJsonBody().isNull()
              ? 0
              : (long) (result.getJsonBody().get("retry_after").asDouble() * 1000);

      if (global) {
        // We hit a global rate-limit. Time to panic!
        logger.warn(
            "Hit a global rate-limit! This means you were sending a very large "
                + "amount within a very short time frame.");
        RatelimitBucket.setGlobalRatelimitResetTimestamp(api, responseTimestamp + retryAfter);
      } else {
        logger.debug("Received a 429 response from Discord! Recalculating time offset...");

        // Update the bucket information
        bucket.setRatelimitRemaining(0);
        bucket.setRatelimitResetTimestamp(responseTimestamp + retryAfter);
      }
    } else {
      // Check if we didn't already complete it exceptionally.
      CompletableFuture<RestRequestResult> requestResult = request.getResult();
      if (!requestResult.isDone()) {
        requestResult.complete(result);
      }

      // Update bucket information
      bucket.setRatelimitRemaining(remaining);
      bucket.setRatelimitResetTimestamp(reset);
    }
  }
}
