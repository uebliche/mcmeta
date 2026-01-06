package net.uebliche.mcmeta.example;

import org.bukkit.plugin.java.JavaPlugin;

public final class PaperExamplePlugin extends JavaPlugin {
  @Override
  public void onEnable() {
    getLogger().info("Paper example loaded: " + CommonInfo.name());
  }
}
