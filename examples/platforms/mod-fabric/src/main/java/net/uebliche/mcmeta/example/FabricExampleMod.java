package net.uebliche.mcmeta.example;

import net.fabricmc.api.ModInitializer;

public final class FabricExampleMod implements ModInitializer {
  public static final String MOD_ID = "mcmeta_example_fabric";

  @Override
  public void onInitialize() {
    String name = CommonInfo.name();
    System.out.println("Fabric example loaded: " + name);
  }
}
