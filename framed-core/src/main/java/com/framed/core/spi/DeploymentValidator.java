package com.framed.core.spi;

import com.framed.core.Service;

import java.util.Collection;

/**
 * Extension point invoked once after all services have been instantiated, allowing
 * domain modules to validate the deployed topology (e.g. checking that a network of
 * services satisfies structural constraints) without the orchestrator depending on any
 * domain type.
 *
 * <p>Implementations are discovered at runtime via {@link java.util.ServiceLoader}, so a
 * provider must declare itself in
 * {@code META-INF/services/com.framed.core.spi.DeploymentValidator} and expose a public
 * no-argument constructor.</p>
 *
 * @see java.util.ServiceLoader
 */
public interface DeploymentValidator {

  /**
   * Validates the set of instantiated services. Implementations should inspect only the
   * services they care about (typically via {@code instanceof}) and ignore the rest.
   *
   * @param services all services instantiated by the orchestrator
   * @throws IllegalArgumentException if the deployed topology is invalid
   */
  void validate(Collection<Service> services);
}
