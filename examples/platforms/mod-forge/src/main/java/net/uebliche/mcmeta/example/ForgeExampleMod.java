package net.uebliche.mcmeta.example;

import net.minecraftforge.fml.common.Mod;

@Mod(ForgeExampleMod.MOD_ID)
public final class ForgeExampleMod {
  public static final String MOD_ID = "mcmeta_example_forge";

  public ForgeExampleMod() {
    String name = CommonInfo.name();
    System.out.println("Forge example loaded: " + name);
  }
}
