package maple.expectation;

public class GuardrailTest {
  public int badMethod() {
    try {
      return riskyOperation();
    } catch (Exception e) {
      return -1;
    }
  }

  private int riskyOperation() {
    return 42;
  }
}
