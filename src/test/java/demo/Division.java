package demo;

/** Slim decision-table fixture used by run tests. */
public class Division {
  private double numerator;
  private double denominator;

  public void setNumerator(double numerator) { this.numerator = numerator; }

  public void setDenominator(double denominator) { this.denominator = denominator; }

  public double quotient() { return numerator / denominator; }
}
