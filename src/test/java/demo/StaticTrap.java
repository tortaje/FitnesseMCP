package demo;

/** Fixture whose static initialiser must never run during introspection. */
public class StaticTrap {
  static {
    System.setProperty("tortaje.trap", "fired");
  }

  public String ping() { return "pong"; }
}
