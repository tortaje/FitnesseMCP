package demo;

/** Slim script fixture whose only method always throws. */
public class Boom {
  public boolean explode() {
    throw new IllegalStateException("kaboom");
  }
}
