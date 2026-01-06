package net.uebliche.mcmeta.example;

public final class ForgeExampleMod {
  public static final String MOD_ID = "mcmeta_example_forge";

  public ForgeExampleMod() {
    String name = CommonInfo.name();
    System.out.println("Forge example loaded: " + name);
  }
}
