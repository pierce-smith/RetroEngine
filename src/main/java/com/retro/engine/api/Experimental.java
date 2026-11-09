package com.retro.engine.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Public API that is still settling: usable by scripts, but it may change in a minor release, with a changelog note.
 * Applies to a type (with its members) or to single members. Experimental signatures are listed in the API baseline
 * with an {@code [experimental]} tag and must still only mention API types.
 */
@PublicApi
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface Experimental {
}
