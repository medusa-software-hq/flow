package com.example;

/**
 * The project's check: asserts the greeting is the expected one, failing the build otherwise. Run by
 * the `verifyGreeting` Gradle task.
 *
 * <p>Changing {@link Greeter#GREETING} therefore also requires updating {@link #EXPECTED_GREETING} —
 * which is exactly the two-file edit the loop test's fixture task asks a model to make.
 */
public final class GreetingCheck {
  private static final String EXPECTED_GREETING = "Hello";

  private GreetingCheck() {}

  public static void main(String[] args) {
    if (!EXPECTED_GREETING.equals(Greeter.GREETING)) {
      System.err.println(
          "Expected greeting '" + EXPECTED_GREETING + "' but was '" + Greeter.GREETING + "'");
      System.exit(1);
    }

    System.out.println("greeting ok: " + Greeter.GREETING);
  }
}
