package com.electerior.g2bmaster.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 기존 {@code requireAppAuth(req, res)} 를 대체하는 표식.
 *
 * <p>모놀리스에서는 핸들러 첫 줄마다 이 호출을 손으로 넣었고, 빠뜨려도 아무도 몰랐다.
 * 애너테이션으로 바꾸면 어떤 엔드포인트가 보호되는지 한눈에 grep 된다.
 *
 * @see AppAuthInterceptor
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAppAuth {
}
