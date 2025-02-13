package io.github.brenoepics.at4j.core.exceptions;

import io.github.brenoepics.at4j.util.rest.RestRequestInformation;
import io.github.brenoepics.at4j.util.rest.RestRequestResponseInformation;

/**
 * Represents a function that accepts four arguments ({@code Exception}, {@code String}, {@code
 * RestRequest<?>} and {@code RestRequestResult}) and produces a azure exception of type {@code T}.
 *
 * @param <T> The type of the azure exception that is produced.
 */
@FunctionalInterface
public interface AzureExceptionInstantiation<T extends AzureException> {

  /**
   * Creates a new instance of the class {@code T}.
   *
   * @param origin The origin of the exception.
   * @param message The message of the exception.
   * @param request The information about the request.
   * @param response The information about the response.
   * @return The new instance.
   */
  T createInstance(
      Exception origin,
      String message,
      RestRequestInformation request,
      RestRequestResponseInformation response);
}
