package net.uebliche.mcmeta.example;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import org.slf4j.Logger;

@Plugin(
  id = "mcmeta_velocity_example",
  name = "mcmeta Velocity Example",
  version = "1.0.0"
)
public final class VelocityExamplePlugin {
  private final Logger logger;

  @Inject
  public VelocityExamplePlugin(Logger logger) {
    this.logger = logger;
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    logger.info("Velocity example loaded: {}", CommonInfo.name());
  }
}
