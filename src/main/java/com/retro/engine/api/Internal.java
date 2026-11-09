package com.retro.engine.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A public member (or nested type) of a {@link PublicApi} type that is public only so other engine packages can reach
 * it. Not part of the API: it may change or disappear in any release.
 */
@PublicApi
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface Internal {
}
