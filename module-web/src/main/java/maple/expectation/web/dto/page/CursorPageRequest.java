package maple.expectation.web.dto.page;

/** Cursor-based Pagination 요청 (#233) */
public record CursorPageRequest(Long cursor, int size) {
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 100;

  public static CursorPageRequest of(Long cursor, int size) {
    int validSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    return new CursorPageRequest(cursor, validSize);
  }

  public static CursorPageRequest firstPage() {
    return new CursorPageRequest(null, DEFAULT_SIZE);
  }

  public static CursorPageRequest firstPage(int size) {
    return of(null, size);
  }
}
