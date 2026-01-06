package net.uebliche.mcmeta.example;

import net.md_5.bungee.api.plugin.Plugin;

public final class BungeeCordExamplePlugin extends Plugin {
  @Override
  public void onEnable() {
    getLogger().info("BungeeCord example loaded: " + CommonInfo.name());
  }
}
