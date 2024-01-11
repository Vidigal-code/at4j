package com.github.brenoepics.at4j.core.exceptions;

import com.github.brenoepics.at4j.util.rest.RestRequestInformation;
import com.github.brenoepics.at4j.util.rest.RestRequestResponseInformation;
import java.util.Optional;
import tech.brenoepic.at4j.util.rest.*;

/** This exception is always thrown whenever a request to azure failed. */
public class AzureException extends Exception {

  /**
   * The request. May be <code>null</code> if the exception was thrown before creating a request.
   */
  private final RestRequestInformation request;

  /**
   * The rest request result. May be <code>null</code> if the exception was thrown before sending a
   * request.
   */
  private final RestRequestResponseInformation response;

  /**
   * Creates a new instance of this class.
   *
   * @param origin The origin of the exception.
   * @param message The message of the exception.
   * @param request The information about the request.
   * @param response The information about the response.
   */
  public AzureException(
      Exception origin,
      String message,
      RestRequestInformation request,
      RestRequestResponseInformation response) {
    super(message, origin);
    this.request = request;
    this.response = response;
  }

  /**
   * Gets information about the request which caused the exception. May be <code>null</code> if the
   * exception was thrown before creating a request.
   *
   * @return Information about the request which caused the exception.
   */
  public Optional<RestRequestInformation> getRequest() {
    return Optional.ofNullable(request);
  }

  /**
   * Gets information about the response which caused the exception. May not be present if the
   * exception was thrown before sending a request.
   *
   * @return Information about the response which caused the exception.
   */
  public Optional<RestRequestResponseInformation> getResponse() {
    return Optional.ofNullable(response);
  }
}
