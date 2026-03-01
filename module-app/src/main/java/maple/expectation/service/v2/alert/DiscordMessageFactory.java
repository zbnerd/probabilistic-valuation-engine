package maple.expectation.service.v2.alert;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import maple.expectation.infrastructure.monitoring.ai.AiSreService.AiAnalysisResult;
import maple.expectation.service.v2.alert.dto.DiscordMessage;
import org.springframework.stereotype.Component;

/**
 * Discord 메시지 팩토리 (Issue #251 확장)
 *
 * <h3>Factory 패턴</h3>
 *
 * <p>Discord Embed 메시지 생성 로직을 캡슐화합니다.
 *
 * <h4>AI SRE 통합 (Issue #251)</h4>
 *
 * <ul>
 *   <li>AI 분석 결과 섹션 추가
 *   <li>[P0-Purple] AI Hallucination 경고 문구 필수 삽입
 *   <li>시스템 컨텍스트 요약 포함
 * </ul>
 */
@Component
public class DiscordMessageFactory {

  private static final int ERROR_COLOR = 16711680; // 빨간색
  private static final int AI_COLOR = 5793266; // 파란색 (AI 분석)
  private static final int WARNING_COLOR = 16776960; // 노란색 (경고)

  /** [P0-Purple] AI Hallucination 경고 문구 (필수) */
  private static final String AI_DISCLAIMER = "⚠️ **이 분석은 AI가 생성한 결과이므로 검증이 필요합니다.**";

  /** 기본 Critical Alert 생성 (기존 호환) */
  public DiscordMessage createCriticalEmbed(String title, String description, Throwable e) {
    return new DiscordMessage(
        List.of(
            new DiscordMessage.Embed(
                "🚨 " + title,
                description,
                ERROR_COLOR,
                createFields(e),
                new DiscordMessage.Footer("MapleExpectation Alert System"),
                ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT))));
  }

  /**
   * AI 분석이 포함된 Critical Alert 생성 (Issue #251)
   *
   * @param title 알림 제목
   * @param description 설명
   * @param e 예외
   * @param aiAnalysis AI 분석 결과 (Optional)
   * @param systemSummary 시스템 컨텍스트 요약
   * @return Discord 메시지
   */
  public DiscordMessage createCriticalEmbedWithAi(
      String title,
      String description,
      Throwable e,
      Optional<AiAnalysisResult> aiAnalysis,
      String systemSummary) {

    List<DiscordMessage.Embed> embeds = new ArrayList<>();

    // 1. 에러 정보 Embed
    embeds.add(
        new DiscordMessage.Embed(
            "🚨 " + title,
            description,
            ERROR_COLOR,
            createFieldsWithContext(e, systemSummary),
            new DiscordMessage.Footer("MapleExpectation Alert System"),
            ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)));

    // 2. AI 분석 Embed (있는 경우)
    aiAnalysis.ifPresent(analysis -> embeds.add(createAiAnalysisEmbed(analysis)));

    return new DiscordMessage(embeds);
  }

  /** AI 분석 결과 Embed 생성 */
  private DiscordMessage.Embed createAiAnalysisEmbed(AiAnalysisResult analysis) {
    List<DiscordMessage.Field> fields = new ArrayList<>();

    fields.add(new DiscordMessage.Field("🔍 Root Cause", analysis.rootCause(), false));
    fields.add(
        new DiscordMessage.Field(
            "⚡ Severity", severityEmoji(analysis.severity()) + " " + analysis.severity(), true));
    fields.add(new DiscordMessage.Field("🔗 Affected", analysis.affectedComponents(), true));
    fields.add(
        new DiscordMessage.Field(
            "📋 Action Items", formatActionItems(analysis.actionItems()), false));
    fields.add(new DiscordMessage.Field("📊 Analysis Source", analysis.analysisSource(), true));

    // [P0-Purple] 필수 경고 문구
    fields.add(new DiscordMessage.Field("⚠️ Disclaimer", AI_DISCLAIMER, false));

    return new DiscordMessage.Embed(
        "🤖 AI SRE 분석 리포트",
        "자동화된 에러 분석 결과입니다.",
        AI_COLOR,
        fields,
        new DiscordMessage.Footer("Powered by GPT-4o-mini | " + analysis.analysisSource()),
        ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT));
  }

  /** 시스템 컨텍스트가 포함된 필드 생성 */
  private List<DiscordMessage.Field> createFieldsWithContext(Throwable e, String systemSummary) {
    List<DiscordMessage.Field> fields = new ArrayList<>();

    fields.add(new DiscordMessage.Field("📄 Exception Type", e.getClass().getSimpleName(), true));
    fields.add(new DiscordMessage.Field("💻 Server", getServerIp(), true));
    fields.add(new DiscordMessage.Field("💬 Message", getShortMessage(e), false));

    if (systemSummary != null && !systemSummary.isBlank()) {
      fields.add(
          new DiscordMessage.Field("📊 System Context", truncate(systemSummary, 500), false));
    }

    fields.add(
        new DiscordMessage.Field(
            "📝 Stack Trace (Top 5)", "```java\n" + getStackTrace(e) + "\n```", false));

    return fields;
  }

  private List<DiscordMessage.Field> createFields(Throwable e) {
    return List.of(
        new DiscordMessage.Field("📄 Exception Type", e.getClass().getSimpleName(), true),
        new DiscordMessage.Field("💻 Server IP", getServerIp(), true),
        new DiscordMessage.Field("💬 Root Cause", getShortMessage(e), false),
        new DiscordMessage.Field(
            "Stack Trace (Top 5)", "```java\n" + getStackTrace(e) + "\n```", false));
  }

  private String getServerIp() {
    return System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "Unknown";
  }

  private String getShortMessage(Throwable e) {
    return e.getMessage() != null ? e.getMessage() : "No message provided";
  }

  private String getStackTrace(Throwable e) {
    return Arrays.stream(e.getStackTrace())
        .limit(5)
        .map(StackTraceElement::toString)
        .collect(Collectors.joining("\n"));
  }

  private String severityEmoji(String severity) {
    return switch (severity.toUpperCase()) {
      case "CRITICAL" -> "🔴";
      case "HIGH" -> "🟠";
      case "MEDIUM" -> "🟡";
      case "LOW" -> "🟢";
      default -> "⚪";
    };
  }

  private String formatActionItems(String actionItems) {
    if (actionItems == null || actionItems.isBlank()) {
      return "수동 점검 필요";
    }
    // 번호 목록 형식으로 정리
    return truncate(actionItems, 500);
  }

  private String truncate(String text, int maxLength) {
    if (text == null) return "";
    if (text.length() <= maxLength) return text;
    return text.substring(0, maxLength - 3) + "...";
  }
}
