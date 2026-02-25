package maple.expectation.infrastructure.aop.annotation

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/** 🚀 [Write-Back 전략] DB 저장 대신 메모리 버퍼(Caffeine)에 먼저 기록하도록 가로챕니다. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
annotation class BufferedLike
